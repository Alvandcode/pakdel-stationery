-- =============================================================
-- Pakdel Stationery — 05 Row Level Security
-- اصل: پیش‌فرض «هیچ دسترسی» است؛ دسترسی فقط با سیاست صریح.
-- =============================================================

-- شناسه مشتری متصل (برای اپ فروشگاه)
create or replace function public.auth_customer_id()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select c.id from public.customers c
   where c.user_id = auth.uid() and c.is_active and c.deleted_at is null
   limit 1;
$$;

-- ---------- فعال‌سازی RLS روی همه جداول ----------
do $$
declare t text;
begin
  foreach t in array array[
    'stores','branches','roles','permissions','role_permissions','store_members',
    'categories','suppliers','products','product_variants','product_images','price_history',
    'customers','addresses','sales','sale_items','purchases','purchase_items',
    'inventory_transactions','stocktakes','stocktake_items','payments','expenses',
    'orders','order_items','order_status_history','coupons','coupon_redemptions',
    'campaigns','campaign_products','loyalty_accounts','loyalty_transactions',
    'school_lists','school_list_items','notifications','app_settings','audit_logs',
    'store_counters'
  ] loop
    execute format('alter table public.%I enable row level security', t);
  end loop;
end $$;

-- ---------- جداول فقط-خواندنی برای کارکنان ----------
-- نوشتن در این جداول صرفاً از طریق توابع امن (security definer) انجام می‌شود.
do $$
declare r record;
begin
  for r in
    select * from (values
      ('sales',
       'public.is_store_staff(store_id)'),
      ('sale_items',
       'exists (select 1 from public.sales s where s.id = sale_items.sale_id and public.is_store_staff(s.store_id))'),
      ('inventory_transactions',
       'public.is_store_staff(store_id)'),
      ('payments',
       'public.is_store_staff(store_id)'),
      ('price_history',
       'public.is_store_staff(store_id)'),
      ('order_status_history',
       'exists (select 1 from public.orders o where o.id = order_status_history.order_id and public.is_store_staff(o.store_id))'),
      ('coupon_redemptions',
       'exists (select 1 from public.coupons c where c.id = coupon_redemptions.coupon_id and public.is_store_staff(c.store_id))'),
      ('loyalty_transactions',
       'exists (select 1 from public.loyalty_accounts la where la.id = loyalty_transactions.account_id and public.is_store_staff(la.store_id))'),
      ('audit_logs',
       'public.is_store_staff(store_id) and public.has_permission(''audit.view'')')
    ) as t(tbl, expr)
  loop
    execute format('drop policy if exists staff_read on public.%I', r.tbl);
    execute format(
      'create policy staff_read on public.%I for select to authenticated using (%s)',
      r.tbl, r.expr);
  end loop;
end $$;

-- ---------- جداول مدیریتی (کارکنان: خواندن/نوشتن) ----------
do $$
declare r record;
begin
  for r in
    select * from (values
      ('products',
       'public.is_store_staff(store_id)'),
      ('product_variants',
       'exists (select 1 from public.products p where p.id = product_variants.product_id and public.is_store_staff(p.store_id))'),
      ('product_images',
       'exists (select 1 from public.products p where p.id = product_images.product_id and public.is_store_staff(p.store_id))'),
      ('categories',
       'public.is_store_staff(store_id)'),
      ('suppliers',
       'public.is_store_staff(store_id)'),
      ('customers',
       'public.is_store_staff(store_id)'),
      ('addresses',
       'exists (select 1 from public.customers c where c.id = addresses.customer_id and public.is_store_staff(c.store_id))'),
      ('stocktakes',
       'public.is_store_staff(store_id)'),
      ('stocktake_items',
       'exists (select 1 from public.stocktakes st where st.id = stocktake_items.stocktake_id and public.is_store_staff(st.store_id))'),
      ('campaigns',
       'public.is_store_staff(store_id)'),
      ('campaign_products',
       'exists (select 1 from public.campaigns cp where cp.id = campaign_products.campaign_id and public.is_store_staff(cp.store_id))'),
      ('school_lists',
       'public.is_store_staff(store_id)'),
      ('school_list_items',
       'exists (select 1 from public.school_lists sl where sl.id = school_list_items.list_id and public.is_store_staff(sl.store_id))'),
      ('notifications',
       'public.is_store_staff(store_id)'),
      ('branches',
       'public.is_store_staff(store_id)')
    ) as t(tbl, expr)
  loop
    execute format('drop policy if exists staff_all on public.%I', r.tbl);
    execute format(
      'create policy staff_all on public.%I for all to authenticated using (%s) with check (%s)',
      r.tbl, r.expr, r.expr);
  end loop;
