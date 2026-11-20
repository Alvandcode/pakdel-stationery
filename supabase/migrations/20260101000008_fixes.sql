-- =============================================================
-- Pakdel Stationery — 08 Fixes
-- اصلاحات امنیتی و تجاری پس از بازبینی:
--   ۱) rpc_my_context: ورود کارکنان بدون نیاز به claim در app_metadata
--   ۲) rpc_upsert_customer: ثبت/ویرایش مشتری به‌صورت اتمیک
--   ۳) محافظت فروش نسیه بدون مشتری (تریگر)
--   ۴) محدود‌کردن لغو فاکتور به فروشگاه خودی
--   ۵) مدیریت کارکنان: فهرست و تغییر نقش/فعال‌سازی
-- =============================================================

-- -------------------------------------------------------------
-- ۰) مرجع فروشگاه/نقش: عضویت فعال جدول store_members بر claim مقدم است
-- (claim قدیمی می‌توانست باعث نوشتن در فروشگاه اشتباه شود)
-- -------------------------------------------------------------
create or replace function public.auth_store_id()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    -- ابتدا عضویتی که با claim هم‌خوان است
    (select sm.store_id
       from public.store_members sm
      where sm.user_id = auth.uid() and sm.is_active
        and sm.store_id = nullif(auth.jwt() -> 'app_metadata' ->> 'store_id', '')::uuid
      limit 1),
    -- سپس اولین عضویت فعال
    (select sm.store_id
       from public.store_members sm
      where sm.user_id = auth.uid() and sm.is_active
      order by sm.created_at
      limit 1),
    -- در نهایت claim (برای کاربرانی که هنوز عضو ثبت نشده‌اند)
    nullif(auth.jwt() -> 'app_metadata' ->> 'store_id', '')::uuid
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
    (select r.code::text
       from public.store_members sm
       join public.roles r on r.id = sm.role_id
      where sm.user_id = auth.uid() and sm.is_active
        and sm.store_id = public.auth_store_id()
      limit 1),
    nullif(auth.jwt() -> 'app_metadata' ->> 'role', '')
  );
$$;

-- -------------------------------------------------------------
-- ۱) زمینه‌ی کاربر: فروشگاه، نقش و نام فروشگاه
-- Security definer تا RLS جدول store_members مانع نشود.
-- -------------------------------------------------------------
create or replace function public.rpc_my_context()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid   uuid := auth.uid();
  v_claim uuid := nullif(auth.jwt() -> 'app_metadata' ->> 'store_id', '')::uuid;
  v_m     public.store_members%rowtype;
  v_role  text;
  v_store text;
  v_name  text;
  v_email text;
begin
  if v_uid is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;

  select * into v_m
    from public.store_members
   where user_id = v_uid and is_active
   -- اگر کاربر عضو چند فروشگاه است، همان فروشگاهی که در claim نشست آمده مقدم است
   -- تا با auth_store_id() (که نوشتن‌ها را به آن فروشگاه می‌برد) هم‌خوان بماند
   order by (store_id = v_claim) desc, created_at asc
   limit 1;

  if not found then
    raise exception 'FORBIDDEN: حساب شما به هیچ فروشگاهی متصل نیست. با مدیر سیستم تماس بگیرید.';
  end if;

  select code into v_role from public.roles where id = v_m.role_id;
  select name into v_store from public.stores where id = v_m.store_id;

  select coalesce(nullif(raw_user_meta_data ->> 'full_name', ''), email), email
    into v_name, v_email
    from auth.users where id = v_uid;

  return jsonb_build_object(
    'user_id', v_uid,
    'store_id', v_m.store_id,
    'role', coalesce(v_role, 'cashier'),
    'store_name', coalesce(v_store, ''),
    'display_name', coalesce(nullif(v_m.full_name, ''), nullif(v_name, ''), v_email, ''),
    'email', coalesce(v_email, '')
  );
end;
$$;

revoke all on function public.rpc_my_context() from public;
grant execute on function public.rpc_my_context() to authenticated;

