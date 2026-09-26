#!/bin/sh
# ============================================================================
#  Создание резервной копии базы NoteScout.
#
#  Что делает:
#    1. снимает дамп в сжатом формате pg_dump -Fc;
#    2. проверяет, что архив читается (битый дамп хуже отсутствующего);
#    3. считает контрольную сумму и снимок количества строк по таблицам;
#    4. архивирует файлы вложений: они лежат не в базе, и без этого шага
#       тексты заметок восстанавливались бы, а файлы — нет;
#    5. раскладывает копию в недельный (вс) и месячный (1-е число) наборы;
#    6. удаляет устаревшие копии по политике хранения — и локально, и во
#       внешнем хранилище: иначе бакет рос бы без предела;
#    7. при настроенном S3_REMOTE выгружает копию во внешнее хранилище.
#
#  Запуск вручную:
#    docker compose exec backup /usr/local/bin/backup.sh
# ============================================================================
set -eu

# Побайтовый порядок раскрытия масок и сравнения строк. Имена копий содержат
# UTC-таймстамп фиксированной ширины, поэтому при LC_ALL=C лексикографический
# порядок совпадает с хронологическим — на этом построен отбор устаревших копий.
export LC_ALL=C

BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAILY="${RETENTION_DAILY:-7}"
RETENTION_WEEKLY="${RETENTION_WEEKLY:-4}"
RETENTION_MONTHLY="${RETENTION_MONTHLY:-12}"
S3_REMOTE="${S3_REMOTE:-}"
S3_BUCKET="${S3_BUCKET:-}"
S3_PREFIX="${S3_PREFIX:-notescout}"

# Путь во внешнем хранилище. Пустая строка означает «внешнее не настроено» —
# по ней же решается, нужно ли чистить облако вместе с локальными копиями.
remote_path=""
if [ -n "$S3_REMOTE" ] && [ -n "$S3_BUCKET" ]; then
    remote_path="${S3_REMOTE}:${S3_BUCKET}/${S3_PREFIX}"
fi
# Каталог файлов вложений. Пусто — шаг архивации пропускается: так копия
# снимается и на установке, где вложений ещё нет.
ATTACHMENTS_DIR="${ATTACHMENTS_DIR:-}"

TABLES="users notes tags note_tags refresh_tokens attachments"

log() {
    printf '%s [backup] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
    log "ОШИБКА: $*"
    exit 1
}

: "${PGDATABASE:?переменная PGDATABASE не задана}"
: "${PGUSER:?переменная PGUSER не задана}"

mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$BACKUP_DIR/daily/notescout-${timestamp}.dump"
tmp="${target}.part"
attachments_target="$BACKUP_DIR/daily/notescout-${timestamp}-attachments.tar.gz"
attachments_tmp="${attachments_target}.part"

# Неудачный бэкап не должен оставлять после себя файлы. Иначе, например, дамп
# без счётчиков выглядит как рабочая копия: раньше verify-restore.sh на пустом
# файле счётчиков «проходил» проверку, ничего не сверив.
completed=no
cleanup_partial() {
    if [ "$completed" != "yes" ]; then
        rm -f "$target" "${target}.sha256" "${target}.counts" "$tmp"
        rm -f "$attachments_target" "${attachments_target}.sha256" \
            "${attachments_target}.counts" "$attachments_tmp"
        log "Копия неполная — файлы удалены, чтобы не выглядели как рабочая копия"
    fi
}
trap cleanup_partial EXIT INT TERM

log "Дамп базы ${PGDATABASE} -> ${target}"

# -Fc: сжатый формат, восстанавливается pg_restore, допускает выборочное
# восстановление отдельных таблиц.
pg_dump --format=custom --compress=6 --no-owner --no-privileges \
    --file="$tmp" "$PGDATABASE" \
    || fail "pg_dump завершился с ошибкой"

# Читаемость архива — минимальная гарантия, что копия пригодна.
pg_restore --list "$tmp" > /dev/null 2>&1 \
    || { rm -f "$tmp"; fail "дамп не читается pg_restore"; }

mv "$tmp" "$target"
sha256sum "$target" | awk '{print $1}' > "${target}.sha256"

# Снимок количества строк: по нему verify-restore.sh убедится, что
# восстановилось ровно столько же данных, сколько было в момент дампа.
counts_file="${target}.counts"
: > "$counts_file"
for table in $TABLES; do
    value=$(psql -d "$PGDATABASE" -tAc "select count(*) from ${table}") \
        || fail "не удалось посчитать строки в таблице ${table}"
    printf '%s=%s\n' "$table" "$value" >> "$counts_file"
