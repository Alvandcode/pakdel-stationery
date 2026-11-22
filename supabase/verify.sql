-- =============================================================
-- Pakdel Stationery — بررسی سلامت نصب
-- پس از اجرای مهاجرت‌های ۰۱ تا ۱۰ این فایل را در SQL Editor اجرا کنید.
-- هر ردیف باید status = 'OK' باشد.
-- =============================================================

with expected_tables(name, needs_rls) as (
  values
    ('stores', true), ('branches', true), ('roles', true),
    ('permissions', true), ('role_permissions', true), ('store_members', true),
    ('categories', true), ('suppliers', true), ('products', true),
    ('product_variants', true), ('product_images', true), ('price_history', true),
    ('customers', true), ('addresses', true), ('sales', true), ('sale_items', true),
    ('purchases', true), ('purchase_items', true), ('inventory_transactions', true),
    ('stocktakes', true), ('stocktake_items', true), ('payments', true),
    ('expenses', true), ('orders', true), ('order_items', true),
    ('order_status_history', true), ('coupons', true), ('coupon_redemptions', true),
    ('campaigns', true), ('campaign_products', true), ('loyalty_accounts', true),
    ('loyalty_transactions', true), ('school_lists', true), ('school_list_items', true),
    ('notifications', true), ('app_settings', true), ('audit_logs', true),
    ('store_counters', true), ('role_permission_defaults', false)
),
expected_functions(name) as (
  values
    ('auth_store_id'), ('auth_role_code'), ('auth_is_staff'), ('auth_customer_id'),
    ('has_permission'), ('is_store_staff'),
    ('rpc_my_context'), ('rpc_checkout_sale'), ('rpc_cancel_sale'),
    ('rpc_adjust_stock'), ('rpc_place_order'), ('rpc_set_order_status'),
    ('rpc_validate_coupon'), ('rpc_loyalty_award'), ('rpc_dashboard_summary'),
    ('rpc_record_payment'), ('rpc_upsert_product'), ('rpc_delete_product'),
    ('rpc_upsert_customer'), ('rpc_list_members'), ('rpc_set_member_role'),
    ('rpc_set_member_active'), ('rpc_invite_member'), ('fn_bootstrap_store'),
    ('fn_sale_require_customer'),
    ('rpc_create_store'), ('rpc_join_store'), ('rpc_store_invite_code'),
    ('rpc_rotate_invite_code'), ('fn_generate_invite_code'),
    ('rpc_publish_all_products')
)
select 'جدول: ' || e.name as check_name,
       case when t.tablename is null then 'MISSING' else 'OK' end as status
  from expected_tables e
  left join pg_tables t
    on t.schemaname = 'public' and t.tablename = e.name

union all

select 'تابع: ' || e.name,
       case when p.proname is null then 'MISSING' else 'OK' end
  from expected_functions e
  left join pg_proc p
    on p.proname = e.name
   and p.pronamespace = 'public'::regnamespace

union all

select 'RLS فعال روی جداول تجاری',
       case when count(*) = 0 then 'OK' else 'MISSING روی ' || count(*) || ' جدول' end
  from expected_tables e
  join pg_tables t on t.schemaname = 'public' and t.tablename = e.name
 where e.needs_rls and t.rowsecurity = false

union all

select 'تریگر فروش نسیه',
       case when count(*) = 1 then 'OK' else 'MISSING' end
  from pg_trigger
 where tgname = 'trg_sale_require_customer' and not tgisinternal

union all

select 'مجوزهای پیش‌فرض نقش‌ها',
       case when count(*) >= 4 then 'OK' else 'MISSING' end
  from public.role_permission_defaults

order by status desc, check_name;