-- -------------------------------------------------------------
-- ۲) ثبت/ویرایش مشتری (آفلاین‌اول و تکرارناپذیر)
-- -------------------------------------------------------------
create or replace function public.rpc_upsert_customer(p_payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid := public.auth_store_id();
  v_id    uuid := coalesce(nullif(p_payload ->> 'id', '')::uuid, gen_random_uuid());
  v_name  text := btrim(coalesce(p_payload ->> 'full_name', ''));
  v_phone text := nullif(btrim(coalesce(p_payload ->> 'phone', '')), '');
  v_limit bigint := coalesce((p_payload ->> 'credit_limit')::bigint, 0);
  v_owner public.customers%rowtype;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('customer.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت مشتری وجود ندارد.';
  end if;
  if v_name = '' then
    raise exception 'VALIDATION: نام مشتری الزامی است.';
  end if;
  if v_limit < 0 then
    raise exception 'VALIDATION: سقف اعتبار نمی‌تواند منفی باشد.';
  end if;

  select * into v_owner from public.customers where id = v_id;
  if found and v_owner.store_id <> v_store then
    raise exception 'FORBIDDEN: این مشتری به فروشگاه دیگری تعلق دارد.';
  end if;

  if v_phone is not null and exists (
    select 1 from public.customers
     where store_id = v_store and phone = v_phone and deleted_at is null and id <> v_id
  ) then
    raise exception 'DUPLICATE_PHONE: مشتری دیگری با این شماره تلفن ثبت شده است.';
  end if;

  insert into public.customers (
    id, store_id, full_name, phone, group_code, discount_percent,
    credit_limit, balance_cached, is_active
  ) values (
    v_id, v_store, v_name, v_phone,
    coalesce(nullif(p_payload ->> 'group_code', ''), 'regular'),
    coalesce((p_payload ->> 'discount_percent')::numeric, 0),
    v_limit, 0, true
  )
  on conflict (id) do update set
    full_name        = excluded.full_name,
    phone            = excluded.phone,
    group_code       = excluded.group_code,
    discount_percent = excluded.discount_percent,
    credit_limit     = excluded.credit_limit,
    is_active        = true,
    updated_at       = now();

  select * into v_owner from public.customers where id = v_id;

  return jsonb_build_object(
    'customer_id', v_id,
    'balance', v_owner.balance_cached,
    'duplicate', false
  );
end;
$$;

revoke all on function public.rpc_upsert_customer(jsonb) from public;
grant execute on function public.rpc_upsert_customer(jsonb) to authenticated;

-- -------------------------------------------------------------
-- ۳) فروش نسیه هرگز بدون مشتری ثبت نمی‌شود
-- -------------------------------------------------------------
create or replace function public.fn_sale_require_customer()
returns trigger
language plpgsql
as $$
begin
  if new.status = 'completed' and new.due_total > 0 and new.customer_id is null then
    raise exception 'VALIDATION: فروش نسیه بدون انتخاب مشتری مجاز نیست.';
  end if;
  return new;
end;
$$;

drop trigger if exists trg_sale_require_customer on public.sales;
create trigger trg_sale_require_customer
before insert on public.sales
for each row execute function public.fn_sale_require_customer();

-- -------------------------------------------------------------
-- ۴) لغو فاکتور فقط در محدوده‌ی فروشگاه کاربر
-- -------------------------------------------------------------
create or replace function public.rpc_cancel_sale(p_sale_id uuid, p_reason text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid := public.auth_store_id();
  v_sale  public.sales%rowtype;
  v_item  public.sale_items%rowtype;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('sale.cancel') then
    raise exception 'FORBIDDEN: دسترسی لغو فاکتور وجود ندارد.';
  end if;

  select * into v_sale
    from public.sales
   where id = p_sale_id and store_id = v_store
   for update;
  if not found then
    raise exception 'NOT_FOUND: فاکتور یافت نشد.';
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
     where id = v_sale.customer_id and store_id = v_store;
  end if;

  return jsonb_build_object('sale_id', p_sale_id, 'status', 'canceled');
end;
$$;

-- -------------------------------------------------------------
-- ۵) مدیریت کارکنان (ساخت کاربر Auth در داشبورد؛ عضویت با rpc_invite_member)
-- -------------------------------------------------------------
create or replace function public.rpc_list_members()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid := public.auth_store_id();
  v_rows  jsonb := '[]'::jsonb;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('staff.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت کارکنان وجود ندارد.';
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
           'id', m.id,
           'user_id', m.user_id,
           'full_name', coalesce(nullif(m.full_name, ''), u.raw_user_meta_data ->> 'full_name', u.email, m.user_id::text),
           'email', coalesce(u.email, ''),
           'role', r.code,
           'role_title', r.title,
           'is_active', m.is_active,
           'is_self', m.user_id = auth.uid()
         ) order by m.created_at asc), '[]'::jsonb)
    into v_rows
    from public.store_members m
    join public.roles r on r.id = m.role_id
    left join auth.users u on u.id = m.user_id
   where m.store_id = v_store;

  return v_rows;
