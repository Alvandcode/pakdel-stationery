# ۳. مشخصات API

## ۳.۱ روش دسترسی

| مورد | مقدار |
|------|-------|
| پایه | `https://<PROJECT_REF>.supabase.co` |
| احراز هویت | `Authorization: Bearer <access_token>` (JWT صادرشده توسط Supabase Auth) |
| کلید عمومی | `apikey: <SUPABASE_ANON_KEY>` |
| فرمت | JSON |
| PostgREST | `GET/POST/PATCH /rest/v1/<table>` |
| توابع تجاری | `POST /rest/v1/rpc/<function>` |

> همه عملیات نوشتاری مالی **فقط** از طریق `rpc_*` مجاز است. نوشتن مستقیم روی `sales`،
> `inventory_transactions` و `payments` توسط RLS مسدود شده است.

## ۳.۲ احراز هویت

| عملیات | مسیر |
|--------|------|
| ثبت‌نام | `POST /auth/v1/signup` |
| ورود | `POST /auth/v1/token?grant_type=password` |
| تازه‌سازی | `POST /auth/v1/token?grant_type=refresh_token` |
| خروج | `POST /auth/v1/logout` |
| پروفایل | `GET  /auth/v1/user` |

Claimهای سفارشی (اختیاری، برای سازگاری با نسخه‌های قبل از مهاجرت ۸):

```json
{ "store_id": "uuid", "role": "cashier", "branch_id": "uuid" }
```

> مرجع اصلی فروشگاه/نقش، جدول `store_members` است و با `rpc_my_context()` خوانده می‌شود؛
> بنابراین کارکنان دعوت‌شده نیازی به claim ندارند.

### `rpc_my_context() → jsonb`
فروشگاه، نقش، نام فروشگاه و نام نمایشی کاربر جاری را برمی‌گرداند (بدون نیاز به claim).

### `rpc_upsert_customer(p_payload jsonb) → jsonb`
ثبت/ویرایش مشتری؛ نیازمند `customer.manage`، با کنترل تلفن تکراری (`DUPLICATE_PHONE`)
و محدود به فروشگاه کاربر.

### `rpc_list_members() / rpc_set_member_role(uuid, text) / rpc_set_member_active(uuid, boolean)`
مدیریت کارکنان؛ نیازمند `staff.manage`.
- تغییر نقش به/از `owner`/`manager` و غیرفعال‌سازی مدیران فقط توسط `owner`.
- آخرین مالک فعال قابل حذف/تنزل نیست.

### `rpc_invite_member(p_email text, p_role_code text, p_full_name text default null) → jsonb`
عضویت کاربرِ **موجود در Supabase Auth** را در فروشگاه جاری ثبت/فعال می‌کند
(نیازمند `staff.manage`). اگر کاربری با آن ایمیل نباشد: `USER_NOT_FOUND`.
ساخت کاربر Auth در داشبورد انجام می‌شود تا کلید `service_role` در اپ نباشد.
پاسخ: `{ id, user_id, full_name, email, role, is_active, created_at }`.

### `rpc_create_store(p_name text, p_branch_name text default 'اصلی') → jsonb`
هر کاربر احراز هویت‌شده‌ای که عضو هیچ فروشگاهی نیست، فروشگاه خودش را می‌سازد
و **مالک** آن می‌شود (نقش‌ها، مجوزها، شعبه و دسته‌بندی‌های اولیه خودکار ساخته می‌شوند).
پاسخ: `{ store_id, store_name, invite_code, role }`.
اگر کاربر از قبل عضو فروشگاهی باشد: `FORBIDDEN`.

### `rpc_join_store(p_code text) → jsonb`
پیوستن به فروشگاه با **کد دعوت ۸ کاراکتری** (بدون نیاز به مدیر سیستم).
عضو جدید با نقش پیش‌فرض `sales` اضافه می‌شود؛ ارتقای نقش با `rpc_set_member_role`.
پاسخ: `{ store_id, store_name, role, member_id }`. کد نامعتبر: `NOT_FOUND`.

### `rpc_publish_all_products() → jsonb`
همهٔ محصولات فعال فروشگاه را در کاتالوگ عمومی (`is_public = true`) منتشر می‌کند
(نیازمند `product.manage`). پاسخ: `{ published }` و یک رکورد `audit_logs`.

### `rpc_store_invite_code() → jsonb` / `rpc_rotate_invite_code() → jsonb`
خواندن و چرخش کد دعوت فروشگاه (نیازمند `staff.manage`).
پاسخ: `{ invite_code }`. چرخش، کد قبلی را باطل می‌کند.

## ۳.۳ توابع تجاری (RPC)

### `rpc_checkout_sale(p_payload jsonb) → jsonb`
ثبت اتمیک فاکتور فروش.

```json
{
  "id": "uuid-v4",
  "store_id": "uuid",
  "branch_id": "uuid",
  "customer_id": null,
  "payment_method": "cash",
  "invoice_discount": 0,
  "note": "",
  "client_operation_id": "uuid-v4",
  "items": [
    { "product_id": "uuid", "variant_id": null, "qty": 2, "unit_price": 45000, "discount": 0 }
  ],
  "payments": [ { "method": "cash", "amount": 90000, "reference": null } ]
}
```

