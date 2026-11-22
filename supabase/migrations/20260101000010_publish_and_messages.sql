-- =============================================================================
--  ۱۰) انتشار گروهی محصولات در فروشگاه آنلاین + پیام‌های راهنمای به‌روز
--
--  چرا: پیش‌فرض `products.is_public = false` است و مدیر باید تک‌تک کالاها را
--  برای نمایش در اپ مشتری روشن کند؛ نتیجه این بود که کاتالوگ اپ فروشگاه خالی
--  می‌ماند. این مهاجرت یک عملیات اتمیک «انتشار همه» اضافه می‌کند.
--
--  همچنین دو پیام قدیمی که کاربر را به «داشبورد Supabase» یا «مدیر سیستم»
--  ارجاع می‌دادند با مسیر خودخدمت (کد دعوت / ساخت فروشگاه) هم‌خوان می‌شوند.
-- =============================================================================

-- ---------- ۱) انتشار همه محصولات فعال فروشگاه ----------
create or replace function public.rpc_publish_all_products()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store   uuid := public.auth_store_id();
  v_count   int;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('product.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت محصولات وجود ندارد.';
  end if;

  update public.products
     set is_public = true,
         updated_at = now()
   where store_id = v_store
     and deleted_at is null
     and is_active
     and is_public = false;

  get diagnostics v_count = row_count;

  insert into public.audit_logs (store_id, user_id, action, entity, entity_id, after)
  values (v_store, auth.uid(), 'publish_all_products', 'products', null,
          jsonb_build_object('published', v_count));

  return jsonb_build_object('published', v_count);
end;
$$;

revoke all on function public.rpc_publish_all_products() from public, anon;
grant execute on function public.rpc_publish_all_products() to authenticated;

-- ---------- ۲) پیام راهنمای زمینه کاربر ----------
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
   order by (store_id = v_claim) desc, created_at asc
   limit 1;

  if not found then
    raise exception 'FORBIDDEN: حساب شما به هیچ فروشگاهی متصل نیست. با کد دعوت به فروشگاه بپیوندید یا فروشگاه جدید بسازید.';
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

-- ---------- ۳) پیام افزودن کارمندی که هنوز حساب ندارد ----------
create or replace function public.rpc_invite_member(
  p_email     text,
  p_role_code text,
  p_full_name text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, auth
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
    raise exception 'USER_NOT_FOUND: این ایمیل هنوز در برنامه ثبت‌نام نکرده است. کد دعوت فروشگاه را برایش بفرستید تا خودش حساب بسازد.';
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

  insert into public.audit_logs (store_id, user_id, action, entity, entity_id, after)
  values (v_store, auth.uid(), 'invite_member', 'store_members', v_member.id,
          jsonb_build_object('role', v_role.code::text, 'email', v_email));

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

-- ---------- ۴) به‌روزرسانی بررسی سلامت ----------
-- (بدون تغییر ساختار جدول‌ها؛ فقط توابع)
comment on function public.rpc_publish_all_products() is
  'انتشار همه محصولات فعال فروشگاه در کاتالوگ اپ مشتری (is_public = true)';
