-- =============================================================================
--  ۰۹) راه‌اندازی خودکار فروشگاه و دعوت کارکنان
--
--  هدف: صاحب فروشگاه بدون دخالت مدیر سیستم بتواند:
--    ۱) حساب بسازد و فروشگاه خودش را ایجاد کند (مالک شود)
--    ۲) با «کد دعوت» کارمند/مدیر اضافه کند
--
--  نکات امنیتی:
--    * توابع SECURITY DEFINER هستند و فقط برای نقش authenticated قابل اجرا.
--    * هر کاربر فقط می‌تواند فروشگاه بسازد اگر عضو هیچ فروشگاهی نباشد.
--    * کد دعوت ۸ کاراکتری، یکتا و قابل چرخش (rotate) توسط مدیر است.
--    * عضو جدید با نقش پیش‌فرض «مسئول فروش» اضافه می‌شود؛ ارتقای نقش توسط مالک.
-- =============================================================================

-- ---------- ستون کد دعوت ----------
alter table public.stores
  add column if not exists invite_code text;

create unique index if not exists ux_stores_invite_code_ci
  on public.stores (upper(invite_code))
  where invite_code is not null;

-- ---------- تولید کد (بدون حروف مبهم 0/O/1/I) ----------
create or replace function public.fn_generate_invite_code()
returns text
language sql
volatile
as $$
  select string_agg(
           substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789',
                  (floor(random() * 32) + 1)::int, 1),
           ''
         )
    from generate_series(1, 8);
$$;

-- ---------- پر کردن کد برای فروشگاه‌های موجود ----------
do $$
declare
  r record;
  c text;
begin
  for r in select id from public.stores where invite_code is null loop
    loop
      c := public.fn_generate_invite_code();
      exit when not exists (select 1 from public.stores s where upper(s.invite_code) = c);
    end loop;
    update public.stores set invite_code = c where id = r.id;
  end loop;
end $$;

-- ---------- ۱) ساخت فروشگاه توسط خود کاربر (اولین ورود) ----------
create or replace function public.rpc_create_store(
  p_name        text,
  p_branch_name text default 'اصلی'
)
returns jsonb
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  v_user   uuid := auth.uid();
  v_name   text := btrim(coalesce(p_name, ''));
  v_branch text := coalesce(nullif(btrim(coalesce(p_branch_name, '')), ''), 'اصلی');
  v_code   text;
  v_res    jsonb;
  v_store  uuid;
begin
  if v_user is null then
    raise exception 'UNAUTHORIZED: ابتدا وارد شوید.';
  end if;
  if v_name = '' then
    raise exception 'VALIDATION: نام فروشگاه الزامی است.';
  end if;
  if exists (
    select 1 from public.store_members m
     where m.user_id = v_user and m.is_active
  ) then
    raise exception 'FORBIDDEN: شما از قبل عضو یک فروشگاه هستید.';
  end if;

  v_res := public.fn_bootstrap_store(v_name, v_branch, v_user);
  v_store := (v_res ->> 'store_id')::uuid;

  loop
    v_code := public.fn_generate_invite_code();
    exit when not exists (select 1 from public.stores s where upper(s.invite_code) = v_code);
  end loop;
  update public.stores
     set invite_code = v_code, updated_at = now()
   where id = v_store;

  return jsonb_build_object(
    'store_id', v_store,
    'store_name', v_name,
    'invite_code', v_code,
    'role', 'owner'
  );
end;
$$;