end $$;

-- ---------- خرید و اقلام خرید: فقط با مجوز خرید ----------
drop policy if exists purchases_manage on public.purchases;
create policy purchases_manage on public.purchases for all to authenticated
  using (public.is_store_staff(store_id) and public.has_permission('purchase.manage'))
  with check (public.is_store_staff(store_id) and public.has_permission('purchase.manage'));

drop policy if exists purchase_items_manage on public.purchase_items;
create policy purchase_items_manage on public.purchase_items for all to authenticated
  using (exists (select 1 from public.purchases pu where pu.id = purchase_items.purchase_id
                  and public.is_store_staff(pu.store_id) and public.has_permission('purchase.manage')))
  with check (exists (select 1 from public.purchases pu where pu.id = purchase_items.purchase_id
                  and public.is_store_staff(pu.store_id) and public.has_permission('purchase.manage')));

-- ---------- کارکنان، نقش‌ها و تنظیمات: نیازمند مجوز مدیریت ----------
drop policy if exists staff_members on public.store_members;
create policy staff_members on public.store_members for all to authenticated
  using (public.is_store_staff(store_id) and public.has_permission('staff.manage'))
  with check (public.is_store_staff(store_id) and public.has_permission('staff.manage'));

drop policy if exists roles_manage on public.roles;
create policy roles_manage on public.roles for all to authenticated
  using (public.is_store_staff(store_id) and public.has_permission('staff.manage'))
  with check (public.is_store_staff(store_id) and public.has_permission('staff.manage'));

drop policy if exists roles_read on public.roles;
create policy roles_read on public.roles for select to authenticated
  using (public.is_store_staff(store_id));

drop policy if exists settings_manage on public.app_settings;
create policy settings_manage on public.app_settings for all to authenticated
  using (public.is_store_staff(store_id) and public.has_permission('settings.finance'))
  with check (public.is_store_staff(store_id) and public.has_permission('settings.finance'));

-- ---------- فروشگاه ----------
drop policy if exists stores_read on public.stores;
create policy stores_read on public.stores for select to authenticated
  using (public.is_store_staff(id));

drop policy if exists stores_owner_update on public.stores;
create policy stores_owner_update on public.stores for update to authenticated
  using (public.is_store_staff(id) and public.has_permission('settings.finance'))
  with check (public.is_store_staff(id));

-- ---------- دسترسی‌ها ----------
drop policy if exists permissions_read on public.permissions;
create policy permissions_read on public.permissions for select to authenticated using (true);

drop policy if exists role_permissions_read on public.role_permissions;
create policy role_permissions_read on public.role_permissions for select to authenticated
  using (exists (select 1 from public.roles r where r.id = role_permissions.role_id
                  and public.is_store_staff(r.store_id)));

drop policy if exists role_permissions_manage on public.role_permissions;
create policy role_permissions_manage on public.role_permissions for all to authenticated
  using (exists (select 1 from public.roles r where r.id = role_permissions.role_id
                  and public.is_store_staff(r.store_id) and public.has_permission('staff.manage')))
  with check (exists (select 1 from public.roles r where r.id = role_permissions.role_id
                  and public.is_store_staff(r.store_id) and public.has_permission('staff.manage')));

