-- =============================================================
-- Pakdel Stationery — 01 Init: extensions, enums, tables
-- اجرا روی پایگاه‌داده خالی Supabase
-- =============================================================
create extension if not exists pgcrypto;
create extension if not exists pg_trgm;

-- ---------- Enums ----------
do $$ begin
  create type user_role_code as enum ('owner','manager','sales','cashier','warehouse');
exception when duplicate_object then null; end $$;

do $$ begin
  create type sale_type as enum ('sale','return');
exception when duplicate_object then null; end $$;

do $$ begin
  create type sale_status as enum ('completed','canceled');
exception when duplicate_object then null; end $$;

do $$ begin
  create type payment_method as enum ('cash','pos','bank','mixed','credit');
exception when duplicate_object then null; end $$;

do $$ begin
  create type payment_direction as enum ('in','out');
exception when duplicate_object then null; end $$;

do $$ begin
  create type party_type as enum ('sale','purchase','order','expense','customer','supplier');
exception when duplicate_object then null; end $$;

do $$ begin
  create type inventory_tx_type as enum
    ('purchase','sale','return_sale','return_purchase','adjustment','damage','stocktake','transfer');
exception when duplicate_object then null; end $$;

do $$ begin
  create type order_status as enum
    ('pending','approved','preparing','ready','shipped','delivered','canceled','returned');
exception when duplicate_object then null; end $$;

do $$ begin
  create type order_fulfillment as enum ('pickup','delivery');
exception when duplicate_object then null; end $$;

do $$ begin
  create type coupon_type as enum ('percent','amount','bogo');
exception when duplicate_object then null; end $$;

do $$ begin
  create type stocktake_status as enum ('draft','applied');
exception when duplicate_object then null; end $$;

do $$ begin
  create type purchase_status as enum ('draft','received','partial','canceled');
exception when duplicate_object then null; end $$;

