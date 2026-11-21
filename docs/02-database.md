# ۲. طراحی پایگاه داده (PostgreSQL / Supabase)

## ۲.۱ نمودار روابط (ERD فشرده)

```
stores 1─< branches
stores 1─< store_members >─ auth.users
roles 1─< role_permissions >─ permissions

categories 1─< categories (parent_id, self-referencing)
categories 1─< products >─ suppliers
products 1─< product_variants
products 1─< product_images
products 1─< price_history
products 1─< inventory_transactions >─ variants/users
products 1─< school_list_items >─ school_lists

customers 1─< addresses
customers 1─< orders 1─< order_items
customers 1─< sales   1─< sale_items
customers 1─< loyalty_accounts 1─< loyalty_transactions

suppliers 1─< purchases 1─< purchase_items
purchases 1─< payments
sales     1─< payments

orders 1─< order_status_history
coupons 1─< coupon_redemptions >─ orders/sales
campaigns 1─< campaign_products

stocktakes 1─< stocktake_items
audit_logs, notifications, app_settings, sync_outbox(server-side view)
```

## ۲.۲ اصول طراحی

| اصل | پیاده‌سازی |
|-----|-----------|
| شناسه یکتا در دستگاه | تمام جداول تجاری `uuid PRIMARY KEY` بدون `default`؛ مقدار از کلاینت (آفلاین‌سازگار) |
| حذف منطقی | `deleted_at timestamptz` + ویوهای `v_*` برای فیلتر |
| گردش مالی تغییرناپذیر | `inventory_transactions` و `loyalty_transactions` فقط INSERT؛ اصلاح با رکورد معکوس |
| شماره فاکتور | `sales.invoice_number` یکتا در هر فروشگاه، تولید با `fn_next_invoice_no()` و `SELECT ... FOR UPDATE` |
| تکرارناپذیری | `UNIQUE (store_id, client_operation_id)` روی `sales`, `orders`, `payments`, `inventory_transactions` |
| مبالغ | همه `bigint` تومان: `subtotal`, `discount_total`, `tax_total`, `shipping_total`, `grand_total`, `paid_total`, `due_total` |
| بهای تمام‌شده | `sale_items.unit_cost` در لحظه فروش قفل می‌شود (COGS تاریخی قابل تغییر نیست) |
| کنترل دسترسی | RLS روی همه جداول + `role_permissions` برای دسترسی ریزدانه |
| حسابرسی | تریگر `fn_audit_row()` روی جداول حساس |

## ۲.۳ جداول اصلی و ستون‌های کلیدی

### هویت و سازمان
- `stores(id, name, legal_name, phone, currency, timezone, is_active, created_at)`
- `branches(id, store_id, name, address, phone, is_active)`
- `roles(id, store_id, code, title, is_system)`
  `code ∈ {owner, manager, sales, cashier, warehouse}`
- `permissions(id, code, title)` — `code` مثل `sale.create`, `sale.cancel`, `report.profit`, `finance.settings`
- `role_permissions(role_id, permission_id)`
- `store_members(id, store_id, user_id, branch_id, role_id, is_active)` — یکتا روی `(store_id, user_id)`

### کاتالوگ
- `categories(id, store_id, parent_id, name, slug, sort_order, is_active, deleted_at)`
- `suppliers(id, store_id, name, contact_name, phone, email, address, note, balance_cached, is_active)`
- `products(id, store_id, category_id, supplier_id, name, sku, barcode, description, brand, unit,
  purchase_price, sale_price, wholesale_price, min_stock, stock_cached, attributes jsonb,
  is_public, is_active, deleted_at, created_at, updated_at)`
  > `stock_cached` فقط برای نمایش سریع است؛ مرجع حقیقی، مجموع `inventory_transactions.qty_delta` است.
  > تریگر `fn_recalc_stock()` آن را پس از هر گردش انبار به‌روز می‌کند.
- `product_variants(id, product_id, sku, barcode, color, size, model, extra_price,
  purchase_price, sale_price, stock_cached, is_active)` — موجودی مستقل برای هر رنگ/سایز/مدل
- `product_images(id, product_id, storage_path, sort_order)`
- `price_history(id, product_id, variant_id, old_price, new_price, changed_by, changed_at)`

### فروش و انبار
- `sales(id, store_id, branch_id, customer_id, cashier_id, invoice_number, sale_type[sale|return],
  status[completed|canceled], subtotal, item_discount_total, invoice_discount, discount_total,
  tax_total, grand_total, paid_total, due_total, payment_method[cash|pos|bank|mixed|credit],
  note, client_operation_id, sold_at, created_at, canceled_at)`
- `sale_items(id, sale_id, product_id, variant_id, name_snapshot, qty, unit_price, unit_cost,
  discount, line_total)`
- `inventory_transactions(id, store_id, branch_id, product_id, variant_id, qty_delta,
  type[purchase|sale|return_sale|return_purchase|adjustment|damage|stocktake],
  reference_type, reference_id, unit_cost, reason, user_id, client_operation_id, created_at)`
