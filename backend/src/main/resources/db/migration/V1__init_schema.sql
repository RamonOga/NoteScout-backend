-- ============================================================================
--  V1 — базовая схема NoteScout.
--
--  Принципы:
--    * все идентификаторы — uuid (не утекает количество записей, удобно для клиента);
--    * timestamptz везде, приложение работает в UTC;
--    * мягкое удаление заметок (deleted_at) — пользователь не теряет данные;
--    * теги приватны: уникальность имени в пределах пользователя, не глобально.
-- ============================================================================

create extension if not exists pg_trgm;

-- ---------------------------------------------------------------- users -----
create table users
(
    id            uuid primary key     default gen_random_uuid(),
    email         varchar(255) not null,
    password_hash varchar(255) not null,
    display_name  varchar(100) not null,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);

-- Регистр email не должен позволять создать два аккаунта: Ivan@x.ru == ivan@x.ru
create unique index users_email_lower_idx on users (lower(email));

-- ---------------------------------------------------------------- notes -----
create table notes
(
    id          uuid primary key     default gen_random_uuid(),
    user_id     uuid         not null references users (id) on delete cascade,
    type        varchar(16)  not null default 'TEXT',
    title       varchar(255) not null,
    content     text,
    url         text,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    archived_at timestamptz,
    deleted_at  timestamptz,

    constraint notes_type_check check (type in ('TEXT', 'LINK')),
    -- Запись-ссылка без URL бессмысленна: запрещаем на уровне БД, не только в коде.
    constraint notes_link_requires_url
        check (type <> 'LINK' or (url is not null and length(btrim(url)) > 0))
);

-- Основной сценарий чтения: «мои неудалённые заметки, свежие сверху».
create index notes_user_updated_idx on notes (user_id, updated_at desc) where deleted_at is null;
-- Список активных (не в архиве) заметок.
create index notes_user_active_idx on notes (user_id) where deleted_at is null and archived_at is null;

-- ----------------------------------------------------------------- tags -----
create table tags
(
    id              uuid primary key     default gen_random_uuid(),
    user_id         uuid         not null references users (id) on delete cascade,
    name            varchar(64)  not null, -- как ввёл пользователь (для отображения)
    normalized_name varchar(64)  not null, -- lower + сжатые пробелы (для уникальности и поиска)
    created_at      timestamptz  not null default now(),

    constraint tags_normalized_not_blank check (length(btrim(normalized_name)) > 0)
);

create unique index tags_user_normalized_idx on tags (user_id, normalized_name);

-- ------------------------------------------------------------ note_tags -----
create table note_tags
(
    note_id uuid not null references notes (id) on delete cascade,
    tag_id  uuid not null references tags (id) on delete cascade,
    primary key (note_id, tag_id)
);

create index note_tags_tag_idx on note_tags (tag_id);

-- ------------------------------------------------------- refresh_tokens -----
-- Хранится только SHA-256 от токена: утечка дампа БД не даёт войти в аккаунт.
create table refresh_tokens
(
    id          uuid primary key     default gen_random_uuid(),
    user_id     uuid         not null references users (id) on delete cascade,
    token_hash  varchar(64)  not null,
    device_info varchar(255),
    expires_at  timestamptz  not null,
    revoked_at  timestamptz,
    created_at  timestamptz  not null default now()
);

create unique index refresh_tokens_hash_idx on refresh_tokens (token_hash);
create index refresh_tokens_user_idx on refresh_tokens (user_id);
create index refresh_tokens_expires_idx on refresh_tokens (expires_at);