end;
$$;

create or replace function public.rpc_set_member_role(p_member_id uuid, p_role_code text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store       uuid := public.auth_store_id();
  v_caller_role text := public.auth_role_code();
  v_member      public.store_members%rowtype;
  v_role        public.roles%rowtype;
  v_current     text;
  v_owners      int;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('staff.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت کارکنان وجود ندارد.';
  end if;

  select * into v_member from public.store_members
   where id = p_member_id and store_id = v_store for update;
  if not found then
    raise exception 'NOT_FOUND: کارمند یافت نشد.';
  end if;

  select code::text into v_current from public.roles where id = v_member.role_id;

  -- فقط مالک می‌تواند نقش‌های مدیریتی/مالکیت را تغییر دهد
  if v_caller_role <> 'owner'
     and (p_role_code in ('owner', 'manager') or v_current in ('owner', 'manager')) then
    raise exception 'FORBIDDEN: تغییر نقش مدیریتی فقط توسط مالک فروشگاه مجاز است.';
  end if;

  select * into v_role from public.roles
   where store_id = v_store and code = p_role_code::user_role_code;
  if not found then
    raise exception 'VALIDATION: نقش نامعتبر است.';
  end if;

  if v_member.role_id = v_role.id then
    return jsonb_build_object('member_id', p_member_id, 'role', p_role_code);
  end if;

  -- جلوگیری از حذف آخرین مالک فروشگاه
  if p_role_code <> 'owner' then
    select count(*) into v_owners
      from public.store_members m
      join public.roles r on r.id = m.role_id
     where m.store_id = v_store and m.is_active and r.code = 'owner';
    if v_owners <= 1 and v_member.user_id = auth.uid() then
      raise exception 'VALIDATION: فروشگاه باید حداقل یک مالک فعال داشته باشد.';
    end if;
  end if;

  update public.store_members set role_id = v_role.id where id = p_member_id;
  return jsonb_build_object('member_id', p_member_id, 'role', p_role_code);
end;
$$;

create or replace function public.rpc_set_member_active(p_member_id uuid, p_active boolean)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store       uuid := public.auth_store_id();
  v_caller_role text := public.auth_role_code();
  v_member      public.store_members%rowtype;
  v_current     text;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('staff.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت کارکنان وجود ندارد.';
  end if;

  select * into v_member from public.store_members
   where id = p_member_id and store_id = v_store for update;
  if not found then
    raise exception 'NOT_FOUND: کارمند یافت نشد.';
  end if;
  if v_member.user_id = auth.uid() and not p_active then
    raise exception 'VALIDATION: نمی‌توانید حساب خودتان را غیرفعال کنید.';
  end if;

  select code::text into v_current from public.roles where id = v_member.role_id;
  if v_caller_role <> 'owner' and v_current in ('owner', 'manager') then
    raise exception 'FORBIDDEN: تغییر وضعیت مدیران فقط توسط مالک مجاز است.';
  end if;

  update public.store_members set is_active = p_active where id = p_member_id;
  return jsonb_build_object('member_id', p_member_id, 'is_active', p_active);
end;
$$;

revoke all on function public.rpc_list_members() from public;
revoke all on function public.rpc_set_member_role(uuid, text) from public;
revoke all on function public.rpc_set_member_active(uuid, boolean) from public;
grant execute on function public.rpc_list_members() to authenticated;
grant execute on function public.rpc_set_member_role(uuid, text) to authenticated;
grant execute on function public.rpc_set_member_active(uuid, boolean) to authenticated;

-- -------------------------------------------------------------
-- ۶) محدود‌کردن کاتالوگ عمومی برای کارکنان
-- کارکنان باید فقط کالا/دسته فروشگاه خود را ببینند (staff_read)؛
-- سیاست عمومی برای اپ مشتریان (کاربران بدون عضویت فروشگاه) است.
--
-- نکته: زیر‌پرس‌وجو روی store_members خودش تحت RLS است و برای صندوق‌دار
-- خالی برمی‌گشت؛ بنابراین از تابع security definer استفاده می‌شود.
-- -------------------------------------------------------------
create or replace function public.auth_is_staff()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.store_members sm
     where sm.user_id = auth.uid() and sm.is_active
  );
$$;

grant execute on function public.auth_is_staff() to anon, authenticated;

drop policy if exists public_catalog on public.products;
create policy public_catalog on public.products for select to anon, authenticated
  using (
    is_public and is_active and deleted_at is null
    and not public.auth_is_staff()
  );

drop policy if exists public_categories on public.categories;
create policy public_categories on public.categories for select to anon, authenticated
  using (
    is_active and deleted_at is null
    and not public.auth_is_staff()
  );

-- -------------------------------------------------------------
-- ۷) ایندکس جست‌وجوی سریع مشتری بر اساس تلفن در فروشگاه
-- -------------------------------------------------------------
create index if not exists ix_customers_store_phone
  on public.customers (store_id, phone)
  where deleted_at is null;

