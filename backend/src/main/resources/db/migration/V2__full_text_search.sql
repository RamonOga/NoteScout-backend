-- ============================================================================
--  V2 — полнотекстовый поиск по заметкам.
--
--  search_vector — генерируемая колонка: Postgres сам пересчитывает её при
--  любом изменении title/content. Триггеры не нужны, рассинхрон невозможен.
--
--  Функции to_tsvector(regconfig, text) и setweight() помечены IMMUTABLE,
--  поэтому их можно использовать в generated always as ... stored.
-- ============================================================================

alter table notes
    add column search_vector tsvector
        generated always as (
            setweight(to_tsvector('russian', coalesce(title, '')), 'A') ||
            setweight(to_tsvector('russian', coalesce(content, '')), 'B')
        ) stored;

-- Полнотекстовый поиск (GIN по tsvector).
create index notes_search_idx on notes using gin (search_vector);

-- Поиск по части слова: пользователь вводит «док» и находит «документация».
create index notes_title_trgm_idx on notes using gin (title gin_trgm_ops);
