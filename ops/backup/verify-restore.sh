#!/bin/sh
# ============================================================================
#  Автоматическая проверка резервной копии.
#
#  Восстанавливает последнюю копию во временную базу и сверяет количество
#  строк с данными, зафиксированными в момент создания дампа. Резервная копия,
#  которую ни разу не восстанавливали, копией не считается.
#
#  Запуск:
#      docker compose exec backup /usr/local/bin/verify-restore.sh
#
#  Рекомендуется добавить в cron раз в неделю — см. docs/runbook-backup.md.
# ============================================================================
set -eu

# См. комментарий в backup.sh: при LC_ALL=C порядок раскрытия масок совпадает
# с хронологическим, потому что в имени копии — UTC-таймстамп фиксированной ширины.
export LC_ALL=C

BACKUP_DIR="${BACKUP_DIR:-/backups}"
SET="${1:-daily}"

log() {
    printf '%s [verify] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
    log "ПРОВЕРКА НЕ ПРОЙДЕНА: $*"
    exit 1
}

# Последний элемент маски — самая свежая копия (см. комментарий про LC_ALL=C).
latest=""
for candidate in "$BACKUP_DIR/$SET"/notescout-*.dump; do
    [ -e "$candidate" ] || continue
    latest="$candidate"
done
[ -n "$latest" ] || fail "в каталоге $BACKUP_DIR/$SET нет ни одной копии"

counts_file="${latest}.counts"
[ -f "$counts_file" ] || fail "не найден файл ожидаемых счётчиков ${counts_file}"

verify_db="notescout_verify_$(date -u +%Y%m%d%H%M%S)"

cleanup() {
    psql -d postgres -c "drop database if exists \"${verify_db}\"" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

log "Проверяю копию: $(basename "$latest")"
log "Временная база: ${verify_db}"

CONFIRM_RESTORE=yes /usr/local/bin/restore.sh "$latest" "$verify_db" >/dev/null \
    || fail "восстановление копии не удалось"

mismatches=0
while IFS='=' read -r table expected; do
    [ -n "$table" ] || continue
    actual=$(psql -d "$verify_db" -tAc "select count(*) from \"${table}\"") \
        || fail "не удалось прочитать таблицу ${table} в восстановленной базе"

    if [ "$actual" = "$expected" ]; then
        log "  ${table}: ${actual} строк — совпадает"
    else
        log "  ${table}: ожидалось ${expected}, восстановлено ${actual} — РАСХОЖДЕНИЕ"
        mismatches=$((mismatches + 1))
    fi
done < "$counts_file"

[ "$mismatches" -eq 0 ] || fail "расхождений: ${mismatches}. Копия неполная или повреждена"

log "Проверка пройдена: копия восстанавливается и содержит ожидаемые данные"