-- -------------------------------------------------------------
-- ۸) افزودن کارمند موجود به فروشگاه
-- ساخت کاربر Auth بیرون از اپ انجام می‌شود (Supabase Dashboard →
-- Authentication → Add user / Invite). این تابع فقط عضویت و نقش را
-- برای همان ایمیل ثبت می‌کند؛ کلید service_role هرگز در اپ نیست.
-- -------------------------------------------------------------
create or replace function public.rpc_invite_member(
  p_email      text,
  p_role_code  text,
  p_full_name  text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store    uuid := public.auth_store_id();
  v_caller   text := public.auth_role_code();
  v_email    text := lower(btrim(coalesce(p_email, '')));
  v_user     uuid;
  v_role     public.roles%rowtype;
  v_member   public.store_members%rowtype;
  v_existing uuid;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('staff.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت کارکنان وجود ندارد.';
  end if;
  if v_email = '' then
    raise exception 'VALIDATION: ایمیل کارمند الزامی است.';
  end if;

  select * into v_role from public.roles
   where store_id = v_store and code::text = p_role_code;
  if not found then
    raise exception 'VALIDATION: نقش نامعتبر است.';
  end if;
  if v_caller <> 'owner' and p_role_code in ('owner', 'manager') then
    raise exception 'FORBIDDEN: افزودن مدیر/مالک فقط توسط مالک مجاز است.';
  end if;

  select id into v_user from auth.users where lower(email) = v_email limit 1;
  if v_user is null then
    raise exception 'USER_NOT_FOUND: کاربری با این ایمیل در Auth وجود ندارد؛ ابتدا در داشبورد Supabase ساخته/دعوت شود.';
  end if;

  select id into v_existing from public.store_members
   where store_id = v_store and user_id = v_user
   limit 1;

  if v_existing is not null then
    update public.store_members
       set is_active = true, role_id = v_role.id,
           full_name = coalesce(nullif(btrim(coalesce(p_full_name, '')), ''), full_name)
     where id = v_existing
     returning * into v_member;
  else
    insert into public.store_members (id, store_id, user_id, role_id, full_name)
    values (gen_random_uuid(), v_store, v_user, v_role.id, nullif(btrim(coalesce(p_full_name, '')), ''))
    returning * into v_member;
  end if;

  return jsonb_build_object(
    'id', v_member.id,
    'user_id', v_member.user_id,
    'full_name', coalesce(nullif(v_member.full_name, ''), v_email),
    'email', v_email,
    'role', v_role.code::text,
    'is_active', v_member.is_active,
    'created_at', v_member.created_at
  );
end;
$$;

revoke all on function public.rpc_invite_member(text, text, text) from public;
grant execute on function public.rpc_invite_member(text, text, text) to authenticated;
