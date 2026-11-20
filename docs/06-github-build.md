# ۶. ساخت APK بدون اندروید استودیو (GitHub Actions)

اگر اندروید استودیو نصب ندارید، GitHub خودش APK را می‌سازد و شما فقط دانلودش می‌کنید.
هزینه: رایگان برای مخزن عمومی (و سهمیه ماهانه برای مخزن خصوصی).

فایل گردش‌کار: `.github/workflows/android.yml`

---

## ۶.۱ یک‌بار: گذاشتن کد روی GitHub

**راه الف — GitHub Desktop (ساده‌ترین):**

1. از `desktop.github.com` نصب کنید و با حساب GitHub وارد شوید.
2. `File → Add local repository` → پوشه پروژه را انتخاب کنید.
3. اگر گفت مخزن نیست، `create a repository` را بزنید → `Create repository`.
4. دکمه **Publish repository** (مخزن می‌تواند Private باشد).

**راه ب — خط فرمان (اگر Git دارید):**

```bash
git init
git add .
git commit -m "Pakdel Stationery"
git branch -M main
git remote add origin https://github.com/<user>/<repo>.git
git push -u origin main
```

> فایل `supabase/.env.local` و `android/local.properties` در `.gitignore` هستند و
> هرگز روی GitHub نمی‌روند. توکن Supabase را هم هیچ‌وقت commit نکنید.

---

## ۶.۲ یک‌بار: دادن دو مقدار به Actions

در GitHub: مخزن → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| نام | مقدار |
|-----|--------|
| `SUPABASE_URL` | `https://lcuslymnltowcggkpdlm.supabase.co` |
| `SUPABASE_ANON_KEY` | از داشبورد: `Project Settings → API → anon public` |

این‌ها هنگام بیلد داخل اپ تزریق می‌شوند (نه داخل کد مخزن).

---

## ۶.۳ ساخت APK

- مخزن → تب **Actions** → گردش‌کار **Android** → **Run workflow** → `Run`
- یا هر `push` روی `main` خودکار اجرا می‌شود.

پس از حدود ۵ تا ۱۰ دقیقه (اجراهای بعدی با کش سریع‌تر):

1. روی اجرای سبز کلیک کنید.
2. پایین صفحه بخش **Artifacts** → **pakdel-apk** را دانلود کنید.
3. فایل zip را باز کنید؛ دو APK داخلش است:
   - `app-manager-debug.apk` → اپ مدیر/فروشنده
   - `app-shop-debug.apk` → اپ مشتری
4. APK را روی گوشی اندروید کپی و نصب کنید (اجازه «نصب از منابع نامشخص» لازم است).

> APK آزمایشی (debug) روی هر گوشی نصب می‌شود و برای تست کامل کافی است.
> برای انتشار در Google Play، نسخه `release` و کلید امضا لازم است (بخش ۶.۴).

---

## ۶.۴ نسخه امضاشده (اختیاری، برای انتشار)

1. یک کلید امضا بسازید (یک‌بار برای همیشه). اگر JDK ندارید، می‌توانید از
   `Android Studio → Build → Generate Signed Bundle/APK` استفاده کنید یا
   `keytool` را از یک JDK 17 اجرا کنید:

   ```bash
   keytool -genkey -v -keystore pakdel-release.jks -alias pakdel \
           -keyalg RSA -keysize 2048 -validity 10000
   ```

2. فایل `pakdel-release.jks` را به Base64 تبدیل کنید:

   ```bash
   # Linux/macOS
   base64 -w0 pakdel-release.jks > keystore.b64
   ```

   ```powershell
   # Windows PowerShell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("pakdel-release.jks")) | Set-Content keystore.b64
   ```

3. در GitHub چهار Secret دیگر اضافه کنید (محتوا را از `keystore.b64` کپی کنید):

   | نام | مقدار |
   |-----|--------|
   | `ANDROID_KEYSTORE_BASE64` | محتوای `keystore.b64` |
   | `ANDROID_KEYSTORE_PASSWORD` | رمز keystore |
   | `ANDROID_KEY_ALIAS` | `pakdel` |
   | `ANDROID_KEY_PASSWORD` | رمز کلید |

4. از این پس هم APK آزمایشی و هم `release` امضاشده ساخته می‌شود.

> فایل `.jks` و رمزهایش را جای امن نگه دارید؛ بدون آن‌ها نمی‌توانید نسخه بعدی را
> روی همان اپ به‌روزرسانی کنید. هرگز آن‌ها را در مخزن نگذارید.

---

## ۶.۵ انتشار نسخه در Releases

یک تگ بسازید تا GitHub خودش Release با APKها بسازد:

```bash
git tag v1.0.0
git push origin v1.0.0
```

در صفحه **Releases** مخزن، فایل‌های APK مستقیم قابل دانلود هستند.

---

## ۶.۶ اگر بیلد قرمز شد

- تب **Actions** → اجرای قرمز → مرحله خطادار → متن خطا.
- رایج‌ترین‌ها: نبود Secretهای بخش ۶.۲، یا خطای کامپایل کاتلین.
- متن خطا را بفرستید تا رفع شود؛ تغییرات کد را push کنید و دوباره اجرا می‌شود.
