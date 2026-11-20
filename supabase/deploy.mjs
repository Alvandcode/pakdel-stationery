#!/usr/bin/env node
/**
 * اجرای مهاجرت‌های SQL روی پروژه Supabase از طریق Management API
 * (بدون نیاز به psql یا Supabase CLI).
 *
 * پیش‌نیاز: یک Personal Access Token از
 *   https://supabase.com/dashboard/account/tokens
 *
 * نحوه استفاده (PowerShell):
 *   $env:SUPABASE_ACCESS_TOKEN="sbp_..."
 *   $env:SUPABASE_PROJECT_REF="abcdefghijklm"
 *   node supabase/deploy.mjs
 *
 * یا با فایل `supabase/.env.local` (در .gitignore است):
 *   SUPABASE_ACCESS_TOKEN=sbp_...
 *   SUPABASE_PROJECT_REF=abcdefghijklm
 *
 * گزینه‌ها:
 *   --dry-run     فقط فایل‌ها را نشان بده، اجرا نکن
 *   --from=N      از مهاجرت شماره N به بعد (مثلاً --from=8)
 *   --only=N      فقط مهاجرت شماره N را اجرا کن
 */

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
loadEnvFile(join(here, ".env.local"));
loadEnvFile(join(here, ".env"));

const token = process.env.SUPABASE_ACCESS_TOKEN?.trim();
let projectRef = process.env.SUPABASE_PROJECT_REF?.trim();

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
const from = numberArg("--from");
const only = numberArg("--only");

if (!dryRun && (!token || !projectRef)) {
  console.error(
    "SUPABASE_ACCESS_TOKEN و SUPABASE_PROJECT_REF لازم است.\n" +
      "آن‌ها را در فایل supabase/.env.local قرار دهید یا به‌صورت متغیر محیطی بدهید.\n" +
      "ref همان بخش بعد از /project/ در آدرس داشبورد است."
  );
  process.exit(1);
}

if (!dryRun && !/^[a-z0-9]{20}$/.test(projectRef)) {
  console.error(
    `SUPABASE_PROJECT_REF نامعتبر است: "${projectRef}"\n` +
      "باید ۲۰ کاراکتر کوچک انگلیسی/رقم باشد (مثل abcdefghijklmnopqrst).\n" +
      "دقت کنید مقدار جایگزین‌شده <ref> حذف شده باشد و فقط یک خط SUPABASE_PROJECT_REF در فایل بماند."
  );
  process.exit(1);
}

const dir = join(here, "migrations");
const files = readdirSync(dir)
  .filter((f) => f.endsWith(".sql"))
  .sort();

const selected = files.filter((file) => {
  // شماره مهاجرت = ترتیب فایل (۱ تا ۸)
  const num = files.indexOf(file) + 1;
  if (only && num !== only) return false;
  if (from && num < from) return false;
  return true;
});

if (selected.length === 0) {
  console.log("هیچ فایل SQL مطابق انتخاب پیدا نشد.");
  process.exit(0);
}

console.log(
  dryRun
    ? `حالت آزمایشی — ${selected.length} مهاجرت اجرا نمی‌شود:`
    : `اجرای ${selected.length} مهاجرت روی پروژه ${projectRef}:`
);

for (const file of selected) {
  const sql = readFileSync(join(dir, file), "utf8");
  if (dryRun) {
    console.log(`  • ${file} (${sql.length.toLocaleString("fa-IR")} کاراکتر)`);
    continue;
  }
  process.stdout.write(`  • ${file} ... `);
  const started = Date.now();
  try {
    const response = await fetch(
      `https://api.supabase.com/v1/projects/${projectRef}/database/query`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ query: sql, read_only: false }),
      }
    );
    const text = await response.text();
    if (!response.ok) {
      console.log("خطا");
      console.error(`\nHTTP ${response.status} در ${file}\n${text}\n`);
      process.exit(1);
    }
    console.log(`انجام شد (${((Date.now() - started) / 1000).toFixed(1)}s)`);
  } catch (error) {
    console.log("خطای شبکه");
    console.error(`\n${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(1);
  }
}

if (!dryRun) {
  console.log("\nهمه مهاجرت‌های انتخاب‌شده با موفقیت اجرا شدند.");
  console.log("گام بعد: در Supabase یک کاربر Auth بسازید و سپس:");
  console.log(
    "  select public.fn_bootstrap_store('پاکدل', 'اصلی', '<user_id>');"
  );
}

function numberArg(name) {
  const found = args.find((a) => a.startsWith(`${name}=`));
  if (!found) return undefined;
  const value = Number(found.split("=")[1]);
  return Number.isFinite(value) ? value : undefined;
}

function loadEnvFile(path) {
  if (!existsSync(path)) return;
  const content = readFileSync(path, "utf8");
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq < 0) continue;
    const key = trimmed.slice(0, eq).trim();
    const value = trimmed
      .slice(eq + 1)
      .trim()
      .replace(/^["']|["']$/g, "");
    // آخرین مقدار برنده است تا اگر خطی دوبار نوشته شد، نسخهٔ اصلاح‌شده اعمال شود
    process.env[key] = value;
  }
}