-- ---------- ۲) پیوستن به فروشگاه با کد دعوت ----------
create or replace function public.rpc_join_store(p_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  v_user  uuid := auth.uid();
  v_code  text := upper(btrim(coalesce(p_code, '')));
  v_store public.stores%rowtype;
  v_role  uuid;
  v_mem   public.store_members%rowtype;
  v_role_code text;
begin
  if v_user is null then
    raise exception 'UNAUTHORIZED: ابتدا وارد شوید.';
  end if;
  if v_code = '' then
    raise exception 'VALIDATION: کد دعوت را وارد کنید.';
  end if;

  select * into v_store from public.stores
   where upper(invite_code) = v_code and is_active
   limit 1;
  if not found then
    raise exception 'NOT_FOUND: کد دعوت نامعتبر است.';
  end if;

  select * into v_mem from public.store_members
   where store_id = v_store.id and user_id = v_user
   limit 1;

  if found then
    update public.store_members set is_active = true where id = v_mem.id
    returning * into v_mem;
  else
    select id into v_role from public.roles
     where store_id = v_store.id and code = 'sales'
     limit 1;
    if v_role is null then
      raise exception 'NOT_FOUND: نقش پیش‌فرض فروشگاه تعریف نشده است.';
    end if;

    insert into public.store_members (id, store_id, user_id, role_id, is_active)
    values (gen_random_uuid(), v_store.id, v_user, v_role, true)
    returning * into v_mem;
  end if;

  select r.code::text into v_role_code from public.roles r where r.id = v_mem.role_id;

  insert into public.audit_logs (store_id, user_id, action, entity, entity_id, after)
  values (v_store.id, v_user, 'join_by_invite', 'store_members', v_mem.id,
          jsonb_build_object('role', v_role_code));

  return jsonb_build_object(
    'store_id', v_store.id,
    'store_name', v_store.name,
    'role', v_role_code,
    'member_id', v_mem.id
  );
end;
$$;

-- ---------- ۳) خواندن کد دعوت (مدیر/مالک) ----------
create or replace function public.rpc_store_invite_code()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid := public.auth_store_id();
  v_code  text;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('staff.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت کارکنان وجود ندارد.';
  end if;

  select invite_code into v_code from public.stores where id = v_store;
  if v_code is null then
    loop
      v_code := public.fn_generate_invite_code();
      exit when not exists (select 1 from public.stores s where upper(s.invite_code) = v_code);
    end loop;
    update public.stores set invite_code = v_code where id = v_store;
  end if;

  return jsonb_build_object('invite_code', v_code);
end;
$$;

-- ---------- ۴) چرخش کد دعوت (باطل کردن کد قبلی) ----------
create or replace function public.rpc_rotate_invite_code()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_store uuid := public.auth_store_id();
  v_code  text;
begin
  if v_store is null then
    raise exception 'UNAUTHORIZED: نشست کاربر یافت نشد.';
  end if;
  if not public.has_permission('staff.manage') then
    raise exception 'FORBIDDEN: دسترسی مدیریت کارکنان وجود ندارد.';
  end if;

  loop
    v_code := public.fn_generate_invite_code();
    exit when not exists (select 1 from public.stores s where upper(s.invite_code) = v_code);
  end loop;

  update public.stores set invite_code = v_code, updated_at = now() where id = v_store;
  return jsonb_build_object('invite_code', v_code);
end;
$$;

-- ---------- دسترسی‌ها ----------
revoke all on function public.fn_generate_invite_code() from public, anon, authenticated;
revoke all on function public.rpc_create_store(text, text) from public, anon;
revoke all on function public.rpc_join_store(text) from public, anon;
revoke all on function public.rpc_store_invite_code() from public, anon;
revoke all on function public.rpc_rotate_invite_code() from public, anon;

grant execute on function public.rpc_create_store(text, text) to authenticated;
grant execute on function public.rpc_join_store(text) to authenticated;
grant execute on function public.rpc_store_invite_code() to authenticated;
grant execute on function public.rpc_rotate_invite_code() to authenticated;

-- ---------- به‌روزرسانی verify (بدون تغییر تعداد جدول‌ها) ----------
comment on column public.stores.invite_code is
  'کد دعوت ۸ کاراکتری برای پیوستن کارکنان به فروشگاه؛ با rpc_rotate_invite_code قابل چرخش است.';