-- ---------- Organization ----------
create table if not exists public.stores (
  id            uuid primary key,
  name          text not null,
  legal_name    text,
  phone         text,
  address       text,
  currency      text not null default 'IRT',
  timezone      text not null default 'Asia/Tehran',
  logo_path     text,
  is_active     boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create table if not exists public.branches (
  id         uuid primary key,
  store_id   uuid not null references public.stores(id) on delete cascade,
  name       text not null,
  address    text,
  phone      text,
  is_active  boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.roles (
  id         uuid primary key,
  store_id   uuid references public.stores(id) on delete cascade,
  code       user_role_code not null,
  title      text not null,
  is_system  boolean not null default false,
  created_at timestamptz not null default now(),
  unique (store_id, code)
);

create table if not exists public.permissions (
  id    uuid primary key,
  code  text not null unique,
  title text not null
);

create table if not exists public.role_permissions (
  role_id       uuid not null references public.roles(id) on delete cascade,
  permission_id uuid not null references public.permissions(id) on delete cascade,
  primary key (role_id, permission_id)
);

create table if not exists public.store_members (
  id         uuid primary key,
  store_id   uuid not null references public.stores(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  branch_id  uuid references public.branches(id) on delete set null,
  role_id    uuid not null references public.roles(id),
  full_name  text,
  is_active  boolean not null default true,
  created_at timestamptz not null default now(),
  unique (store_id, user_id)
);

-- ---------- Catalog ----------
create table if not exists public.categories (
  id          uuid primary key,
  store_id    uuid not null references public.stores(id) on delete cascade,
  parent_id   uuid references public.categories(id) on delete set null,
  name        text not null,
  slug        text,
  icon        text,
  color       text,
  sort_order  int not null default 0,
  is_active   boolean not null default true,
  deleted_at  timestamptz,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table if not exists public.suppliers (
  id             uuid primary key,
  store_id       uuid not null references public.stores(id) on delete cascade,
  name           text not null,
  contact_name   text,
  phone          text,
  email          text,
  address        text,
  note           text,
  balance_cached bigint not null default 0,
  is_active      boolean not null default true,
  deleted_at     timestamptz,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create table if not exists public.products (
  id              uuid primary key,
  store_id        uuid not null references public.stores(id) on delete cascade,
  category_id     uuid references public.categories(id) on delete set null,
  supplier_id     uuid references public.suppliers(id) on delete set null,
  name            text not null,
  sku             text,
  barcode         text,
  description     text,
  brand           text,
  unit            text not null default 'عدد',
  purchase_price  bigint not null default 0 check (purchase_price >= 0),
  sale_price      bigint not null default 0 check (sale_price >= 0),
  wholesale_price bigint not null default 0 check (wholesale_price >= 0),
  min_stock       int not null default 0 check (min_stock >= 0),
  stock_cached    int not null default 0,
  attributes      jsonb not null default '{}'::jsonb,
  image_path      text,
  is_public       boolean not null default false,
  is_active       boolean not null default true,
  deleted_at      timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create table if not exists public.product_variants (
  id             uuid primary key,
  product_id     uuid not null references public.products(id) on delete cascade,
  sku            text,
  barcode        text,
  color          text,
  size           text,
  model          text,
  extra_price    bigint not null default 0,
  purchase_price bigint not null default 0,
  sale_price     bigint not null default 0,
  stock_cached   int not null default 0,
  is_active      boolean not null default true,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create table if not exists public.product_images (
  id           uuid primary key,
  product_id   uuid not null references public.products(id) on delete cascade,
  storage_path text not null,
  sort_order   int not null default 0,
  created_at   timestamptz not null default now()
);

create table if not exists public.price_history (
  id          uuid primary key default gen_random_uuid(),
  store_id    uuid not null references public.stores(id) on delete cascade,
  product_id  uuid not null references public.products(id) on delete cascade,
  variant_id  uuid references public.product_variants(id) on delete cascade,
  old_price   bigint not null,
  new_price   bigint not null,
  changed_by  uuid references auth.users(id),
  changed_at  timestamptz not null default now()
);

-- ---------- Customers ----------
create table if not exists public.customers (
  id              uuid primary key,
  store_id        uuid not null references public.stores(id) on delete cascade,
  user_id         uuid references auth.users(id) on delete set null,
  full_name       text not null,
  phone           text,
  email           text,
  group_code      text not null default 'regular',
  discount_percent numeric(5,2) not null default 0 check (discount_percent between 0 and 100),
  credit_limit    bigint not null default 0,
  balance_cached  bigint not null default 0,
  note            text,
  is_active       boolean not null default true,
  deleted_at      timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create table if not exists public.addresses (
  id             uuid primary key,
  customer_id    uuid not null references public.customers(id) on delete cascade,
  title          text not null default 'خانه',
  province       text not null,
  city           text not null,
  postal_code    text,
  line1          text not null,
  receiver_name  text not null,
  receiver_phone text not null,
  is_default     boolean not null default false,
  created_at     timestamptz not null default now()
);

-- ---------- Sales ----------
create table if not exists public.sales (
  id                    uuid primary key,
  store_id              uuid not null references public.stores(id) on delete cascade,
  branch_id             uuid references public.branches(id) on delete set null,
  customer_id           uuid references public.customers(id) on delete set null,
  cashier_id            uuid references auth.users(id) on delete set null,
  invoice_number        text not null,
  sale_type             sale_type not null default 'sale',
  status                sale_status not null default 'completed',
  subtotal              bigint not null default 0,
  item_discount_total   bigint not null default 0,
  invoice_discount      bigint not null default 0,
  discount_total        bigint not null default 0,
  tax_total             bigint not null default 0,
  grand_total           bigint not null default 0,
  paid_total            bigint not null default 0,
  due_total             bigint not null default 0,
  payment_method        payment_method not null default 'cash',
  coupon_id             uuid,
  note                  text,
  client_operation_id   uuid not null,
  sold_at               timestamptz not null default now(),
  canceled_at           timestamptz,
  cancel_reason         text,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  unique (store_id, invoice_number),
  unique (store_id, client_operation_id)
);

create table if not exists public.sale_items (
  id             uuid primary key,
  sale_id        uuid not null references public.sales(id) on delete cascade,
  product_id     uuid references public.products(id) on delete set null,
  variant_id     uuid references public.product_variants(id) on delete set null,
  name_snapshot  text not null,
  qty            int not null check (qty > 0),
  unit_price     bigint not null check (unit_price >= 0),
  unit_cost      bigint not null default 0,
  discount       bigint not null default 0,
  line_total     bigint not null
);

-- ---------- Purchases ----------
create table if not exists public.purchases (
  id                  uuid primary key,
  store_id            uuid not null references public.stores(id) on delete cascade,
  supplier_id         uuid references public.suppliers(id) on delete set null,
  invoice_number      text,
  status              purchase_status not null default 'draft',
  subtotal            bigint not null default 0,
  discount_total      bigint not null default 0,
  grand_total         bigint not null default 0,
  paid_total          bigint not null default 0,
  due_total           bigint not null default 0,
  note                text,
  client_operation_id uuid not null,
  purchased_at        timestamptz not null default now(),
  created_at          timestamptz not null default now(),
  unique (store_id, client_operation_id)
);

create table if not exists public.purchase_items (
  id          uuid primary key,
  purchase_id uuid not null references public.purchases(id) on delete cascade,
  product_id  uuid not null references public.products(id) on delete restrict,
  variant_id  uuid references public.product_variants(id) on delete set null,
  name_snapshot text not null,
  qty         int not null check (qty > 0),
  unit_cost   bigint not null check (unit_cost >= 0),
  line_total  bigint not null
);

-- ---------- Inventory ----------
create table if not exists public.inventory_transactions (
  id                  uuid primary key,
  store_id            uuid not null references public.stores(id) on delete cascade,
  branch_id           uuid references public.branches(id) on delete set null,
  product_id          uuid not null references public.products(id) on delete restrict,
  variant_id          uuid references public.product_variants(id) on delete set null,
  qty_delta           int not null check (qty_delta <> 0),
  type                inventory_tx_type not null,
  reference_type      text,
  reference_id        uuid,
  unit_cost           bigint not null default 0,
  reason              text,
  user_id             uuid references auth.users(id) on delete set null,
  client_operation_id uuid not null,
  created_at          timestamptz not null default now(),
  unique (store_id, client_operation_id)
);

create table if not exists public.stocktakes (
  id         uuid primary key,
  store_id   uuid not null references public.stores(id) on delete cascade,
  branch_id  uuid references public.branches(id) on delete set null,
  status     stocktake_status not null default 'draft',
  note       text,
  counted_at timestamptz,
  applied_by uuid references auth.users(id),
  created_at timestamptz not null default now()
);

create table if not exists public.stocktake_items (
  id           uuid primary key,
  stocktake_id uuid not null references public.stocktakes(id) on delete cascade,
  product_id   uuid not null references public.products(id) on delete cascade,
  variant_id   uuid references public.product_variants(id) on delete cascade,
  system_qty   int not null default 0,
  counted_qty  int not null default 0,
  diff_qty     int not null default 0
);

-- ---------- Finance ----------
create table if not exists public.payments (
  id                  uuid primary key,
  store_id            uuid not null references public.stores(id) on delete cascade,
  direction           payment_direction not null,
  party_type          party_type not null,
  party_id            uuid,
  method              payment_method not null default 'cash',
  amount              bigint not null check (amount > 0),
  reference           text,
  note                text,
  paid_at             timestamptz not null default now(),
  user_id             uuid references auth.users(id) on delete set null,
  client_operation_id uuid not null,
  created_at          timestamptz not null default now(),
  unique (store_id, client_operation_id)
);

create table if not exists public.expenses (
  id                  uuid primary key,
  store_id            uuid not null references public.stores(id) on delete cascade,
  category            text not null default 'general',
  title               text not null,
  amount              bigint not null check (amount > 0),
  paid_at             timestamptz not null default now(),
  user_id             uuid references auth.users(id) on delete set null,
  note                text,
  client_operation_id uuid not null,
  created_at          timestamptz not null default now(),
  unique (store_id, client_operation_id)
);

-- ---------- Online orders ----------
create table if not exists public.orders (
  id                  uuid primary key,
  store_id            uuid not null references public.stores(id) on delete cascade,
  branch_id           uuid references public.branches(id) on delete set null,
  customer_id         uuid not null references public.customers(id) on delete restrict,
  order_number        text not null,
  status              order_status not null default 'pending',
  fulfillment         order_fulfillment not null default 'delivery',
  address_id          uuid references public.addresses(id) on delete set null,
  subtotal            bigint not null default 0,
  item_discount_total bigint not null default 0,
  discount_total      bigint not null default 0,
  shipping_total      bigint not null default 0,
  grand_total         bigint not null default 0,
  coupon_id           uuid,
  note                text,
  client_operation_id uuid not null,
  placed_at           timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  unique (store_id, order_number),
  unique (store_id, client_operation_id)
);

create table if not exists public.order_items (
  id            uuid primary key,
  order_id      uuid not null references public.orders(id) on delete cascade,
  product_id    uuid not null references public.products(id) on delete restrict,
  variant_id    uuid references public.product_variants(id) on delete set null,
  name_snapshot text not null,
  qty           int not null check (qty > 0),
  unit_price    bigint not null check (unit_price >= 0),
  discount      bigint not null default 0,
  line_total    bigint not null
);

create table if not exists public.order_status_history (
  id          uuid primary key default gen_random_uuid(),
  order_id    uuid not null references public.orders(id) on delete cascade,
  from_status order_status,
  to_status   order_status not null,
  changed_by  uuid references auth.users(id),
  note        text,
  created_at  timestamptz not null default now()
);

-- ---------- Marketing ----------
create table if not exists public.coupons (
  id                uuid primary key,
  store_id          uuid not null references public.stores(id) on delete cascade,
  code              text not null,
  type              coupon_type not null default 'percent',
  value             bigint not null default 0,
  min_subtotal      bigint not null default 0,
  max_discount      bigint,
  usage_limit       int,
  used_count        int not null default 0,
  per_customer_limit int not null default 1,
  starts_at         timestamptz,
  ends_at           timestamptz,
  is_active         boolean not null default true,
  created_at        timestamptz not null default now(),
  unique (store_id, code)
);

create table if not exists public.coupon_redemptions (
  id          uuid primary key default gen_random_uuid(),
  coupon_id   uuid not null references public.coupons(id) on delete cascade,
  customer_id uuid references public.customers(id) on delete set null,
  order_id    uuid references public.orders(id) on delete set null,
  sale_id     uuid references public.sales(id) on delete set null,
  amount      bigint not null default 0,
  redeemed_at timestamptz not null default now()
);

create table if not exists public.campaigns (
  id         uuid primary key,
  store_id   uuid not null references public.stores(id) on delete cascade,
  title      text not null,
  type       text not null default 'percent',
  starts_at  timestamptz not null default now(),
  ends_at    timestamptz,
  is_active  boolean not null default true,
  config     jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists public.campaign_products (
  campaign_id      uuid not null references public.campaigns(id) on delete cascade,
  product_id       uuid not null references public.products(id) on delete cascade,
  discount_percent numeric(5,2) not null default 0,
  primary key (campaign_id, product_id)
);

create table if not exists public.loyalty_accounts (
  id               uuid primary key,
  store_id         uuid not null references public.stores(id) on delete cascade,
  customer_id      uuid not null references public.customers(id) on delete cascade,
  points           int not null default 0 check (points >= 0),
  lifetime_points  int not null default 0,
  tier             text not null default 'bronze',
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (store_id, customer_id)
);

create table if not exists public.loyalty_transactions (
  id           uuid primary key default gen_random_uuid(),
  account_id   uuid not null references public.loyalty_accounts(id) on delete cascade,
  points_delta int not null,
  reason       text not null,
  reference_id uuid,
  created_at   timestamptz not null default now()
);

create table if not exists public.school_lists (
  id         uuid primary key,
  store_id   uuid not null references public.stores(id) on delete cascade,
  grade_code text not null,
  title      text not null,
  sort_order int not null default 0,
  is_active  boolean not null default true,
  unique (store_id, grade_code)
);

create table if not exists public.school_list_items (
  id           uuid primary key,
  list_id      uuid not null references public.school_lists(id) on delete cascade,
  product_id   uuid not null references public.products(id) on delete cascade,
  default_qty  int not null default 1 check (default_qty > 0),
  sort_order   int not null default 0,
  unique (list_id, product_id)
);

create table if not exists public.notifications (
  id         uuid primary key default gen_random_uuid(),
  store_id   uuid references public.stores(id) on delete cascade,
  user_id    uuid references auth.users(id) on delete cascade,
  audience   text not null default 'staff',
  title      text not null,
  body       text not null,
  type       text not null default 'general',
  is_read    boolean not null default false,
  data       jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists public.app_settings (
  id         uuid primary key default gen_random_uuid(),
  store_id   uuid not null references public.stores(id) on delete cascade,
  key        text not null,
  value      jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  unique (store_id, key)
);

create table if not exists public.audit_logs (
  id         uuid primary key default gen_random_uuid(),
  store_id   uuid references public.stores(id) on delete cascade,
  user_id    uuid references auth.users(id) on delete set null,
  action     text not null,
  entity     text not null,
  entity_id  uuid,
  before     jsonb,
  after      jsonb,
  ip         text,
  created_at timestamptz not null default now()
);
