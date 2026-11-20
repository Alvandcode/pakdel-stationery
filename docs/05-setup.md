# ۵. پیش‌نیازها و راه‌اندازی

## ۵.۱ نرم‌افزارهای لازم

| ابزار | نسخه | توضیح |
|-------|------|-------|
| Android Studio | Ladybug 2024.2.1 یا جدیدتر | **اختیاری** — اگر ندارید، مسیر GitHub Actions → `docs/06-github-build.md` |
| JDK | 17 | در Android Studio: `Settings → Build → Gradle → Gradle JDK` |
| Android SDK | API 35 | از SDK Manager نصب شود |
| Git | 2.40+ | |
| حساب Supabase | رایگان | https://supabase.com |
| Node.js (اختیاری) | 18+ | برای Supabase CLI |

## ۵.۲ ایجاد پروژه Supabase (گام‌به‌گام)

1. وارد داشبورد Supabase شوید و **New Project** بزنید.
   - نام: `pakdel-stationery`، منطقه: نزدیک‌ترین (مثلاً `eu-central-1`).
   - رمز دیتابیس را در جای bezpiek نگه دارید.
2. از `Project Settings → API` این دو مقدار را بردارید:
   - `Project URL` → `SUPABASE_URL`
   - `anon public` → `SUPABASE_ANON_KEY`
   > کلید `service_role` را **هرگز** در اپ قرار ندهید.
3. یکی از دو روش برای اجرای مهاجرت‌ها:

   **روش الف — اسکریپت خودکار (پیشنهادی):**

   ```bash
   # یک Personal Access Token از Account → Access Tokens بسازید
   # https://supabase.com/dashboard/account/tokens
   cd supabase
   # فایل .env.local را بسازید (در Git نیست):
   #   SUPABASE_ACCESS_TOKEN=sbp_...
   #   SUPABASE_PROJECT_REF=abcdefghijklm
   node deploy.mjs --dry-run   # نمایش فهرست
   node deploy.mjs             # اجرا به ترتیب
   ```

   **روش ب — SQL Editor:** فایل‌های زیر را **به همین ترتیب** اجرا کنید:
   1. `supabase/migrations/20260101000001_init.sql`
   2. `20260101000002_indexes.sql`
   3. `20260101000003_functions.sql`
   4. `20260101000004_rpc.sql`
   5. `20260101000005_rls.sql`
   6. `20260101000006_seed.sql`
   7. `20260101000007_product_rpc.sql`
   8. `20260101000008_fixes.sql`  ← الزامی: ورود کارکنان، ثبت مشتری، مدیریت کارکنان و امنیت کاتالوگ
4. در `Authentication → Providers` گزینه Email را فعال و «Confirm email» را برای شروع غیرفعال کنید.
5. در `Authentication → Users` یک کاربر مدیر بسازید؛ سپس در SQL Editor:

```sql
-- شناسه کاربر را از لیست Users بردارید
select public.fn_bootstrap_store(
  'فروشگاه پاکدل',                    -- نام فروشگاه
  'اصلی',                              -- نام شعبه
  'a1b2c3d4-0000-0000-0000-000000000000'::uuid  -- user_id مدیر
);
```

> از مهاجرت `20260101000008_fixes.sql` به بعد، تعیین فروشگاه و نقش کاربر از جدول
> `store_members` خوانده می‌شود (`rpc_my_context`) و نیازی به قرار دادن claim در
> `app_metadata` نیست. اگر مهاجرت ۸ را اجرا نکرده‌اید، نقش و فروشگاه از claim خوانده
> می‌شود و کارکنان بدون claim نمی‌توانند وارد شوند.

این تابع فروشگاه، شعبه، نقش‌های پیش‌فرض و عضویت مالک را می‌سازد و Claimها را تنظیم می‌کند.

6. بررسی سلامت نصب:

   فایل `supabase/verify.sql` را در SQL Editor اجرا کنید. همه ردیف‌ها باید `OK` باشند
   (وجود ۳۹ جدول، ۲۵ تابع کلیدی، RLS و تریگر فروش نسیه).

### افزودن کارمند

1. در `Authentication → Users` کاربر را بسازید یا دعوت کنید (با ایمیل).
2. در اپ مدیر: `تنظیمات → کارکنان → افزودن کارمند` و ایمیل/نقش را وارد کنید
   (یا در SQL: `select public.rpc_invite_member('staff@example.com','sales','نام');`).

> ساخت کاربر Auth از داخل اپ نیازمند کلید `service_role` است و طبق قاعده پروژه،
> هرگز در اپ قرار نمی‌گیرد.

## ۵.۳ تنظیم پروژه اندروید

1. فایل `android/local.properties` بسازید (این فایل در Git نیست).
   یک نمونه آماده در `android/local.properties.example` هست؛ آن را کپی کنید:

```properties
sdk.dir=C\:\\Users\\<USER>\\AppData\\Local\\Android\\Sdk
SUPABASE_URL=https://xxxxxxxxxxxx.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOi....
```

2. پوشه `android/` را در Android Studio باز کنید (نه ریشه مخزن).
3. صبر کنید Gradle Sync کامل شود.
4. اجرا: `Run ▶ app-manager`.

> اگر فایل `gradle-wrapper.jar` موجود نیست، یک‌بار در ترمینال ریشه `android/` دستور
> `gradle wrapper --gradle-version 8.9` را اجرا کنید (یا در Android Studio از
> `Settings → Build → Gradle` گزینه «Use Gradle from: gradle-wrapper.properties» را انتخاب کنید).

### ساخت از خط فرمان

```bash
cd android
./gradlew :app-manager:assembleDebug      # APK دیباگ
./gradlew :app-shop:assembleDebug
./gradlew test                             # تست‌های واحد
```

## ۵.۴ ساخت نسخه انتشار (AAB)

```bash
keytool -genkey -v -keystore pakdel-release.keystore \
  -alias pakdel -keyalg RSA -keysize 2048 -validity 10000
```

سپس در `android/keystore.properties` (در Git نیست):

```properties
storeFile=pakdel-release.keystore
storePassword=***
keyAlias=pakdel
keyPassword=***
```

و `./gradlew :app-manager:bundleRelease`.

## ۵.۵ رفع مشکلات رایج

| مشکل | راه‌حل |
|------|--------|
| `SUPABASE_URL is missing` | `local.properties` را پر کنید و Sync بزنید |
| خطای ۴۰۱ در ورود | کاربر ساخته شده؟ Confirm email غیرفعال است؟ |
| لیست محصولات خالی | RLS: کاربر عضو `store_members` است؟ |
| همگام‌سازی گیر کرده | `adb shell am force-stop` + اجرای مجدد؛ صف Outbox پاک نمی‌شود |
| `Unresolved reference: compose` | JDK پروژه باید 17 باشد و نسخه Kotlin با Compose Compiler هم‌خوان |
