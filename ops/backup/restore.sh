#!/bin/sh
# ============================================================================
#  Восстановление базы из резервной копии.
#
#  Использование:
#      restore.sh <файл.dump> [целевая_база]
#
#  Без переменной CONFIRM_RESTORE=yes скрипт отказывается работать: по умолчанию
#  целевая база — боевая, и восстановление затрёт её содержимое.
#
#  Пример: восстановить последнюю копию в отдельную базу для проверки
#      CONFIRM_RESTORE=yes restore.sh /backups/daily/notescout-...dump notescout_check
# ============================================================================
set -eu

usage() {
    cat <<'USAGE'
Использование: restore.sh <файл.dump> [целевая_база]

  целевая_база по умолчанию равна $PGDATABASE (боевая база).

  Требуется CONFIRM_RESTORE=yes — защита от случайной перезаписи рабочих данных.
USAGE
    exit 1
}

log() {
    printf '%s [restore] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
    log "ОШИБКА: $*"
    exit 1
}

[ $# -ge 1 ] || usage

dump_file="$1"
target_db="${2:-${PGDATABASE:-}}"

[ -n "$target_db" ] || fail "не задана целевая база (второй аргумент или PGDATABASE)"
[ -f "$dump_file" ] || fail "файл не найден: $dump_file"

# --- проверка контрольной суммы --------------------------------------------
checksum_file="${dump_file}.sha256"
if [ -f "$checksum_file" ]; then
    expected=$(cat "$checksum_file")
    actual=$(sha256sum "$dump_file" | awk '{print $1}')
    [ "$expected" = "$actual" ] || fail "контрольная сумма не совпала: файл повреждён"
    log "Контрольная сумма совпала"
else
    log "ПРЕДУПРЕЖДЕНИЕ: файл контрольной суммы не найден, проверка пропущена"
fi

# --- защита от случайной перезаписи боевой базы -----------------------------
if [ "${CONFIRM_RESTORE:-no}" != "yes" ]; then
    fail "восстановление в базу '${target_db}' перезапишет её содержимое. Запустите с CONFIRM_RESTORE=yes"
fi

# --- подготовка целевой базы ------------------------------------------------
# Запрос передаём через стандартный ввод: подстановку :'db' psql выполняет
# только в таком режиме. В `-c` последовательность остаётся как есть, Postgres
# отвечает «syntax error at or near ":"», а из-за `|| true` ошибка молча
# превращалась в «базы нет» — и восстановление шло не в ту базу.
if ! db_exists=$(psql -d postgres -tA -v db="$target_db" <<'SQL'
select 1 from pg_database where datname = :'db';
SQL
); then
    fail "не удалось проверить, существует ли база '${target_db}'"
fi

if [ "$db_exists" = "1" ]; then
    log "База '${target_db}' существует — содержимое будет перезаписано"
else
    log "База '${target_db}' не найдена — создаю"
    createdb "$target_db" || fail "не удалось создать базу '${target_db}'"
fi

# --- восстановление ---------------------------------------------------------
# --clean --if-exists: удаляем существующие объекты перед созданием,
# --no-owner/--no-privileges: дамп не зависит от имён ролей на другом сервере.
log "Восстанавливаю ${dump_file} -> ${target_db}"
pg_restore \
    --clean --if-exists \
    --no-owner --no-privileges \
    --dbname="$target_db" \
    "$dump_file" \
    || fail "pg_restore завершился с ошибкой"

log "Восстановление завершено. Проверьте данные: psql -d ${target_db} -c 'select count(*) from notes'"
