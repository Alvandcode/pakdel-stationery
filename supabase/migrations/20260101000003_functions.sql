-- =============================================================
-- Pakdel Stationery — 03 Helper functions & triggers
-- =============================================================

-- ---------- شمارنده‌های اتمیک (شماره فاکتور و ...) ----------
create table if not exists public.store_counters (
  store_id uuid not null references public.stores(id) on delete cascade,
  name     text not null,
  value    bigint not null default 0,
  primary key (store_id, name)
);

create or replace function public.fn_next_counter(p_store_id uuid, p_name text)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
  v bigint;
begin
  insert into public.store_counters (store_id, name, value)
  values (p_store_id, p_name, 1)
  on conflict (store_id, name)
    do update set value = public.store_counters.value + 1
  returning value into v;
  return v;
end;
$$;

create or replace function public.fn_next_invoice_number(p_store_id uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v bigint := public.fn_next_counter(p_store_id, 'invoice');
begin
  return 'INV-' || to_char(now() at time zone 'Asia/Tehran', 'YYYY') || '-' || lpad(v::text, 6, '0');
end;
$$;

create or replace function public.fn_next_order_number(p_store_id uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v bigint := public.fn_next_counter(p_store_id, 'order');
begin
  return 'PK-' || to_char(now() at time zone 'Asia/Tehran', 'YYYYMMDD') || '-' || lpad(v::text, 4, '0');
end;
$$;

-- ---------- استخراج هویت از JWT ----------
create or replace function public.auth_store_id()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    nullif(auth.jwt() -> 'app_metadata' ->> 'store_id', '')::uuid,
    (select sm.store_id
       from public.store_members sm
      where sm.user_id = auth.uid() and sm.is_active
      limit 1)
  );
$$;

create or replace function public.auth_role_code()
returns text
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    nullif(auth.jwt() -> 'app_metadata' ->> 'role', ''),
    (select r.code::text
       from public.store_members sm
       join public.roles r on r.id = sm.role_id
      where sm.user_id = auth.uid() and sm.is_active
      limit 1)
  );
$$;

create or replace function public.has_permission(p_code text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
      from public.store_members sm
      join public.role_permissions rp on rp.role_id = sm.role_id
      join public.permissions p on p.id = rp.permission_id
     where sm.user_id = auth.uid()
       and sm.store_id = public.auth_store_id()
       and sm.is_active
       and p.code = p_code
  );
$$;

create or replace function public.is_store_staff(p_store_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.store_members sm
     where sm.store_id = p_store_id and sm.user_id = auth.uid() and sm.is_active
  );
$$;

-- ---------- به‌روزرسانی updated_at ----------
create or replace function public.fn_touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

do $$
declare t text;
begin
  foreach t in array array[
    'stores','categories','suppliers','products','product_variants',
    'customers','sales','orders','loyalty_accounts','app_settings'
  ] loop
    execute format('drop trigger if exists trg_touch_%1$s on public.%1$I', t);
    execute format(
      'create trigger trg_touch_%1$s before update on public.%1$I
       for each row execute function public.fn_touch_updated_at()', t);
  end loop;
end $$;

-- ---------- محاسبه مجدد موجودی از گردش انبار ----------
create or replace function public.fn_recalc_stock()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_product uuid;
  v_variant uuid;
begin
  v_product := coalesce(new.product_id, old.product_id);
  v_variant := coalesce(new.variant_id, old.variant_id);

  update public.products p
     set stock_cached = coalesce((
           select sum(qty_delta) from public.inventory_transactions
            where product_id = v_product), 0),
         updated_at = now()
   where p.id = v_product;

  if v_variant is not null then
    update public.product_variants pv
       set stock_cached = coalesce((
             select sum(qty_delta) from public.inventory_transactions
              where variant_id = v_variant), 0),
           updated_at = now()
     where pv.id = v_variant;
  end if;

  return null;
end;
$$;

drop trigger if exists trg_inv_recalc on public.inventory_transactions;
create trigger trg_inv_recalc
after insert on public.inventory_transactions
for each row execute function public.fn_recalc_stock();

-- گردش انبار تغییرناپذیر است (اصلاح فقط با رکورد معکوس)
create or replace function public.fn_inv_immutable()
returns trigger
language plpgsql
as $$
begin
  raise exception 'INVENTORY_IMMUTABLE: گردش انبار قابل ویرایش یا حذف نیست؛ رکورد معکوس ثبت کنید.';
end;
$$;

drop trigger if exists trg_inv_immutable on public.inventory_transactions;
create trigger trg_inv_immutable
before update or delete on public.inventory_transactions
for each row execute function public.fn_inv_immutable();

-- ---------- فاکتور تغییرناپذیر پس از نهایی شدن ----------
create or replace function public.fn_sale_guard()
returns trigger
language plpgsql
as $$
begin
  if old.status = 'canceled' then
    raise exception 'SALE_LOCKED: فاکتور باطل‌شده قابل تغییر نیست.';
  end if;
  if old.invoice_number <> new.invoice_number
     or old.grand_total <> new.grand_total
     or old.store_id <> new.store_id then
    raise exception 'SALE_IMMUTABLE: شماره، مبلغ و فروشگاه فاکتور قابل تغییر نیست.';
  end if;
  return new;
end;
$$;

drop trigger if exists trg_sale_guard on public.sales;
create trigger trg_sale_guard
before update on public.sales
for each row execute function public.fn_sale_guard();

-- ---------- حسابرسی عمومی ----------
create or replace function public.fn_audit_row()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid;
  v_before jsonb;
  v_after jsonb;
  v_entity_id uuid;
begin
  if tg_op = 'DELETE' then
    v_before := to_jsonb(old);
    v_after := null;
    v_entity_id := old.id;
  elsif tg_op = 'UPDATE' then
    v_before := to_jsonb(old);
    v_after := to_jsonb(new);
    v_entity_id := new.id;
  else
    v_before := null;
    v_after := to_jsonb(new);
    v_entity_id := new.id;
  end if;

  v_store := coalesce(
    nullif(v_after ->> 'store_id', '')::uuid,
    nullif(v_before ->> 'store_id', '')::uuid,
    public.auth_store_id()
  );

  insert into public.audit_logs (store_id, user_id, action, entity, entity_id, before, after)
  values (v_store, auth.uid(), lower(tg_op), tg_table_name, v_entity_id, v_before, v_after);

  return null;
end;
$$;

do $$
declare t text;
begin
  foreach t in array array[
    'products','categories','customers','sales','orders','payments',
    'expenses','coupons','store_members','purchases'
  ] loop
    execute format('drop trigger if exists trg_audit_%1$s on public.%1$I', t);
    execute format(
      'create trigger trg_audit_%1$s after insert or update or delete on public.%1$I
       for each row execute function public.fn_audit_row()', t);
  end loop;
end $$;

-- ---------- گذارهای مجاز وضعیت سفارش ----------
create or replace function public.fn_order_transition_allowed(p_from order_status, p_to order_status)
returns boolean
language sql
immutable
as $$
  select case
    when p_from = p_to then false
    when p_from = 'pending'   then p_to in ('approved','canceled')
    when p_from = 'approved'  then p_to in ('preparing','canceled')
    when p_from = 'preparing' then p_to in ('ready','canceled')
    when p_from = 'ready'     then p_to in ('shipped','delivered','canceled')
    when p_from = 'shipped'   then p_to in ('delivered','returned')
    when p_from = 'delivered' then p_to in ('returned')
    else false
  end;
$$;
