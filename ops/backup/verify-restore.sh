#!/bin/sh
# ============================================================================
#  Автоматическая проверка резервной копии.
#
#  Скачивает последнюю копию из внешнего хранилища, сверяет контрольную сумму,
#  восстанавливает её во временную базу и сверяет количество строк с данными,
#  зафиксированными в момент создания дампа. Копия, которую ни разу не
#  восстанавливали, копией не считается.
#
#  Проверяется именно то, что лежит в хранилище, а не локальный близнец:
#  локальных копий больше нет, и восстанавливаться придётся из хранилища.
#
#  Запуск:
#      docker compose exec backup /usr/local/bin/verify-restore.sh [набор]
#
#  Рекомендуется добавить в cron раз в неделю — см. docs/runbook-backup.md.
# ============================================================================
set -eu

# См. комментарий в backup.sh: при LC_ALL=C порядок сравнения имён совпадает
# с хронологическим, потому что в имени — UTC-таймстамп фиксированной ширины.
export LC_ALL=C

BACKUP_DIR="${BACKUP_DIR:-/backups}"
SET="${1:-daily}"

S3_REMOTE="${S3_REMOTE:-}"
S3_BUCKET="${S3_BUCKET:-}"
S3_PREFIX="${S3_PREFIX:-notescout}"

log() {
    printf '%s [verify] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
    log "ПРОВЕРКА НЕ ПРОЙДЕНА: $*"
    exit 1
}

if [ -z "$S3_REMOTE" ] || [ -z "$S3_BUCKET" ]; then
    fail "внешнее хранилище не настроено (S3_REMOTE/S3_BUCKET пусты) — проверять нечего"
fi

remote_path="${S3_REMOTE}:${S3_BUCKET}/${S3_PREFIX}"
remote_set="${remote_path}/${SET}"

# Рабочий каталог для скачанного: он временный, копии в нём не хранятся.
work_dir=$(mktemp -d)
verify_db=""

cleanup() {
    if [ -n "$verify_db" ]; then
        psql -d postgres -c "drop database if exists \"${verify_db}\"" >/dev/null 2>&1 || true
    fi
    rm -rf "$work_dir"
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
#  Берём последнюю копию из хранилища. Список отдаёт rclone: локального
#  перечня копий больше нет, а сортировка по имени совпадает с хронологической.
# ---------------------------------------------------------------------------
dumps=$(rclone lsf "$remote_set/" 2>/dev/null | grep '\.dump$' | sort) \
    || fail "не удалось получить список копий из ${remote_set}"

latest=$(printf '%s\n' "$dumps" | grep . | tail -1 || true)
[ -n "$latest" ] || fail "в наборе ${SET} нет ни одной копии"

log "Проверяю копию из хранилища: ${latest}"

for suffix in "" ".sha256" ".counts"; do
    rclone copy "${remote_set}/${latest}${suffix}" "$work_dir/" \
        || fail "не удалось скачать ${latest}${suffix} из хранилища"
done

dump="${work_dir}/${latest}"
counts_file="${dump}.counts"

[ -f "$dump" ] || fail "дамп не скачался"
[ -f "${dump}.sha256" ] || fail "нет файла контрольной суммы"
[ -f "$counts_file" ] || fail "нет файла ожидаемых счётчиков"

# Контрольная сумма хранится без имени файла, поэтому `sha256sum -c` здесь
# не подходит: он ждёт строку вида «хеш  имя». Сравниваем вручную.
expected_hash=$(cat "${dump}.sha256")
actual_hash=$(sha256sum "$dump" | awk '{print $1}')
[ "$expected_hash" = "$actual_hash" ] \
    || fail "контрольная сумма не совпадает — копия повреждена при выгрузке"
log "  контрольная сумма: совпадает"

# Пустой файл означает, что сверять нечего, и проверка прошла бы впустую —
# ровно так выглядит копия, снятая до появления схемы.
[ -s "$counts_file" ] || fail "файл счётчиков пуст: копия снята до появления схемы или повреждена"

verify_db="notescout_verify_$(date -u +%Y%m%d%H%M%S)"
log "  временная база: ${verify_db}"

CONFIRM_RESTORE=yes /usr/local/bin/restore.sh "$dump" "$verify_db" >/dev/null \
    || fail "восстановление копии не удалось"

mismatches=0
compared=0
while IFS='=' read -r table expected; do
    [ -n "$table" ] || continue
    compared=$((compared + 1))
    actual=$(psql -d "$verify_db" -tAc "select count(*) from \"${table}\"") \
        || fail "не удалось прочитать таблицу ${table} в восстановленной базе"

    if [ "$actual" = "$expected" ]; then
        log "  ${table}: ${actual} строк — совпадает"
    else
        log "  ${table}: ожидалось ${expected}, восстановлено ${actual} — РАСХОЖДЕНИЕ"
        mismatches=$((mismatches + 1))
    fi
done < "$counts_file"

[ "$compared" -gt 0 ] || fail "в файле счётчиков нет ни одной таблицы — проверять нечего"
[ "$mismatches" -eq 0 ] || fail "расхождений: ${mismatches}. Копия неполная или повреждена"

# ---------------------------------------------------------------------------
#  Файлы вложений. Дамп их не содержит — они лежат в томе, — поэтому копия
#  без целого архива вложений полной не считается: база восстановится,
#  а заметки придут без файлов.
# ---------------------------------------------------------------------------
attachments_name=$(basename "$dump" .dump)-attachments.tar.gz
attachments_path="${work_dir}/${attachments_name}"

if rclone copy "${remote_set}/${attachments_name}" "$work_dir/" 2>/dev/null \
    && [ -f "$attachments_path" ]; then
    expected_attachments_hash=$(cat "${attachments_path}.sha256" 2>/dev/null || true)
    [ -n "$expected_attachments_hash" ] \
        || fail "нет контрольной суммы для ${attachments_name}"

    actual_attachments_hash=$(sha256sum "$attachments_path" | awk '{print $1}')
    [ "$actual_attachments_hash" = "$expected_attachments_hash" ] \
        || fail "контрольная сумма архива вложений не совпадает — архив повреждён"

    expected_files=$(sed -n 's/^files=//p' "${attachments_path}.counts" 2>/dev/null)
    [ -n "$expected_files" ] || fail "нет счётчика файлов для архива вложений"

    # В архиве есть и каталоги — они оканчиваются на слэш, их не считаем.
    actual_files=$(tar -tzf "$attachments_path" | grep -vc '/$' || true)

    [ "$actual_files" = "$expected_files" ] \
        || fail "в архиве вложений ${actual_files} файлов, ожидалось ${expected_files}"

    log "  вложения: ${actual_files} файлов — совпадает"

    # Сверка с базой: файлов должно быть не меньше, чем строк в attachments.
    # Меньше файлов, чем строк, означает вложение, которое нечем открыть.
    restored_attachments=$(psql -d "$verify_db" -tAc "select count(*) from attachments")
    if [ "$actual_files" -lt "$restored_attachments" ]; then
        fail "в базе ${restored_attachments} вложений, а файлов в архиве ${actual_files}: часть файлов потеряна"
    fi
    if [ "$actual_files" -gt "$restored_attachments" ]; then
        # Не отказ: лишние файлы занимают место, но данные целы.
        log "  ПРЕДУПРЕЖДЕНИЕ: в архиве ${actual_files} файлов при ${restored_attachments} строках — есть осиротевшие файлы"
    fi
else
    log "  вложений в копии нет: архив ${attachments_name} не найден"
fi

log "Проверка пройдена: сверено таблиц — ${compared}, расхождений нет"
