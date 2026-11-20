-- =============================================================
-- Pakdel Stationery — 07 Product RPCs (atomic, idempotent)
-- ثبت/ویرایش/غیرفعال‌سازی محصول + تاریخچه قیمت + موجودی اولیه
-- =============================================================

create or replace function public.rpc_upsert_product(p_payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store         uuid := (p_payload ->> 'store_id')::uuid;
  v_id            uuid := (p_payload ->> 'id')::uuid;
  v_name          text := trim(coalesce(p_payload ->> 'name', ''));
  v_barcode       text := nullif(trim(p_payload ->> 'barcode'), '');
  v_sku           text := nullif(trim(p_payload ->> 'sku'), '');
  v_purchase      bigint := coalesce((p_payload ->> 'purchase_price')::bigint, 0);
  v_sale          bigint := coalesce((p_payload ->> 'sale_price')::bigint, 0);
  v_wholesale     bigint := coalesce((p_payload ->> 'wholesale_price')::bigint, 0);
  v_min_stock     int := coalesce((p_payload ->> 'min_stock')::int, 0);
  v_initial_stock int := coalesce((p_payload ->> 'initial_stock')::int, 0);
  v_is_public     boolean := coalesce((p_payload ->> 'is_public')::boolean, false);
  v_attributes    jsonb := coalesce(p_payload -> 'attributes', '{}'::jsonb);
  v_existing      public.products%rowtype;
  v_created       boolean := false;
begin
  -- ۱) دسترسی
  if not public.is_store_staff(v_store) then
    raise exception 'FORBIDDEN: کاربر عضو این فروشگاه نیست.';
  end if;
  if not public.has_permission('product.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت محصولات وجود ندارد.';
  end if;

  -- ۲) اعتبارسنجی
  if v_name = '' then
    raise exception 'VALIDATION: نام محصول الزامی است.';
  end if;
  if v_sale < 0 or v_purchase < 0 or v_wholesale < 0 then
    raise exception 'VALIDATION: قیمت نمی‌تواند منفی باشد.';
  end if;
  if v_min_stock < 0 or v_initial_stock < 0 then
    raise exception 'VALIDATION: تعداد موجودی نمی‌تواند منفی باشد.';
  end if;
  if v_barcode is not null and exists (
    select 1 from public.products
     where store_id = v_store and barcode = v_barcode
       and id <> v_id and deleted_at is null
  ) then
    raise exception 'DUPLICATE_BARCODE: بارکد تکراری است.';
  end if;
  if v_sku is not null and exists (
    select 1 from public.products
     where store_id = v_store and sku = v_sku
       and id <> v_id and deleted_at is null
  ) then
    raise exception 'DUPLICATE_SKU: کد کالا تکراری است.';
  end if;

  select * into v_existing
    from public.products
   where id = v_id and store_id = v_store
   for update;

  if found then
    -- ۳) ویرایش
    if v_existing.deleted_at is not null then
      raise exception 'PRODUCT_NOT_FOUND: محصول حذف شده است.';
    end if;

    if v_existing.sale_price <> v_sale or v_existing.purchase_price <> v_purchase then
      insert into public.price_history (
        store_id, product_id, old_price, new_price, changed_by
      ) values (
        v_store, v_id, v_existing.sale_price, v_sale, auth.uid()
      );
    end if;

    update public.products set
      name            = v_name,
      barcode         = v_barcode,
      sku             = v_sku,
      category_id     = nullif(p_payload ->> 'category_id', '')::uuid,
      supplier_id     = nullif(p_payload ->> 'supplier_id', '')::uuid,
      description     = nullif(trim(p_payload ->> 'description'), ''),
      brand           = nullif(trim(p_payload ->> 'brand'), ''),
      unit            = coalesce(nullif(trim(p_payload ->> 'unit'), ''), 'عدد'),
      purchase_price  = v_purchase,
      sale_price      = v_sale,
      wholesale_price = v_wholesale,
      min_stock       = v_min_stock,
      is_public       = v_is_public,
      attributes      = v_attributes,
      updated_at      = now()
    where id = v_id;
  else
    -- ۴) ایجاد
    v_created := true;
    insert into public.products (
      id, store_id, category_id, supplier_id, name, sku, barcode, description, brand, unit,
      purchase_price, sale_price, wholesale_price, min_stock, is_public, is_active, attributes
    ) values (
      v_id, v_store,
      nullif(p_payload ->> 'category_id', '')::uuid,
      nullif(p_payload ->> 'supplier_id', '')::uuid,
      v_name, v_sku, v_barcode,
      nullif(trim(p_payload ->> 'description'), ''),
      nullif(trim(p_payload ->> 'brand'), ''),
      coalesce(nullif(trim(p_payload ->> 'unit'), ''), 'عدد'),
      v_purchase, v_sale, v_wholesale, v_min_stock, v_is_public, true, v_attributes
    );

    -- موجودی اولیه فقط هنگام ایجاد و فقط از این مسیر
    if v_initial_stock > 0 then
      insert into public.inventory_transactions (
        id, store_id, product_id, qty_delta, type, reference_type, reference_id,
        unit_cost, reason, user_id, client_operation_id
      ) values (
        gen_random_uuid(), v_store, v_id, v_initial_stock, 'adjustment',
        'product', v_id, v_purchase, 'موجودی اولیه محصول', auth.uid(), gen_random_uuid()
      );
    end if;
  end if;

  return jsonb_build_object(
    'product_id', v_id,
    'created', v_created,
    'stock', (select stock_cached from public.products where id = v_id)
  );
end;
$$;

-- -------------------------------------------------------------
-- غیرفعال‌سازی (حذف منطقی) محصول
-- -------------------------------------------------------------
create or replace function public.rpc_delete_product(p_product_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_product public.products%rowtype;
begin
  select * into v_product from public.products where id = p_product_id for update;
  if not found then
    raise exception 'PRODUCT_NOT_FOUND: محصول یافت نشد.';
  end if;
  if not public.is_store_staff(v_product.store_id) then
    raise exception 'FORBIDDEN: کاربر عضو این فروشگاه نیست.';
  end if;
  if not public.has_permission('product.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت محصولات وجود ندارد.';
  end if;

  if v_product.deleted_at is not null then
    return jsonb_build_object('product_id', p_product_id, 'duplicate', true);
  end if;

  update public.products
     set is_active = false, is_public = false, deleted_at = now(), updated_at = now()
   where id = p_product_id;

  return jsonb_build_object('product_id', p_product_id, 'duplicate', false);
end;
$$;
