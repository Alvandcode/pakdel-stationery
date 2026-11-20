-- =============================================================
-- Pakdel Stationery — 06 Seed: permissions, roles, categories
-- =============================================================

-- ---------- دسترسی‌ها ----------
insert into public.permissions (id, code, title) values
  (gen_random_uuid(), 'sale.create',          'صدور فاکتور فروش'),
  (gen_random_uuid(), 'sale.cancel',          'لغو فاکتور فروش'),
  (gen_random_uuid(), 'sale.price_override',  'تغییر دستی قیمت در فروش'),
  (gen_random_uuid(), 'inventory.view',       'مشاهده موجودی و گردش انبار'),
  (gen_random_uuid(), 'inventory.adjust',     'ورود/خروج و اصلاح موجودی'),
  (gen_random_uuid(), 'inventory.stocktake',  'انبارگردانی'),
  (gen_random_uuid(), 'product.manage',       'مدیریت محصولات و دسته‌بندی'),
  (gen_random_uuid(), 'supplier.manage',      'مدیریت تأمین‌کنندگان'),
  (gen_random_uuid(), 'purchase.view',        'مشاهده خریدها'),
  (gen_random_uuid(), 'purchase.manage',      'ثبت و تأیید خرید'),
  (gen_random_uuid(), 'customer.manage',      'مدیریت مشتریان'),
  (gen_random_uuid(), 'order.manage',         'مدیریت سفارش‌های آنلاین'),
  (gen_random_uuid(), 'report.view',          'مشاهده گزارش‌های فروش'),
  (gen_random_uuid(), 'report.profit',        'مشاهده سود و اطلاعات محرمانه'),
  (gen_random_uuid(), 'finance.receive',      'ثبت دریافت وجه'),
  (gen_random_uuid(), 'finance.pay',          'ثبت پرداخت'),
  (gen_random_uuid(), 'finance.expense',      'ثبت هزینه'),
  (gen_random_uuid(), 'finance.settings',     'تغییر تنظیمات مالی'),
  (gen_random_uuid(), 'staff.manage',         'مدیریت کارکنان و نقش‌ها'),
  (gen_random_uuid(), 'campaign.manage',      'مدیریت کمپین و کوپن'),
  (gen_random_uuid(), 'audit.view',           'مشاهده تاریخچه فعالیت‌ها'),
  (gen_random_uuid(), 'settings.finance',     'تنظیمات فروشگاه')
on conflict (code) do nothing;

-- ---------- نگاشت پیش‌فرض نقش ← دسترسی ----------
create table if not exists public.role_permission_defaults (
  role_code       user_role_code not null,
  permission_code text not null,
  primary key (role_code, permission_code)
);

insert into public.role_permission_defaults (role_code, permission_code)
select v.role_code, v.permission_code
from (values
  -- مالک: همه دسترسی‌ها
  ('owner'::user_role_code, 'sale.create'),
  ('owner', 'sale.cancel'), ('owner','sale.price_override'),
  ('owner','inventory.view'), ('owner','inventory.adjust'), ('owner','inventory.stocktake'),
  ('owner','product.manage'), ('owner','supplier.manage'),
  ('owner','purchase.view'), ('owner','purchase.manage'),
  ('owner','customer.manage'), ('owner','order.manage'),
  ('owner','report.view'), ('owner','report.profit'),
  ('owner','finance.receive'), ('owner','finance.pay'), ('owner','finance.expense'),
  ('owner','finance.settings'), ('owner','staff.manage'),
  ('owner','campaign.manage'), ('owner','audit.view'), ('owner','settings.finance'),

  -- مدیر: بدون تغییر تنظیمات مالی/فروشگاه
  ('manager'::user_role_code, 'sale.create'),
  ('manager','sale.cancel'), ('manager','sale.price_override'),
  ('manager','inventory.view'), ('manager','inventory.adjust'), ('manager','inventory.stocktake'),
  ('manager','product.manage'), ('manager','supplier.manage'),
  ('manager','purchase.view'), ('manager','purchase.manage'),
  ('manager','customer.manage'), ('manager','order.manage'),
  ('manager','report.view'), ('manager','report.profit'),
  ('manager','finance.receive'), ('manager','finance.pay'), ('manager','finance.expense'),
  ('manager','staff.manage'), ('manager','campaign.manage'), ('manager','audit.view'),

  -- مسئول فروش
  ('sales'::user_role_code, 'sale.create'),
  ('sales','sale.price_override'), ('sales','inventory.view'),
  ('sales','customer.manage'), ('sales','report.view'),

  -- صندوق‌دار: بدون سود و گزارش محرمانه و بدون اصلاح موجودی
  ('cashier'::user_role_code, 'sale.create'),
  ('cashier','customer.manage'),

  -- مسئول انبار
  ('warehouse'::user_role_code, 'inventory.view'),
  ('warehouse','inventory.adjust'), ('warehouse','inventory.stocktake'),
  ('warehouse','product.manage'), ('warehouse','supplier.manage'),
  ('warehouse','purchase.view'), ('warehouse','purchase.manage')
) as v(role_code, permission_code)
on conflict do nothing;