- `purchases(id, store_id, supplier_id, invoice_number, status, subtotal, discount_total, grand_total, paid_total, due_total, purchased_at, client_operation_id)`
- `purchase_items(id, purchase_id, product_id, variant_id, qty, unit_cost, line_total)`
- `stocktakes(id, store_id, branch_id, status[draft|applied], counted_at, applied_by, note)`
- `stocktake_items(id, stocktake_id, product_id, variant_id, system_qty, counted_qty, diff_qty)`
- `payments(id, store_id, direction[in|out], party_type[sale|purchase|order|expense|customer|supplier],
  party_id, method, amount, reference, paid_at, user_id, client_operation_id)`
- `expenses(id, store_id, category, title, amount, paid_at, user_id, note)`

### فروش آنلاین
- `customers(id, store_id, user_id, full_name, phone, email, group_code, discount_percent,
  credit_limit, balance_cached, is_active, created_at)`
- `addresses(id, customer_id, title, province, city, postal_code, line1, receiver_name, receiver_phone, is_default)`
- `orders(id, store_id, branch_id, customer_id, order_number, status[pending|approved|preparing|
  ready|shipped|delivered|canceled|returned], fulfillment[pickup|delivery], address_id,
  subtotal, discount_total, shipping_total, grand_total, coupon_id, note, client_operation_id,
  placed_at, updated_at)`
- `order_items(id, order_id, product_id, variant_id, name_snapshot, qty, unit_price, discount, line_total)`
- `order_status_history(id, order_id, from_status, to_status, changed_by, note, created_at)`

### بازاریابی و باشگاه مشتریان
- `coupons(id, store_id, code, type[percent|amount|bogo], value, min_subtotal, max_discount,
  usage_limit, used_count, per_customer_limit, starts_at, ends_at, is_active)`
- `coupon_redemptions(id, coupon_id, customer_id, order_id, sale_id, amount, redeemed_at)`
- `campaigns(id, store_id, title, type, starts_at, ends_at, is_active, config jsonb)`
- `campaign_products(campaign_id, product_id, discount_percent)`
- `loyalty_accounts(id, store_id, customer_id UNIQUE, points, tier, lifetime_points)`
- `loyalty_transactions(id, account_id, points_delta, reason, reference_id, created_at)`
- `school_lists(id, store_id, grade_code, title, sort_order, is_active)`
- `school_list_items(id, list_id, product_id, default_qty, sort_order)`
- `notifications(id, store_id, user_id, title, body, type, is_read, created_at, data jsonb)`
- `app_settings(id, store_id, key, value jsonb, updated_at)`
- `audit_logs(id, store_id, user_id, action, entity, entity_id, before jsonb, after jsonb, ip, created_at)`

## ۲.۴ محاسبه سود

```
COGS        = Σ sale_items.qty * sale_items.unit_cost      (در لحظه فروش)
درآمد خالص  = Σ sales.grand_total + Σ sales.tax_total       (فروش منهای مرجوعی)
سود ناخالص  = درآمد خالص − COGS
سود خالص    ≈ سود ناخالص − Σ expenses − Σ تخفیف کل − هزینه ارسال
```
مرجوعی با `sale_type = 'return'` و مبلغ منفی ثبت می‌شود تا جمع‌ها بدون استثنا درست بمانند.

## ۲.۵ فایل‌های Migration

| فایل | محتوا |
|------|--------|
| `20260101000001_init.sql` | Extensionها، انواع Enum، جداول و کلیدهای خارجی |
| `20260101000002_indexes.sql` | ایندکس‌های کارایی و یکتایی |
| `20260101000003_functions.sql` | تریگرها و توابع کمکی (موجودی، شماره فاکتور، حسابرسی) |
| `20260101000004_rpc.sql` | توابع تجاری اتمیک (checkout، order، payment، stock، coupon) |
| `20260101000005_rls.sql` | فعال‌سازی RLS و سیاست‌ها |
| `20260101000006_seed.sql` | نقش‌ها، دسترسی‌ها، دسته‌بندی‌های اولیه |
| `20260101000007_product_rpc.sql` | ثبت/ویرایش و غیرفعال‌سازی اتمیک محصول + تاریخچه قیمت |
| `20260101000008_fixes.sql` | `rpc_my_context`، `rpc_upsert_customer`، مدیریت کارکنان، محافظت نسیه بدون مشتری و محدود‌سازی لغو فاکتور به فروشگاه خودی |
| `20260101000009_onboarding.sql` | کد دعوت فروشگاه (`stores.invite_code`)، `rpc_create_store`، `rpc_join_store`، `rpc_store_invite_code`، `rpc_rotate_invite_code` |

اجرا در Supabase SQL Editor یا با CLI:

```bash
supabase link --project-ref <PROJECT_REF>
supabase db push
```
