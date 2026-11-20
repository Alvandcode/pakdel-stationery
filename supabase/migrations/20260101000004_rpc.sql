-- =============================================================
-- Pakdel Stationery — 04 Business RPCs (atomic, idempotent)
-- تمام تغییرات مالی و انبار فقط از این مسیرها انجام می‌شود.
-- =============================================================

-- -------------------------------------------------------------
-- ثبت اتمیک فاکتور فروش
-- -------------------------------------------------------------
create or replace function public.rpc_checkout_sale(p_payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store      uuid := (p_payload ->> 'store_id')::uuid;
  v_branch     uuid := nullif(p_payload ->> 'branch_id', '')::uuid;
  v_sale_id    uuid := (p_payload ->> 'id')::uuid;
  v_op         uuid := (p_payload ->> 'client_operation_id')::uuid;
  v_customer   uuid := nullif(p_payload ->> 'customer_id', '')::uuid;
  v_method     payment_method := coalesce((p_payload ->> 'payment_method')::payment_method, 'cash');
  v_note       text := p_payload ->> 'note';
  v_items      jsonb := coalesce(p_payload -> 'items', '[]'::jsonb);
  v_payments   jsonb := coalesce(p_payload -> 'payments', '[]'::jsonb);
  v_inv_disc   bigint := coalesce((p_payload ->> 'invoice_discount')::bigint, 0);
  v_tax        bigint := coalesce((p_payload ->> 'tax_total')::bigint, 0);

  v_existing   public.sales%rowtype;
  v_item       jsonb;
  v_prod       public.products%rowtype;
  v_variant    public.product_variants%rowtype;
  v_price      bigint;
  v_cost       bigint;
  v_qty        int;
  v_disc       bigint;
  v_line       bigint;
  v_subtotal   bigint := 0;
  v_item_disc  bigint := 0;
  v_cogs       bigint := 0;
  v_grand      bigint;
  v_paid       bigint := 0;
  v_due        bigint;
  v_pay        jsonb;
  v_invoice    text;
  v_rows       jsonb := '[]'::jsonb;
  v_can_override boolean := public.has_permission('sale.price_override');
  v_cust_balance bigint;
begin
  -- ۱) دسترسی
  if not public.is_store_staff(v_store) then
    raise exception 'FORBIDDEN: کاربر عضو این فروشگاه نیست.';
  end if;
  if not public.has_permission('sale.create') then
    raise exception 'FORBIDDEN: دسترسی صدور فاکتور وجود ندارد.';
  end if;

  -- ۲) تکرارناپذیری
  select * into v_existing from public.sales
   where store_id = v_store and client_operation_id = v_op;
  if found then
    return jsonb_build_object(
      'sale_id', v_existing.id,
      'invoice_number', v_existing.invoice_number,
      'grand_total', v_existing.grand_total,
      'due_total', v_existing.due_total,
      'duplicate', true
    );
  end if;

  if jsonb_array_length(v_items) = 0 then
    raise exception 'VALIDATION: فاکتور بدون قلم است.';
  end if;
  if v_inv_disc < 0 then
    raise exception 'INVALID_DISCOUNT: تخفیف فاکتور منفی است.';
  end if;

  -- ۳) اعتبارسنجی اقلام (قفل ردیف محصول برای جلوگیری از فروش هم‌زمان موجودی آخر)
  for v_item in
    select value from jsonb_array_elements(v_items)
     order by (value ->> 'product_id')
  loop
    v_qty := coalesce((v_item ->> 'qty')::int, 0);
    if v_qty <= 0 then
      raise exception 'VALIDATION: تعداد قلم باید بزرگ‌تر از صفر باشد.';
    end if;

    select * into v_prod from public.products
     where id = (v_item ->> 'product_id')::uuid and store_id = v_store
     for update;
    if not found then
      raise exception 'PRODUCT_NOT_FOUND: کالا یافت نشد.';
    end if;
    if v_prod.deleted_at is not null or not v_prod.is_active then
      raise exception 'PRODUCT_NOT_FOUND: کالا غیرفعال یا حذف شده است: %', v_prod.name;
    end if;
    if v_prod.stock_cached < v_qty then
      raise exception 'INSUFFICIENT_STOCK: موجودی کافی نیست: % (موجودی: %)', v_prod.name, v_prod.stock_cached;
    end if;

    v_variant := null;
    if nullif(v_item ->> 'variant_id', '') is not null then
      select * into v_variant from public.product_variants
       where id = (v_item ->> 'variant_id')::uuid and product_id = v_prod.id;
      if not found then
        raise exception 'PRODUCT_NOT_FOUND: تنوع کالا یافت نشد.';
      end if;
      if v_variant.stock_cached < v_qty then
        raise exception 'INSUFFICIENT_STOCK: موجودی این تنوع کافی نیست (موجودی: %).', v_variant.stock_cached;
      end if;
    end if;

    -- قیمت: کلاینت فقط با مجوز می‌تواند قیمت دلخواه بدهد
    if v_can_override and nullif(v_item ->> 'unit_price', '') is not null then
      v_price := (v_item ->> 'unit_price')::bigint;
      if v_price < 0 then
        raise exception 'VALIDATION: قیمت منفی است.';
      end if;
    else
      v_price := case when v_variant.id is not null
                      then coalesce(nullif(v_variant.sale_price, 0), v_prod.sale_price)
                      else v_prod.sale_price end;
    end if;
    v_cost := case when v_variant.id is not null
                   then coalesce(nullif(v_variant.purchase_price, 0), v_prod.purchase_price)
                   else v_prod.purchase_price end;

    v_line := v_price * v_qty;
    v_disc := coalesce((v_item ->> 'discount')::bigint, 0);
    if v_disc < 0 or v_disc > v_line then
      raise exception 'INVALID_DISCOUNT: تخفیف قلم نامعتبر است.';
    end if;

    v_subtotal  := v_subtotal + v_line;
    v_item_disc := v_item_disc + v_disc;
    v_cogs      := v_cogs + v_cost * v_qty;

    v_rows := v_rows || jsonb_build_object(
      'id', gen_random_uuid(),
      'product_id', v_prod.id,
      'variant_id', v_variant.id,
      'name', v_prod.name || case when v_variant.id is not null
              then ' ' || concat_ws(' / ', v_variant.color, v_variant.size, v_variant.model) else '' end,
      'qty', v_qty,
      'unit_price', v_price,
      'unit_cost', v_cost,
      'discount', v_disc,
      'line_total', v_line - v_disc
    );
  end loop;

  if v_inv_disc > (v_subtotal - v_item_disc) then
    raise exception 'INVALID_DISCOUNT: تخفیف فاکتور بیش از مبلغ قابل تخفیف است.';
  end if;
  v_grand := v_subtotal - v_item_disc - v_inv_disc + v_tax;
  if v_grand < 0 then
    raise exception 'INVALID_DISCOUNT: مبلغ نهایی منفی شد.';
  end if;

  for v_pay in select value from jsonb_array_elements(v_payments) loop
    if coalesce((v_pay ->> 'amount')::bigint, 0) > 0 then
      v_paid := v_paid + (v_pay ->> 'amount')::bigint;
    end if;
  end loop;
  if v_paid > v_grand then
    raise exception 'INVALID_PAYMENT: مبلغ پرداخت بیش از مبلغ فاکتور است.';
  end if;
  v_due := v_grand - v_paid;

  -- ۴) درج فاکتور
  v_invoice := public.fn_next_invoice_number(v_store);

  insert into public.sales (
    id, store_id, branch_id, customer_id, cashier_id, invoice_number,
    sale_type, status, subtotal, item_discount_total, invoice_discount, discount_total,
    tax_total, grand_total, paid_total, due_total, payment_method, note,
    client_operation_id, sold_at
  ) values (
    v_sale_id, v_store, v_branch, v_customer, auth.uid(), v_invoice,
    'sale', 'completed', v_subtotal, v_item_disc, v_inv_disc, v_item_disc + v_inv_disc,
    v_tax, v_grand, v_paid, v_due,
    case when v_due > 0 and v_paid > 0 then 'mixed'::payment_method else v_method end,
    v_note, v_op, now()
  );

  -- ۵) اقلام + گردش انبار
  for v_item in select value from jsonb_array_elements(v_rows) loop
    insert into public.sale_items (
      id, sale_id, product_id, variant_id, name_snapshot, qty,
      unit_price, unit_cost, discount, line_total
    ) values (
      (v_item ->> 'id')::uuid, v_sale_id,
      (v_item ->> 'product_id')::uuid,
      nullif(v_item ->> 'variant_id', '')::uuid,
      v_item ->> 'name',
      (v_item ->> 'qty')::int,
      (v_item ->> 'unit_price')::bigint,
      (v_item ->> 'unit_cost')::bigint,
      (v_item ->> 'discount')::bigint,
      (v_item ->> 'line_total')::bigint
    );

    insert into public.inventory_transactions (
      id, store_id, branch_id, product_id, variant_id, qty_delta, type,
      reference_type, reference_id, unit_cost, reason, user_id, client_operation_id
    ) values (
      gen_random_uuid(), v_store, v_branch,
      (v_item ->> 'product_id')::uuid,
      nullif(v_item ->> 'variant_id', '')::uuid,
      -((v_item ->> 'qty')::int), 'sale',
      'sale', v_sale_id, (v_item ->> 'unit_cost')::bigint,
      'فروش فاکتور ' || v_invoice, auth.uid(), gen_random_uuid()
    );
  end loop;

  -- ۶) پرداخت‌ها
  for v_pay in select value from jsonb_array_elements(v_payments) loop
    if coalesce((v_pay ->> 'amount')::bigint, 0) > 0 then
      insert into public.payments (
        id, store_id, direction, party_type, party_id, method, amount,
        reference, user_id, client_operation_id
      ) values (
        gen_random_uuid(), v_store, 'in', 'sale', v_sale_id,
        coalesce((v_pay ->> 'method')::payment_method, v_method),
        (v_pay ->> 'amount')::bigint,
        v_pay ->> 'reference', auth.uid(), gen_random_uuid()
      );
    end if;
  end loop;

  -- ۷) بدهی مشتری
  if v_customer is not null and v_due <> 0 then
    update public.customers
       set balance_cached = balance_cached + v_due, updated_at = now()
     where id = v_customer and store_id = v_store
     returning balance_cached into v_cust_balance;

    if v_cust_balance > coalesce((select credit_limit from public.customers where id = v_customer), 0)
       and coalesce((select credit_limit from public.customers where id = v_customer), 0) > 0 then
      raise exception 'CREDIT_LIMIT: سقف اعتبار مشتری تکمیل است.';
    end if;
  end if;

  return jsonb_build_object(
    'sale_id', v_sale_id,
    'invoice_number', v_invoice,
    'grand_total', v_grand,
    'due_total', v_due,
    'cogs', v_cogs,
    'duplicate', false
  );