done

log "Готово: $(basename "$target"), размер $(du -h "$target" | awk '{print $1}')"

# ---------------------------------------------------------------------------
#  Файлы вложений. Лежат в томе, а не в базе, поэтому в дамп не попадают:
#  без этого шага после восстановления заметки вернулись бы без файлов, а
#  вложения оказались бы единственными данными без копии.
# ---------------------------------------------------------------------------
if [ -n "$ATTACHMENTS_DIR" ]; then
    if [ -d "$ATTACHMENTS_DIR" ]; then
        # Считаем файлы. Имена в хранилище — uuid, поэтому перенос строки в
        # имени невозможен и `wc -l` точен. `find -printf` не используем:
        # в alpine это busybox, где его нет.
        attachments_count=$(find "$ATTACHMENTS_DIR" -type f | wc -l)

        tar -czf "$attachments_tmp" -C "$ATTACHMENTS_DIR" . \
            || fail "не удалось заархивировать файлы вложений"

        # Читаемость архива — та же минимальная гарантия, что и у дампа.
        tar -tzf "$attachments_tmp" > /dev/null 2>&1 \
            || { rm -f "$attachments_tmp"; fail "архив вложений не читается"; }

        mv "$attachments_tmp" "$attachments_target"
        sha256sum "$attachments_target" | awk '{print $1}' > "${attachments_target}.sha256"
        printf 'files=%s\n' "$attachments_count" > "${attachments_target}.counts"

        log "Вложения: ${attachments_count} файлов, размер $(du -h "$attachments_target" | awk '{print $1}')"
    else
        log "ПРЕДУПРЕЖДЕНИЕ: каталог вложений ${ATTACHMENTS_DIR} не найден — файлы в копию не попали"
    fi
fi

# ---------------------------------------------------------------------------
#  Недельный и месячный наборы. cp -l создаёт жёсткую ссылку: файл один,
#  а в каталогах он числится отдельно, поэтому место не удваивается.
# ---------------------------------------------------------------------------
copy_into() {
    source_file="$1"
    destination_dir="$2"
    cp -l "$source_file" "$destination_dir/" 2>/dev/null \
        || cp "$source_file" "$destination_dir/"
}

# Копия — это дамп и архив вложений вместе. Разложенные по разным наборам
# по отдельности они бессмысленны: база без файлов и файлы без базы.
copy_pair() {
    destination_dir="$1"
    copy_into "$target" "$destination_dir"
    copy_into "${target}.sha256" "$destination_dir"
    copy_into "$counts_file" "$destination_dir"

    if [ -f "$attachments_target" ]; then
        copy_into "$attachments_target" "$destination_dir"
        copy_into "${attachments_target}.sha256" "$destination_dir"
        copy_into "${attachments_target}.counts" "$destination_dir"
    fi
}

day_of_week=$(date -u +%u)   # 1..7, 7 — воскресенье
day_of_month=$(date -u +%d)

if [ "$day_of_week" = "7" ]; then
    copy_pair "$BACKUP_DIR/weekly"
    log "Копия добавлена в недельный набор"
fi

if [ "$day_of_month" = "01" ]; then
    copy_pair "$BACKUP_DIR/monthly"
    log "Копия добавлена в месячный набор"
fi

