create table friendships (
    requester_id bigint      not null references users (id) on delete cascade,
    addressee_id bigint      not null references users (id) on delete cascade,
    status       varchar(16) not null,
    created_at   timestamptz not null default now(),
    primary key (requester_id, addressee_id),
    constraint friendship_not_self check (requester_id <> addressee_id)
);

-- Дружба симметрична, но строка одна: направление хранится только чтобы знать, кто кого позвал.
create index idx_friendships_addressee on friendships (addressee_id, status);

-- Лидерборд сортирует по площади убыванием, отсюда индекс.
create index idx_user_stats_area on user_stats (area_m2 desc);

create table achievements (
    code      varchar(32)  primary key,
    title     varchar(128) not null,
    metric    varchar(16)  not null,
    threshold bigint       not null
);

create table user_achievements (
    user_id     bigint      not null references users (id) on delete cascade,
    code        varchar(32) not null references achievements (code),
    unlocked_at timestamptz not null default now(),
    primary key (user_id, code)
);

insert into achievements (code, title, metric, threshold) values
    ('area_1km',    'Первый километр',   'AREA_M2',  1000000),
    ('area_5km',    'Пять километров',   'AREA_M2',  5000000),
    ('area_25km',   'Двадцать пять',     'AREA_M2', 25000000),
    ('tiles_10',    'Десять тайлов',     'TILES',         10),
    ('tiles_100',   'Сотня тайлов',      'TILES',        100);
