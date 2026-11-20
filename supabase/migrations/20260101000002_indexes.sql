-- =============================================================
-- Pakdel Stationery — 02 Indexes
-- =============================================================

-- سازمان
create index if not exists ix_branches_store on public.branches(store_id);
create index if not exists ix_store_members_user on public.store_members(user_id);
create index if not exists ix_store_members_store on public.store_members(store_id) where is_active;

-- کاتالوگ
create index if not exists ix_categories_store on public.categories(store_id, sort_order) where deleted_at is null;
create index if not exists ix_categories_parent on public.categories(parent_id);
create unique index if not exists ux_products_sku on public.products(store_id, sku)
  where sku is not null and deleted_at is null;
create unique index if not exists ux_products_barcode on public.products(store_id, barcode)
  where barcode is not null and deleted_at is null;
create index if not exists ix_products_store_active on public.products(store_id, is_active) where deleted_at is null;
create index if not exists ix_products_category on public.products(category_id);
create index if not exists ix_products_supplier on public.products(supplier_id);
create index if not exists ix_products_name_trgm on public.products using gin (name gin_trgm_ops);
create index if not exists ix_products_low_stock on public.products(store_id) where stock_cached <= min_stock;
create unique index if not exists ux_variants_barcode on public.product_variants(product_id, barcode)
  where barcode is not null;
create index if not exists ix_variants_product on public.product_variants(product_id);
create index if not exists ix_product_images_product on public.product_images(product_id, sort_order);
create index if not exists ix_price_history_product on public.price_history(product_id, changed_at desc);

-- مشتری
create index if not exists ix_customers_store on public.customers(store_id) where deleted_at is null;
create unique index if not exists ux_customers_phone on public.customers(store_id, phone)
  where phone is not null and deleted_at is null;
create index if not exists ix_customers_user on public.customers(user_id);
create index if not exists ix_addresses_customer on public.addresses(customer_id);

-- فروش
create index if not exists ix_sales_store_date on public.sales(store_id, sold_at desc);
create index if not exists ix_sales_customer on public.sales(customer_id, sold_at desc);
create index if not exists ix_sales_cashier on public.sales(cashier_id, sold_at desc);
create index if not exists ix_sales_status on public.sales(store_id, status) where status = 'completed';
create index if not exists ix_sale_items_sale on public.sale_items(sale_id);
create index if not exists ix_sale_items_product on public.sale_items(product_id);

-- خرید
create index if not exists ix_purchases_store on public.purchases(store_id, purchased_at desc);
create index if not exists ix_purchases_supplier on public.purchases(supplier_id, purchased_at desc);
create index if not exists ix_purchase_items_purchase on public.purchase_items(purchase_id);

-- انبار
create index if not exists ix_inv_tx_product on public.inventory_transactions(product_id, created_at desc);
create index if not exists ix_inv_tx_store_date on public.inventory_transactions(store_id, created_at desc);
create index if not exists ix_inv_tx_reference on public.inventory_transactions(reference_type, reference_id);
create index if not exists ix_stocktake_items_stocktake on public.stocktake_items(stocktake_id);

-- مالی
create index if not exists ix_payments_store_date on public.payments(store_id, paid_at desc);
create index if not exists ix_payments_party on public.payments(party_type, party_id);
create index if not exists ix_expenses_store_date on public.expenses(store_id, paid_at desc);

-- سفارش آنلاین
create index if not exists ix_orders_store_date on public.orders(store_id, placed_at desc);
create index if not exists ix_orders_customer on public.orders(customer_id, placed_at desc);
create index if not exists ix_orders_status on public.orders(store_id, status);
create index if not exists ix_order_items_order on public.order_items(order_id);
create index if not exists ix_order_status_history_order on public.order_status_history(order_id, created_at);

-- بازاریابی
create index if not exists ix_coupons_store_code on public.coupons(store_id, code) where is_active;
create index if not exists ix_coupon_redemptions_coupon on public.coupon_redemptions(coupon_id);
create index if not exists ix_loyalty_tx_account on public.loyalty_transactions(account_id, created_at desc);
create index if not exists ix_campaigns_store on public.campaigns(store_id, starts_at, ends_at);

-- اطلاع‌رسانی و حسابرسی
create index if not exists ix_notifications_user on public.notifications(user_id, is_read, created_at desc);
create index if not exists ix_notifications_store on public.notifications(store_id, created_at desc);
create index if not exists ix_audit_logs_store on public.audit_logs(store_id, created_at desc);
create index if not exists ix_audit_logs_entity on public.audit_logs(entity, entity_id);
create index if not exists ix_app_settings_store on public.app_settings(store_id);