# ---------------------------------------------------------------------------
#  Политика хранения: в каждом наборе остаётся не больше N последних копий.
#
#  Обходимся без `ls` и без `find -printf`: в alpine это busybox, где `-printf`
#  отсутствует, а разбор вывода `ls` ломается на необычных именах файлов.
#  Маска раскрывается по возрастанию имени, а имя имеет вид
#  notescout-20250601T030000Z.dump, поэтому первые (total - keep) элементов —
#  самые старые копии.
# ---------------------------------------------------------------------------
prune() {
    directory="$1"
    keep="$2"
    [ -d "$directory" ] || return 0

    total=0
    for candidate in "$directory"/notescout-*.dump; do
        [ -e "$candidate" ] || continue
        total=$((total + 1))
    done

    remove_count=$((total - keep))
    [ "$remove_count" -gt 0 ] || return 0

    index=0
    for candidate in "$directory"/notescout-*.dump; do
        [ -e "$candidate" ] || continue
        index=$((index + 1))
        [ "$index" -le "$remove_count" ] || break

        # Архив вложений носит тот же таймстамп, что и дамп, поэтому уходит
        # вместе с ним. Иначе каталог копий рос бы за счёт сирот.
        stamp=$(basename "$candidate" .dump)
        stamp=${stamp#notescout-}
        attachments_candidate="$directory/notescout-${stamp}-attachments.tar.gz"

        rm -f "$candidate" "${candidate}.sha256" "${candidate}.counts"
        rm -f "$attachments_candidate" "${attachments_candidate}.sha256" \
            "${attachments_candidate}.counts"
        log "Удалена устаревшая копия $(basename "$candidate")"

        # Из облака удаляем ровно те же копии. Без этого бакет рос бы без
        # предела: локально политика хранения работает, а в облаке оставалось
        # бы всё, что когда-либо выгружено.
        prune_remote "$directory" "$candidate" "$attachments_candidate"
    done
}

# Удаляет пару «дамп + вложения» из внешнего хранилища вслед за локальной.
prune_remote() {
    [ -n "$remote_path" ] || return 0

    directory="$1"
    dump_candidate="$2"
    attachments_candidate="$3"
    set_name=$(basename "$directory")

    for suffix in "" ".sha256" ".counts"; do
        rclone deletefile \
            "${remote_path}/${set_name}/$(basename "$dump_candidate")${suffix}" \
            >/dev/null 2>&1 || true
        rclone deletefile \
            "${remote_path}/${set_name}/$(basename "$attachments_candidate")${suffix}" \
            >/dev/null 2>&1 || true
    done
}

prune "$BACKUP_DIR/daily" "$RETENTION_DAILY"
prune "$BACKUP_DIR/weekly" "$RETENTION_WEEKLY"
prune "$BACKUP_DIR/monthly" "$RETENTION_MONTHLY"

# ---------------------------------------------------------------------------
#  Внешнее хранилище. Пока S3_REMOTE пуст — копия лежит только на этом сервере.
#
#  Настройки хранилища приходят переменными окружения (RCLONE_CONFIG_*),
#  поэтому `rclone config` запускать не нужно: см. docs/runbook-backup.md.
# ---------------------------------------------------------------------------

# Вспомогательные файлы (сумма, счётчики) выгружаем, но их отсутствие не
# срывает копирование: дамп важнее. Однако и молчать нельзя — потерянные молча
# счётчики означают, что проверять копию будет нечем.
upload_file() {
    source_file="$1"
    destination_dir="$2"
    [ -f "$source_file" ] || return 0
    rclone copy "$source_file" "$destination_dir/" \
        || log "ПРЕДУПРЕЖДЕНИЕ: не выгружен $(basename "$source_file")"
}

# Выгружает полный набор: дамп и архив вложений вместе с их суммами
# и счётчиками. Именно полный: копия без счётчиков не проверяется,
# а без контрольной суммы не проверяется на целостность.
upload_set() {
    destination_dir="$1"

    rclone copy "$target" "$destination_dir/" \
        || fail "не удалось выгрузить копию во внешнее хранилище"
    upload_file "${target}.sha256" "$destination_dir"
    upload_file "$counts_file" "$destination_dir"

    # Вложения выгружаем вместе с дампом: внешняя копия базы без файлов
    # не спасёт — восстановить из неё заметки можно, а вложения нет.
    if [ -f "$attachments_target" ]; then
        rclone copy "$attachments_target" "$destination_dir/" \
            || fail "не удалось выгрузить архив вложений во внешнее хранилище"
        upload_file "${attachments_target}.sha256" "$destination_dir"
        upload_file "${attachments_target}.counts" "$destination_dir"
    fi
}

if [ -n "$remote_path" ]; then
    log "Выгружаю копию в ${remote_path}"
    upload_set "${remote_path}/daily"

    if [ "$day_of_week" = "7" ]; then
        upload_set "${remote_path}/weekly"
        log "Копия выгружена в недельный набор"
    fi
    if [ "$day_of_month" = "01" ]; then
        upload_set "${remote_path}/monthly"
        log "Копия выгружена в месячный набор"
    fi
else
    log "Внешнее хранилище не настроено (S3_REMOTE пуст) — копия только на этом сервере"
fi

# Только здесь копия считается состоявшейся: до этой строки любой сбой
# приводит к удалению файлов обработчиком cleanup_partial.
completed=yes
log "Резервное копирование успешно завершено"
