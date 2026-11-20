# ۱. معماری نهایی نرم‌افزار

## ۱.۱ نمای کلان

```
┌────────────────────────┐        ┌────────────────────────┐
│  Pakdel Manager (APK)  │        │   Pakdel Shop (APK)    │
│  Compose + MVVM        │        │   Compose + MVVM       │
│  Room (offline-first)  │        │   Room (cache)         │
└───────────┬────────────┘        └───────────┬────────────┘
            │  HTTPS / JWT (anon key + RLS)   │
            └───────────────┬─────────────────┘
                            ▼
                 ┌──────────────────────┐
                 │   Supabase Platform  │
                 │  Auth  │  Storage    │
                 │  PostgREST  │  Realtime
                 │  Edge Functions (اختیاری)
                 └──────────┬───────────┘
                            ▼
                 ┌──────────────────────┐
                 │ PostgreSQL + RLS     │
                 │ توابع تجاری اتمیک    │
                 │ AuditLogs            │
                 └──────────────────────┘
```

**اصل طلایی:** اپلیکیشن‌ها «مشتری احمق» (thin client) هستند. هر عدد مالی، هر کسر موجودی و هر
محاسبه تخفیف که به پول یا انبار مربوط است، در سرور با تراکنش اتمیک محاسبه و ثبت می‌شود.
اپ فقط نمایش می‌دهد و ورودی جمع می‌کند.

## ۱.۲ لایه‌بندی (Clean Architecture)

هر اپلیکیشن از سه لایه تشکیل می‌شود:

| لایه | مسئولیت | ماژول |
|------|---------|-------|
| Presentation | Compose Screens + ViewModel (StateFlow) | `app-manager`, `app-shop` |
| Domain | مدل‌های دامنه، UseCaseها، قراردادهای Repository | داخل `app-*` (فعلاً) |
| Data | Room، Supabase، Mapper، Sync Engine | `core:data` |

قواعد وابستگی:

```
app-manager ─┬─> core:designsystem ──> core:common
             └─> core:data ──────────> core:common
app-shop    ─┴─ (همان)
```

هیچ ماژول `core:*` به `app-*` وابسته نیست. `core:data` هیچ کلاس Compose ندارد.

## ۱.۳ معماری آفلاین (Offline-first + Outbox)

الگوی انتخابی: **Local-first با Outbox و Idempotency**.

```
[کاربر] → [ViewModel] → [Repository] → [Room: نوشتن + ثبت Outbox] → UI فوراً به‌روز می‌شود
                                                    │
                                            [SyncWorker (WorkManager)]
                                                    │  اجرای RPC سرور به ترتیب
                                                    ▼
                                   success → Outbox پاک می‌شود / وضعیت synced
                                   fail(4xx) → conflict → نمایش به مدیر
                                   fail(5xx/شبکه) → backoff و تلاش مجدد
```

قوانین:

1. **شناسه یکتا:** هر رکورد قابل همگام‌سازی یک `id` از نوع UUID تولیدشده در دستگاه دارد
   (نه `autoincrement` سرور). بنابراین همگام‌سازی idempotent است.
2. **کلید تکرارناپذیری:** هر عملیات یک `client_operation_id` دارد و در سرور ایندکس یکتا دارد.
   ارسال دوباره همان عملیات، خطای «قبلاً ثبت شده» برمی‌گرداند و نتیجه قبلی بازخوانی می‌شود.
3. **ترتیب:** Outbox با `sequence` افزایشی و FIFO پردازش می‌شود؛ عملیات یک موجودیت پشت سر هم می‌رود.
4. **حذف فیزیکی ممنوع:** جداول تجاری دارای `deleted_at` و `is_active` هستند.
5. **تعارض:** اگر سرور موجودی کافی نداشت (`INSUFFICIENT_STOCK`)، فاکتور آفلاین باطل نمی‌شود؛
   وضعیت آن `CONFLICT` شده و در «مرکز تعارض‌ها» برای مدیر نمایش داده می‌شود.
6. **Pull:** پس از Push، دلتای داده با `updated_at > last_pull_at` دریافت و در Room ادغام می‌شود.
   رکوردهای دارای تغییر محلی `pending` هرگز با نسخه سرور بازنویسی نمی‌شوند.

## ۱.۴ ماژول‌های اندروید

| ماژول | محتوا |
|-------|--------|
| `core:common` | `Money`، `PersianNumber`، `JalaliDate`، `Result`/`AppError`، `DispatcherProvider`، `Uuid` |
| `core:designsystem` | `PakdelTheme`، رنگ‌های برند، تایپوگرافی وزیرمتن، `PakdelButton`، `PakdelTextField`، `EmptyState`، `ErrorState`، `LoadingState`، `ConfirmDialog`، `MoneyText`، `JalaliDateText` |
| `core:data` | Room (Entities/DAOs/Database)، Supabase Client، DTO، Mapper، Repositoryها، `SyncEngine`، `SyncWorker`، `SessionManager` |
| `app-manager` | Navigation، Auth، Dashboard، Products، POS (فاز ۲)، Settings |
| `app-shop` | Navigation، Home، Catalog، Product Detail، Cart، Orders، Profile |

## ۱.۵ تصمیمات معماری ثبت‌شده (ADR)

### ADR-001: Supabase به‌جای بک‌اند اختصاصی
- **دلیل:** احراز هویت، Postgres، Storage، RLS و Realtime آماده؛ کاهش زمان تا محصول واقعی.
- **هزینه:** منطق تجاری باید در SQL/PLpgSQL یا Edge Functions نوشته شود.
- **جبران:** تمام قواعد مالی داخل توابع `rpc_*` در `supabase/migrations` متمرکز است.

### ADR-002: اعداد مالی به‌صورت `bigint` تومان
- **دلیل:** حذف کامل خطای اعشار `double`؛ تومان واحد رسمی کسب‌وکار است.
- **پیامد:** تخفیف درصدی در سرور با `round()` صریح محاسبه می‌شود؛ در کد Kotlin از `Long` استفاده می‌شود.

### ADR-003: Outbox در Room به‌جای صف در حافظه
- **دلیل:** بسته شدن اپ یا قطع برق، عملیات فروش را از بین نمی‌برد.

### ADR-004: Room به‌عنوان منبع حقیقت برای UI
- **دلیل:** UI همیشه یک منبع دارد (Flow از Room)؛ رفتار آفلاین و آنلاین یکسان است.

### ADR-005: RLS + Claims نقش
- **دلیل:** حتی اگر کلاینت دستکاری شود، سرور اجازه دسترسی به داده غیرمجاز را نمی‌دهد.
- **پیاده‌سازی:** `app_metadata.store_id` و `app_metadata.role` در JWT؛ توابع `auth_store_id()` و
  `auth_role()` این مقادیر را می‌خوانند. دسترسی دقیق‌تر از جدول `role_permissions` خوانده می‌شود.

### ADR-006: چند فروشگاه/چند شعبه از روز اول
- همه جداول تجاری `store_id` و در صورت نیاز `branch_id` دارند؛ هیچ کوئری «تک‌فروشگاهی» سخت‌کد نشده است.

## ۱.۶ مسیر رشد آینده

- افزودن `feature-manager-pos`، `feature-manager-inventory` … به‌صورت ماژول Gradle مستقل.
- افزودن زبان دوم: تمام رشته‌ها در `strings.xml` و فونت‌ها در DesignSystem؛ بدون تغییر در UI.
- افزودن Edge Function برای درگاه پرداخت (زرین‌پال/آیدی‌پی) — کلید در Secret سرور، نه در اپ.