پاسخ موفق:
```json
{ "sale_id":"…", "invoice_number":"۱۴۰۵-۰۰۰۱۲۳", "grand_total":90000, "due_total":0,
  "duplicate": false }
```

خطاهای دامنه (با `RAISE EXCEPTION` و کد پیام):

| کد | معنا |
|----|------|
| `INSUFFICIENT_STOCK` | موجودی کافی نیست (`detail` شامل لیست اقلام) |
| `PRODUCT_NOT_FOUND` | کالا حذف/غیرفعال شده |
| `DUPLICATE_OPERATION` | عملیات قبلاً ثبت شده — اپ باید نتیجه قبلی را بخواند |
| `INVALID_DISCOUNT` | تخفیف بیش از مبلغ مجاز |
| `DUPLICATE_BARCODE` / `DUPLICATE_SKU` / `DUPLICATE_PHONE` | شناسه تکراری در همان فروشگاه |
| `CREDIT_LIMIT` | سقف اعتبار مشتری تکمیل است |
| `VALIDATION` | ورودی نامعتبر (از جمله فروش نسیه بدون مشتری) |
| `FORBIDDEN` | کاربر دسترسی لازم را ندارد |

### `rpc_cancel_sale(p_sale_id uuid, p_reason text)`
لغو فاکتور + بازگشت موجودی + ثبت گردش معکوس. نیازمند `sale.cancel`.

### `rpc_adjust_stock(p_payload jsonb)`
`{ store_id, branch_id, product_id, variant_id, qty_delta, type, reason, client_operation_id }`
برای ورود کالا، ضایعات، اصلاح موجودی و انبارگردانی.

### `rpc_place_order(p_payload jsonb) → jsonb`
ثبت سفارش آنلاین؛ قیمت و موجودی **در همین تابع** بازخوانی و قفل می‌شوند.
پاسخ: `{ order_id, order_number, grand_total, status }`.

### `rpc_set_order_status(p_order_id uuid, p_status text, p_note text)`
تغییر وضعیت با اعتبارسنجی گذارهای مجاز و ثبت در `order_status_history`.
در گذار به `approved` موجودی رزرو/کسر می‌شود.

### `rpc_validate_coupon(p_code text, p_subtotal bigint) → jsonb`
`{ valid, coupon_id, discount, message }` — فقط اعتبارسنجی؛ مصرف در checkout انجام می‌شود.

### `rpc_loyalty_award(p_customer_id uuid, p_points int, p_reason text)`
افزودن/کسر امتیاز؛ فقط از سمت سرور در پیامد سفارش تحویل‌شده.

### `rpc_dashboard_summary(p_from date, p_to date) → jsonb`
خروجی: فروش، تعداد فاکتور، سود ناخالص، بدهی مشتریان، بدهی تأمین‌کنندگان، اقلام رو به اتمام.

## ۳.۴ کوئری‌های خواندنی (PostgREST)

| داده | درخواست |
|------|---------|
| محصولات فروشگاه (هر دو اپ) | `GET /rest/v1/products?select=*,product_images(*),product_variants(*)&is_active=eq.true` |
| محصولات عمومی (Shop) | `...&is_public=eq.true&deleted_at=is.null` |
| دسته‌بندی‌ها | `GET /rest/v1/categories?select=*&order=sort_order` |
| سفارش‌های مشتری | `GET /rest/v1/orders?select=*,order_items(*)&customer_id=eq.<id>&order=placed_at.desc` |
| گردش انبار یک کالا | `GET /rest/v1/inventory_transactions?product_id=eq.<id>&order=created_at.desc` |
| گزارش فروش بازه | `GET /rest/v1/sales?select=*,sale_items(*)&sold_at=gte.<from>&sold_at=lt.<to>` |
| اعلان‌ها | `GET /rest/v1/notifications?is_read=eq.false&order=created_at.desc` |

## ۳.۵ همگام‌سازی

پیاده‌سازی فعلی `SyncEngine`:

| جهت | جزئیات |
|-----|--------|
| Push | ارسال FIFO ردیف‌های `outbox` به `rpc_*`؛ هر عملیات `client_operation_id` یکتا دارد |
| Pull محصولات | `products` + `categories` فیلترشده بر `store_id` (در اپ مدیر) |
| Pull مشتریان | `customers` فروشگاه؛ ردیف‌های دارای عملیات در صف بازنویسی نمی‌شوند |
| Pull فاکتورها | `sales` + `sale_items` ۱۲۰ روز اخیر فروشگاه |
| Pull گردش انبار | `inventory_transactions` ۶۰ روز اخیر فروشگاه (فقط نمایش/حسابرسی) |
| تعارض | خطای کسب‌وکار → `conflict` (حذف نمی‌شود)؛ خطای شبکه → `pending` و تلاش مجدد |

## ۳.۶ کدهای خطای لایه داده (Kotlin)

```kotlin
sealed interface AppError {
  data object Network
  data object Unauthorized
  data object Forbidden
  data class Validation(val field: String, val message: String)
  data class Domain(val code: String, val message: String)
  data class Conflict(val code: String, val message: String)
  data class Unknown(val throwable: Throwable)
}
```
