-- ============================================================================
--  V3 — поиск, не зависящий от локали базы.
--
--  Проблема. И to_tsvector('russian', ...), и ILIKE приводят регистр средствами
--  локали базы. Если кластер создан с локалью C — а именно так ведёт себя
--  официальный образ postgres на Alpine, где musl не знает локали en_US.utf8, —
--  то lower() не трогает кириллицу:
--
--      lower('Документация')                        -> 'Документация'
--      to_tsvector('russian', 'Документация Spring') -> 'Документация':1
--
--  Поиск по русскому тексту при этом молча возвращает пустой результат: запрос
--  стеммится в нижний регистр, а в индексе лежат слова с заглавной буквы.
--  Ошибки нет — просто ничего не находится.
--
--  Решение. Сворачиваем регистр явной таблицей символов. translate() помечен
--  IMMUTABLE, от локали не зависит и годится и для генерируемой колонки,
--  и для выражения в индексе.
-- ============================================================================

create or replace function notes_fold(p_value text)
    returns text
    language sql
    immutable
    parallel safe
as $$
    select translate(
        p_value,
        'АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯABCDEFGHIJKLMNOPQRSTUVWXYZ',
        'абвгдеёжзийклмнопрстуфхцчшщъыьэюяabcdefghijklmnopqrstuvwxyz'
    )
$$;

comment on function notes_fold(text) is
    'Сворачивает регистр кириллицы и латиницы независимо от локали базы';

-- search_vector пересобирается из уже свёрнутого текста. Собственная
-- нормализация словаря russian после этого ничего не меняет, а стеммер
-- разбирает кириллицу по кодовым точкам и от локали не зависит.
drop index if exists notes_search_idx;
alter table notes drop column search_vector;

alter table notes
    add column search_vector tsvector
        generated always as (
            setweight(to_tsvector('russian', notes_fold(coalesce(title, ''))), 'A') ||
            setweight(to_tsvector('russian', notes_fold(coalesce(content, ''))), 'B')
        ) stored;

create index notes_search_idx on notes using gin (search_vector);

-- Поиск по части слова идёт по свёрнутому значению, поэтому и индекс
-- строится по выражению notes_fold(title), а не по самой колонке.
drop index if exists notes_title_trgm_idx;
create index notes_title_trgm_idx on notes using gin (notes_fold(title) gin_trgm_ops);