-- ---------- ساخت فروشگاه اولیه ----------
create or replace function public.fn_bootstrap_store(
  p_store_name  text,
  p_branch_name text,
  p_owner_id    uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  v_store  uuid := gen_random_uuid();
  v_branch uuid := gen_random_uuid();
  v_role   record;
  v_cat    record;
  v_codes  user_role_code[] := array['owner','manager','sales','cashier','warehouse']::user_role_code[];
  v_titles text[] := array['مالک فروشگاه','مدیر','مسئول فروش','صندوق‌دار','مسئول انبار'];
  i        int;
begin
  if not exists (select 1 from auth.users where id = p_owner_id) then
    raise exception 'NOT_FOUND: کاربر مالک با شناسه % وجود ندارد.', p_owner_id;
  end if;

  insert into public.stores (id, name) values (v_store, p_store_name);
  insert into public.branches (id, store_id, name, is_active)
  values (v_branch, v_store, p_branch_name, true);

  for i in 1..array_length(v_codes, 1) loop
    insert into public.roles (id, store_id, code, title, is_system)
    values (gen_random_uuid(), v_store, v_codes[i], v_titles[i], true);

    insert into public.role_permissions (role_id, permission_id)
    select r.id, p.id
      from public.roles r
      join public.role_permission_defaults d on d.role_code = r.code
      join public.permissions p on p.code = d.permission_code
     where r.store_id = v_store and r.code = v_codes[i]
    on conflict do nothing;
  end loop;

  insert into public.store_members (id, store_id, user_id, branch_id, role_id, is_active)
  select gen_random_uuid(), v_store, p_owner_id, v_branch, r.id, true
    from public.roles r where r.store_id = v_store and r.code = 'owner';

  -- دسته‌بندی‌های اولیه
  for v_cat in
    select * from (values
      ('دفتر و کاغذ', 'notebook-paper', '#1565C0', 1),
      ('خودکار و مداد', 'pens-pencils', '#C62828', 2),
      ('لوازم مدرسه', 'school-supplies', '#2E7D32', 3),
      ('لوازم اداری', 'office-supplies', '#6A1B9A', 4),
      ('لوازم هنری', 'art-supplies', '#EF6C00', 5),
      ('نقاشی و طراحی', 'drawing', '#00838F', 6),
      ('کیف و جامدادی', 'bags', '#AD1457', 7),
      ('کتاب‌های آموزشی', 'books', '#4527A0', 8),
      ('لوازم فانتزی', 'fancy', '#F9A825', 9),
      ('وسایل تزئینی و هدیه', 'gifts', '#00695C', 10)
    ) as t(name, slug, color, sort_order)
  loop
    insert into public.categories (id, store_id, name, slug, color, sort_order)
    values (gen_random_uuid(), v_store, v_cat.name, v_cat.slug, v_cat.color, v_cat.sort_order);
  end loop;

  insert into public.app_settings (store_id, key, value) values
    (v_store, 'store_profile', jsonb_build_object(
      'name', p_store_name, 'phone', '', 'address', '', 'logo_path', null)),
    (v_store, 'invoice', jsonb_build_object(
      'footer', 'از خرید شما سپاسگزاریم', 'show_logo', true, 'paper_width', 58)),
    (v_store, 'loyalty', jsonb_build_object(
      'points_per_10000', 1, 'silver_threshold', 1500, 'gold_threshold', 5000)),
    (v_store, 'shipping', jsonb_build_object(
      'delivery_fee', 40000, 'free_above', 1500000, 'pickup_enabled', true));

  -- ثبت Claimهای کاربر مالک
  update auth.users
     set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb) ||
         jsonb_build_object('store_id', v_store, 'role', 'owner', 'branch_id', v_branch)
   where id = p_owner_id;

  insert into public.audit_logs (store_id, user_id, action, entity, entity_id, after)
  values (v_store, p_owner_id, 'bootstrap', 'stores', v_store,
          jsonb_build_object('name', p_store_name, 'branch', p_branch_name));

  return jsonb_build_object('store_id', v_store, 'branch_id', v_branch);
end;
$$;

revoke all on function public.fn_bootstrap_store(text, text, uuid) from public, anon, authenticated;
grant execute on function public.fn_bootstrap_store(text, text, uuid) to service_role;
