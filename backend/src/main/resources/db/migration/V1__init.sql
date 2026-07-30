create table users (
    id            bigserial primary key,
    username      varchar(32)  not null unique,
    email         varchar(255) not null unique,
    password_hash varchar(255) not null,
    created_at    timestamptz  not null default now()
);

create table refresh_tokens (
    id         bigserial   primary key,
    user_id    bigint      not null references users (id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz
);

create index idx_refresh_tokens_user on refresh_tokens (user_id);

-- Маска тумана: растр, а не геометрия, поэтому bytea и никакого PostGIS.
create table fog_tiles (
    user_id        bigint      not null references users (id) on delete cascade,
    x              integer     not null,
    y              integer     not null,
    mask           bytea       not null,
    -- Денормализованный popcount маски. Считает только сервер, значению от клиента не верим.
    revealed_cells integer     not null,
    updated_at     timestamptz not null,
    primary key (user_id, x, y)
);

create index idx_fog_tiles_user_updated on fog_tiles (user_id, updated_at);

create table user_stats (
    user_id     bigint      primary key references users (id) on delete cascade,
    area_m2     bigint      not null default 0,
    tiles_count integer     not null default 0,
    updated_at  timestamptz not null default now()
);
