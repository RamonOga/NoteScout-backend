-- ============================================================================
--  V4 — вложения к заметкам.
--
--  Файлы лежат не в базе, а в хранилище (том или внешний S3), в таблице —
--  только описание файла и ключ, по которому его достать.
--
--  Заметки удаляются мягко, поэтому on delete cascade здесь срабатывает
--  только при настоящем удалении строки. Это важно: удалённую заметку можно
--  восстановить, и вложения должны вернуться вместе с ней.
-- ============================================================================

create table attachments
(
    id           uuid primary key     default gen_random_uuid(),
    note_id      uuid         not null references notes (id) on delete cascade,
    -- user_id дублирует notes.user_id намеренно: проверка владельца при
    -- скачивании не должна требовать join с notes, а суммарный объём
    -- считается по пользователю напрямую.
    user_id      uuid         not null references users (id) on delete cascade,
    file_name    varchar(255) not null,
    content_type varchar(255) not null,
    size_bytes   bigint       not null,
    -- Ключ в хранилище. Уникален: он же защищает от случайной перезаписи
    -- чужого файла при совпадении имён.
    storage_key  varchar(512) not null,
    created_at   timestamptz  not null default now(),

    constraint attachments_size_positive check (size_bytes > 0)
);

create unique index attachments_storage_key_idx on attachments (storage_key);
create index attachments_note_idx on attachments (note_id);
create index attachments_user_idx on attachments (user_id);