-- ---------- نمایش عمومی کاتالوگ (اپ مشتریان) ----------
drop policy if exists public_catalog on public.products;
create policy public_catalog on public.products for select to anon, authenticated
  using (is_public and is_active and deleted_at is null);

drop policy if exists public_categories on public.categories;
create policy public_categories on public.categories for select to anon, authenticated
  using (is_active and deleted_at is null);

drop policy if exists public_variants on public.product_variants;
create policy public_variants on public.product_variants for select to anon, authenticated
  using (is_active and exists (
    select 1 from public.products p where p.id = product_variants.product_id
      and p.is_public and p.is_active and p.deleted_at is null));

drop policy if exists public_images on public.product_images;
create policy public_images on public.product_images for select to anon, authenticated
  using (exists (
    select 1 from public.products p where p.id = product_images.product_id
      and p.is_public and p.is_active and p.deleted_at is null));

drop policy if exists public_campaigns on public.campaigns;
create policy public_campaigns on public.campaigns for select to anon, authenticated
  using (is_active and (ends_at is null or ends_at > now()));

drop policy if exists public_school_lists on public.school_lists;
create policy public_school_lists on public.school_lists for select to anon, authenticated
  using (is_active);

drop policy if exists public_school_items on public.school_list_items;
create policy public_school_items on public.school_list_items for select to anon, authenticated
  using (exists (select 1 from public.school_lists sl where sl.id = school_list_items.list_id
                  and sl.is_active));

-- ---------- مشتری: دسترسی به پرونده و سفارش‌های خود ----------
drop policy if exists customer_self_read on public.customers;
create policy customer_self_read on public.customers for select to authenticated
  using (user_id = auth.uid());

drop policy if exists customer_self_update on public.customers;
create policy customer_self_update on public.customers for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists customer_addresses on public.addresses;
create policy customer_addresses on public.addresses for all to authenticated
  using (customer_id = public.auth_customer_id())
  with check (customer_id = public.auth_customer_id());

drop policy if exists customer_orders on public.orders;
create policy customer_orders on public.orders for select to authenticated
  using (customer_id = public.auth_customer_id());

drop policy if exists customer_order_items on public.order_items;
create policy customer_order_items on public.order_items for select to authenticated
  using (exists (select 1 from public.orders o where o.id = order_items.order_id
                  and o.customer_id = public.auth_customer_id()));

drop policy if exists customer_loyalty on public.loyalty_accounts;
create policy customer_loyalty on public.loyalty_accounts for select to authenticated
  using (customer_id = public.auth_customer_id());

drop policy if exists staff_orders on public.orders;
create policy staff_orders on public.orders for select to authenticated
  using (public.is_store_staff(store_id));

drop policy if exists staff_order_items on public.order_items;
create policy staff_order_items on public.order_items for select to authenticated
  using (exists (select 1 from public.orders o where o.id = order_items.order_id
                  and public.is_store_staff(o.store_id)));

drop policy if exists staff_loyalty on public.loyalty_accounts;
create policy staff_loyalty on public.loyalty_accounts for all to authenticated
  using (public.is_store_staff(store_id)) with check (public.is_store_staff(store_id));

drop policy if exists customer_notifications on public.notifications;
create policy customer_notifications on public.notifications for select to authenticated
  using (user_id = auth.uid());

drop policy if exists customer_notifications_update on public.notifications;
create policy customer_notifications_update on public.notifications for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ---------- مالی: نیازمند مجوز گزارش ----------
drop policy if exists finance_expenses on public.expenses;
create policy finance_expenses on public.expenses for select to authenticated
  using (public.is_store_staff(store_id) and public.has_permission('report.view'));

drop policy if exists finance_purchases_read on public.purchases;
create policy finance_purchases_read on public.purchases for select to authenticated
  using (public.is_store_staff(store_id) and public.has_permission('purchase.view'));

-- store_counters: هیچ سیاستی ندارد ⇒ دسترسی مستقیم ممنوع (فقط توابع امن)