end;
$$;

-- -------------------------------------------------------------
-- لغو فاکتور (با بازگشت موجودی و بدهی)
-- -------------------------------------------------------------
create or replace function public.rpc_cancel_sale(p_sale_id uuid, p_reason text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_sale public.sales%rowtype;
  v_item public.sale_items%rowtype;
begin
  select * into v_sale from public.sales where id = p_sale_id for update;
  if not found then
    raise exception 'NOT_FOUND: فاکتور یافت نشد.';
  end if;
  if not public.has_permission('sale.cancel') then
    raise exception 'FORBIDDEN: دسترسی لغو فاکتور وجود ندارد.';
  end if;
  if v_sale.status = 'canceled' then
    raise exception 'ALREADY_CANCELED: فاکتور قبلاً لغو شده است.';
  end if;

  for v_item in select * from public.sale_items where sale_id = p_sale_id loop
    insert into public.inventory_transactions (
      id, store_id, branch_id, product_id, variant_id, qty_delta, type,
      reference_type, reference_id, unit_cost, reason, user_id, client_operation_id
    ) values (
      gen_random_uuid(), v_sale.store_id, v_sale.branch_id,
      v_item.product_id, v_item.variant_id, v_item.qty, 'return_sale',
      'sale', v_sale.id, v_item.unit_cost,
      coalesce(p_reason, 'لغو فاکتور'), auth.uid(), gen_random_uuid()
    );
  end loop;

  update public.sales
     set status = 'canceled', canceled_at = now(), cancel_reason = p_reason, updated_at = now()
   where id = p_sale_id;

  if v_sale.customer_id is not null and v_sale.due_total <> 0 then
    update public.customers
       set balance_cached = greatest(balance_cached - v_sale.due_total, 0), updated_at = now()
     where id = v_sale.customer_id;
  end if;

  return jsonb_build_object('sale_id', p_sale_id, 'status', 'canceled');
end;
$$;

-- -------------------------------------------------------------
-- اصلاح/ورود/خروج موجودی
-- -------------------------------------------------------------
create or replace function public.rpc_adjust_stock(p_payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store   uuid := (p_payload ->> 'store_id')::uuid;
  v_branch  uuid := nullif(p_payload ->> 'branch_id', '')::uuid;
  v_prod    uuid := (p_payload ->> 'product_id')::uuid;
  v_var     uuid := nullif(p_payload ->> 'variant_id', '')::uuid;
  v_delta   int  := (p_payload ->> 'qty_delta')::int;
  v_type    inventory_tx_type := (p_payload ->> 'type')::inventory_tx_type;
  v_reason  text := p_payload ->> 'reason';
  v_op      uuid := (p_payload ->> 'client_operation_id')::uuid;
  v_cost    bigint := coalesce((p_payload ->> 'unit_cost')::bigint, 0);
  v_stock   int;
begin
  if not public.is_store_staff(v_store) then
    raise exception 'FORBIDDEN: کاربر عضو این فروشگاه نیست.';
  end if;
  if not public.has_permission('inventory.adjust') then
    raise exception 'FORBIDDEN: دسترسی اصلاح موجودی وجود ندارد.';
  end if;
  if v_delta = 0 then
    raise exception 'VALIDATION: مقدار تغییر صفر است.';
  end if;

  if exists (select 1 from public.inventory_transactions
              where store_id = v_store and client_operation_id = v_op) then
    return jsonb_build_object('duplicate', true);
  end if;

  select stock_cached into v_stock from public.products
   where id = v_prod and store_id = v_store for update;
  if not found then
    raise exception 'PRODUCT_NOT_FOUND: کالا یافت نشد.';
  end if;
  if v_stock + v_delta < 0 then
    raise exception 'INSUFFICIENT_STOCK: موجودی کافی نیست (موجودی فعلی: %).', v_stock;
  end if;

  insert into public.inventory_transactions (
    id, store_id, branch_id, product_id, variant_id, qty_delta, type,
    reference_type, reason, user_id, client_operation_id, unit_cost
  ) values (
    gen_random_uuid(), v_store, v_branch, v_prod, v_var, v_delta, v_type,
    'manual', v_reason, auth.uid(), v_op, v_cost
  );

  select stock_cached into v_stock from public.products where id = v_prod;
  return jsonb_build_object('product_id', v_prod, 'stock', v_stock, 'duplicate', false);
end;
$$;

-- -------------------------------------------------------------
-- ثبت سفارش آنلاین (قیمت و موجودی از سرور)
-- -------------------------------------------------------------
create or replace function public.rpc_place_order(p_payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store    uuid := (p_payload ->> 'store_id')::uuid;
  v_order_id uuid := (p_payload ->> 'id')::uuid;
  v_op       uuid := (p_payload ->> 'client_operation_id')::uuid;
  v_cust     uuid := (p_payload ->> 'customer_id')::uuid;
  v_fulfil   order_fulfillment := coalesce((p_payload ->> 'fulfillment')::order_fulfillment, 'delivery');
  v_addr     uuid := nullif(p_payload ->> 'address_id', '')::uuid;
  v_ship     bigint := coalesce((p_payload ->> 'shipping_total')::bigint, 0);
  v_note     text := p_payload ->> 'note';
  v_code     text := nullif(upper(trim(p_payload ->> 'coupon_code')), '');
  v_items    jsonb := coalesce(p_payload -> 'items', '[]'::jsonb);
  v_item     jsonb;
  v_prod     public.products%rowtype;
  v_price    bigint;
  v_qty      int;
  v_sub      bigint := 0;
  v_rows     jsonb := '[]'::jsonb;
  v_grand    bigint;
  v_order_no text;
  v_coupon   public.coupons%rowtype;
  v_disc     bigint := 0;
  v_existing public.orders%rowtype;
begin
  if not exists (select 1 from public.customers c
                  where c.id = v_cust and c.store_id = v_store
                    and (c.user_id = auth.uid() or public.is_store_staff(v_store))) then
    raise exception 'FORBIDDEN: مشتری نامعتبر است.';
  end if;

  select * into v_existing from public.orders
   where store_id = v_store and client_operation_id = v_op;
  if found then
    return jsonb_build_object('order_id', v_existing.id, 'order_number', v_existing.order_number,
                              'grand_total', v_existing.grand_total, 'status', v_existing.status,
                              'duplicate', true);
  end if;

  if jsonb_array_length(v_items) = 0 then
    raise exception 'VALIDATION: سفارش بدون قلم است.';
  end if;

  for v_item in select value from jsonb_array_elements(v_items) order by (value ->> 'product_id') loop
    v_qty := coalesce((v_item ->> 'qty')::int, 0);
    if v_qty <= 0 then
      raise exception 'VALIDATION: تعداد نامعتبر است.';
    end if;

    select * into v_prod from public.products
     where id = (v_item ->> 'product_id')::uuid and store_id = v_store
     for update;
    if not found or v_prod.deleted_at is not null or not v_prod.is_active or not v_prod.is_public then
      raise exception 'PRODUCT_NOT_FOUND: کالا برای فروش آنلاین در دسترس نیست.';
    end if;
    if v_prod.stock_cached < v_qty then
      raise exception 'INSUFFICIENT_STOCK: موجودی کافی نیست: %', v_prod.name;
    end if;

    v_price := v_prod.sale_price;
    v_sub := v_sub + v_price * v_qty;

    v_rows := v_rows || jsonb_build_object(
      'id', gen_random_uuid(),
      'product_id', v_prod.id,
      'variant_id', null,
      'name', v_prod.name,
      'qty', v_qty,
      'unit_price', v_price,
      'line_total', v_price * v_qty
    );
  end loop;

  -- کوپن
  if v_code is not null then
    select * into v_coupon from public.coupons
     where store_id = v_store and code = v_code and is_active
       and (starts_at is null or starts_at <= now())
       and (ends_at is null or ends_at >= now());
    if found and v_sub >= v_coupon.min_subtotal
       and (v_coupon.usage_limit is null or v_coupon.used_count < v_coupon.usage_limit) then
      v_disc := case when v_coupon.type = 'percent'
                     then round(v_sub::numeric * v_coupon.value / 100)::bigint
                     else v_coupon.value end;
      if v_coupon.max_discount is not null then
        v_disc := least(v_disc, v_coupon.max_discount);
      end if;
      v_disc := least(v_disc, v_sub);
    else
      v_coupon := null;
    end if;
  end if;

  v_grand := greatest(v_sub - v_disc + case when v_fulfil = 'delivery' then v_ship else 0 end, 0);
  v_order_no := public.fn_next_order_number(v_store);

  insert into public.orders (
    id, store_id, customer_id, order_number, status, fulfillment, address_id,
    subtotal, item_discount_total, discount_total, shipping_total, grand_total,
    coupon_id, note, client_operation_id, placed_at
  ) values (
    v_order_id, v_store, v_cust, v_order_no, 'pending', v_fulfil, v_addr,
    v_sub, 0, v_disc, case when v_fulfil = 'delivery' then v_ship else 0 end, v_grand,
    v_coupon.id, v_note, v_op, now()
  );

  for v_item in select value from jsonb_array_elements(v_rows) loop
    insert into public.order_items (
      id, order_id, product_id, variant_id, name_snapshot, qty, unit_price, discount, line_total
    ) values (
      (v_item ->> 'id')::uuid, v_order_id,
      (v_item ->> 'product_id')::uuid,
      nullif(v_item ->> 'variant_id', '')::uuid,
      v_item ->> 'name',
      (v_item ->> 'qty')::int,
      (v_item ->> 'unit_price')::bigint,
      0,
      (v_item ->> 'line_total')::bigint
    );
  end loop;

  if v_coupon.id is not null then
    update public.coupons set used_count = used_count + 1 where id = v_coupon.id;
    insert into public.coupon_redemptions (coupon_id, customer_id, order_id, amount)
    values (v_coupon.id, v_cust, v_order_id, v_disc);
  end if;

  insert into public.order_status_history (order_id, from_status, to_status, changed_by, note)
  values (v_order_id, null, 'pending', auth.uid(), 'ثبت سفارش');

  return jsonb_build_object('order_id', v_order_id, 'order_number', v_order_no,
                            'grand_total', v_grand, 'status', 'pending', 'duplicate', false);
end;
$$;

-- -------------------------------------------------------------
-- تغییر وضعیت سفارش + کسر موجودی هنگام تأیید
-- -------------------------------------------------------------
create or replace function public.rpc_set_order_status(p_order_id uuid, p_status order_status, p_note text default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_order public.orders%rowtype;
  v_item  public.order_items%rowtype;
  v_prod  public.products%rowtype;
begin
  select * into v_order from public.orders where id = p_order_id for update;
  if not found then
    raise exception 'NOT_FOUND: سفارش یافت نشد.';
  end if;

  if not public.is_store_staff(v_order.store_id) or not public.has_permission('order.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت سفارش وجود ندارد.';
  end if;
  if not public.fn_order_transition_allowed(v_order.status, p_status) then
    raise exception 'INVALID_TRANSITION: گذار از % به % مجاز نیست.', v_order.status, p_status;
  end if;

  if v_order.status = 'pending' and p_status = 'approved' then
    for v_item in select * from public.order_items where order_id = p_order_id order by product_id loop
      select * into v_prod from public.products where id = v_item.product_id for update;
      if v_prod.stock_cached < v_item.qty then
        raise exception 'INSUFFICIENT_STOCK: موجودی کافی نیست: %', v_prod.name;
      end if;

      insert into public.inventory_transactions (
        id, store_id, branch_id, product_id, variant_id, qty_delta, type,
        reference_type, reference_id, unit_cost, reason, user_id, client_operation_id
      ) values (
        gen_random_uuid(), v_order.store_id, v_order.branch_id, v_item.product_id, v_item.variant_id,
        -v_item.qty, 'sale', 'order', v_order.id, v_prod.purchase_price,
        'سفارش آنلاین ' || v_order.order_number, auth.uid(), gen_random_uuid()
      );
    end loop;
  end if;

  if v_order.status = 'approved' and p_status = 'canceled' then
    for v_item in select * from public.order_items where order_id = p_order_id order by product_id loop
      insert into public.inventory_transactions (
        id, store_id, branch_id, product_id, variant_id, qty_delta, type,
        reference_type, reference_id, unit_cost, reason, user_id, client_operation_id
      ) values (
        gen_random_uuid(), v_order.store_id, v_order.branch_id, v_item.product_id, v_item.variant_id,
        v_item.qty, 'return_sale', 'order', v_order.id, 0,
        'لغو سفارش ' || v_order.order_number, auth.uid(), gen_random_uuid()
      );
    end loop;
  end if;

  update public.orders set status = p_status, updated_at = now() where id = p_order_id;
  insert into public.order_status_history (order_id, from_status, to_status, changed_by, note)
  values (p_order_id, v_order.status, p_status, auth.uid(), p_note);

  -- امتیاز باشگاه مشتریان در تحویل موفق
  if p_status = 'delivered' then
    perform public.rpc_loyalty_award(
      v_order.customer_id,
      greatest(floor(v_order.grand_total / 10000)::int, 1),
      'خرید سفارش ' || v_order.order_number
    );
  end if;

  -- اعلان به مشتری
  insert into public.notifications (store_id, user_id, audience, title, body, type, data)
  select v_order.store_id, c.user_id, 'customer', 'تغییر وضعیت سفارش',
         'سفارش ' || v_order.order_number || ' به وضعیت ' || p_status || ' تغییر کرد.',
         'order', jsonb_build_object('order_id', v_order.id)
    from public.customers c where c.id = v_order.customer_id;

  return jsonb_build_object('order_id', p_order_id, 'status', p_status);
end;
$$;

-- -------------------------------------------------------------
-- اعتبارسنجی کوپن
-- -------------------------------------------------------------
create or replace function public.rpc_validate_coupon(p_store_id uuid, p_code text, p_subtotal bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v public.coupons%rowtype;
  v_disc bigint;
begin
  select * into v from public.coupons
   where store_id = p_store_id and code = upper(trim(p_code)) and is_active
     and (starts_at is null or starts_at <= now())
     and (ends_at is null or ends_at >= now());
  if not found then
    return jsonb_build_object('valid', false, 'discount', 0, 'message', 'کد تخفیف نامعتبر است.');
  end if;
  if p_subtotal < v.min_subtotal then
    return jsonb_build_object('valid', false, 'discount', 0,
      'message', 'حداقل مبلغ خرید برای این کد رعایت نشده است.');
  end if;
  if v.usage_limit is not null and v.used_count >= v.usage_limit then
    return jsonb_build_object('valid', false, 'discount', 0, 'message', 'ظرفیت این کد تخفیف تکمیل شده است.');
  end if;

  v_disc := case when v.type = 'percent'
                 then round(p_subtotal::numeric * v.value / 100)::bigint
                 else v.value end;
  if v.max_discount is not null then v_disc := least(v_disc, v.max_discount); end if;
  v_disc := least(v_disc, p_subtotal);

  return jsonb_build_object('valid', true, 'coupon_id', v.id, 'discount', v_disc, 'message', 'کد تخفیف اعمال شد.');
end;
$$;

-- -------------------------------------------------------------
-- امتیاز باشگاه مشتریان (فقط سرور)
-- -------------------------------------------------------------
create or replace function public.rpc_loyalty_award(p_customer_id uuid, p_points int, p_reason text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid;
  v_acc   public.loyalty_accounts%rowtype;
begin
  select store_id into v_store from public.customers where id = p_customer_id;
  if v_store is null then
    raise exception 'NOT_FOUND: مشتری یافت نشد.';
  end if;
  if not (public.is_store_staff(v_store) or auth.role() = 'service_role') then
    raise exception 'FORBIDDEN: ثبت امتیاز فقط توسط سرور انجام می‌شود.';
  end if;
  if p_points = 0 then
    raise exception 'VALIDATION: مقدار امتیاز صفر است.';
  end if;

  insert into public.loyalty_accounts (id, store_id, customer_id)
  values (gen_random_uuid(), v_store, p_customer_id)
  on conflict (store_id, customer_id) do nothing;

  select * into v_acc from public.loyalty_accounts
   where store_id = v_store and customer_id = p_customer_id for update;

  update public.loyalty_accounts
     set points = greatest(points + p_points, 0),
         lifetime_points = lifetime_points + greatest(p_points, 0),
         tier = case
                  when lifetime_points + greatest(p_points,0) >= 5000 then 'gold'
                  when lifetime_points + greatest(p_points,0) >= 1500 then 'silver'
                  else 'bronze' end,
         updated_at = now()
   where id = v_acc.id
   returning * into v_acc;

  insert into public.loyalty_transactions (account_id, points_delta, reason)
  values (v_acc.id, p_points, p_reason);

  return jsonb_build_object('account_id', v_acc.id, 'points', v_acc.points, 'tier', v_acc.tier);
end;
$$;

-- -------------------------------------------------------------
-- خلاصه داشبورد مدیریتی
-- -------------------------------------------------------------
create or replace function public.rpc_dashboard_summary(p_from timestamptz, p_to timestamptz)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid := public.auth_store_id();
  v_sales bigint;
  v_invoices int;
  v_cogs bigint;
  v_expenses bigint;
  v_stock_value bigint;
  v_low_stock int;
  v_customer_debt bigint;
  v_supplier_debt bigint;
  v_new_orders int;
begin
  if v_store is null then
    raise exception 'FORBIDDEN: فروشگاه مشخص نیست.';
  end if;
  if not public.has_permission('report.view') then
    raise exception 'FORBIDDEN: دسترسی گزارش وجود ندارد.';
  end if;

  select coalesce(sum(case when sale_type = 'return' then -grand_total else grand_total end), 0),
         count(*)
    into v_sales, v_invoices
    from public.sales
   where store_id = v_store and status = 'completed'
     and sold_at >= p_from and sold_at < p_to;

  select coalesce(sum(si.unit_cost * si.qty), 0)
    into v_cogs
    from public.sale_items si
    join public.sales s on s.id = si.sale_id
   where s.store_id = v_store and s.status = 'completed' and s.sale_type = 'sale'
     and s.sold_at >= p_from and s.sold_at < p_to;

  select coalesce(sum(amount), 0) into v_expenses
    from public.expenses
   where store_id = v_store and paid_at >= p_from and paid_at < p_to;

  select coalesce(sum(stock_cached * purchase_price), 0), count(*) filter (where stock_cached <= min_stock)
    into v_stock_value, v_low_stock
    from public.products
   where store_id = v_store and is_active and deleted_at is null;

  select coalesce(sum(greatest(balance_cached, 0)), 0) into v_customer_debt
    from public.customers where store_id = v_store and deleted_at is null;

  select coalesce(sum(greatest(due_total, 0)), 0) into v_supplier_debt
    from public.purchases where store_id = v_store and status <> 'canceled';

  select count(*) into v_new_orders
    from public.orders where store_id = v_store and status = 'pending';

  return jsonb_build_object(
    'sales_total', v_sales,
    'invoice_count', v_invoices,
    'cogs_total', v_cogs,
    'gross_profit', v_sales - v_cogs,
    'expenses_total', v_expenses,
    'net_profit_estimate', v_sales - v_cogs - v_expenses,
    'stock_value', v_stock_value,
    'low_stock_count', v_low_stock,
    'customer_debt', v_customer_debt,
    'supplier_debt', v_supplier_debt,
    'new_orders', v_new_orders
  );
end;
$$;

-- -------------------------------------------------------------
-- ثبت دریافت/پرداخت مستقل
-- -------------------------------------------------------------
create or replace function public.rpc_record_payment(p_payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid := (p_payload ->> 'store_id')::uuid;
  v_id    uuid := (p_payload ->> 'id')::uuid;
  v_op    uuid := (p_payload ->> 'client_operation_id')::uuid;
  v_party party_type := (p_payload ->> 'party_type')::party_type;
  v_dir   payment_direction := (p_payload ->> 'direction')::payment_direction;
  v_amt   bigint := (p_payload ->> 'amount')::bigint;
  v_pid   uuid := nullif(p_payload ->> 'party_id', '')::uuid;
begin
  if not public.is_store_staff(v_store) then
    raise exception 'FORBIDDEN: کاربر عضو این فروشگاه نیست.';
  end if;
  if v_dir = 'in' and not public.has_permission('finance.receive') then
    raise exception 'FORBIDDEN: دسترسی ثبت دریافت وجود ندارد.';
  end if;
  if v_dir = 'out' and not public.has_permission('finance.pay') then
    raise exception 'FORBIDDEN: دسترسی ثبت پرداخت وجود ندارد.';
  end if;
  if v_amt <= 0 then
    raise exception 'VALIDATION: مبلغ باید بزرگ‌تر از صفر باشد.';
  end if;
  if exists (select 1 from public.payments where store_id = v_store and client_operation_id = v_op) then
    return jsonb_build_object('duplicate', true);
  end if;

  insert into public.payments (id, store_id, direction, party_type, party_id, method, amount,
                               reference, note, user_id, client_operation_id)
  values (v_id, v_store, v_dir, v_party, v_pid,
          coalesce((p_payload ->> 'method')::payment_method, 'cash'),
          v_amt, p_payload ->> 'reference', p_payload ->> 'note', auth.uid(), v_op);

  if v_dir = 'in' and v_party in ('customer', 'sale', 'order') and v_pid is not null then
    update public.customers set balance_cached = greatest(balance_cached - v_amt, 0), updated_at = now()
     where id = v_pid and store_id = v_store;
  elsif v_dir = 'out' and v_party = 'supplier' and v_pid is not null then
    update public.suppliers set balance_cached = greatest(balance_cached - v_amt, 0), updated_at = now()
     where id = v_pid and store_id = v_store;
  end if;

  return jsonb_build_object('payment_id', v_id, 'duplicate', false);
end;
$$;

-- توابع کمکی سیستمی مستقیماً از کلاینت قابل فراخوانی نیستند
revoke all on function public.fn_next_counter(uuid, text) from public, anon, authenticated;
revoke all on function public.fn_next_invoice_number(uuid) from public, anon, authenticated;
revoke all on function public.fn_next_order_number(uuid) from public, anon, authenticated;
