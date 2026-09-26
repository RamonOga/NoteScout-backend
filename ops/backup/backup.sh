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
#    5. выгружает набор во внешнее хранилище — daily всегда, weekly (вс)
#       и monthly (1-е число) дополнительно;
#    6. удаляет локальные файлы ТОЛЬКО после успешной выгрузки;
#    7. чистит устаревшие наборы в хранилище по политике хранения.
#
#  Локальных копий намеренно не остаётся. Диск сервера конечен и делится с
#  базой, а копия на том же диске от потери сервера не спасает — ровно за это
#  и отвечает внешнее хранилище. Если выгрузка не удалась, набор остаётся на
#  диске как последняя копия данных; о сбое сообщают отметка и healthcheck.
#
#  Хранилище обязательно: сохранять копии больше некуда.
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

# Путь во внешнем хранилище. Пустая строка означает «хранилище не настроено»,
# а это для нас отказ: копии живут только там.
remote_path=""
if [ -n "$S3_REMOTE" ] && [ -n "$S3_BUCKET" ]; then
    remote_path="${S3_REMOTE}:${S3_BUCKET}/${S3_PREFIX}"
fi

# Сколько наборов разрешено оставить на диске. Локальные файлы — не копии,
# а свидетельство неудачной выгрузки. Держим несколько: затяжной сбой не
# должен забить диск, но и терять данные нельзя.
LOCAL_SAFETY_SETS="${LOCAL_SAFETY_SETS:-3}"
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

# Без хранилища сохранять некуда, и это ошибка, а не «сделаю копию на диск».
# Молчаливая локальная копия выглядела бы как рабочий бэкап, не защищая ни от
# чего: она лежит на том же диске, что и база.
[ -n "$remote_path" ] \
    || fail "внешнее хранилище не настроено (S3_REMOTE/S3_BUCKET пусты) — сохранять копии некуда"

# Недельные и месячные наборы существуют только в хранилище: локально копий
# больше не остаётся, поэтому и каталогов под них не нужно.
mkdir -p "$BACKUP_DIR/daily"

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$BACKUP_DIR/daily/notescout-${timestamp}.dump"
tmp="${target}.part"
attachments_target="$BACKUP_DIR/daily/notescout-${timestamp}-attachments.tar.gz"
attachments_tmp="${attachments_target}.part"

# Неудачный бэкап не должен оставлять после себя файлы. Иначе, например, дамп
# без счётчиков выглядит как рабочая копия: раньше verify-restore.sh на пустом
# файле счётчиков «проходил» проверку, ничего не сверив.
completed=no
local_set_complete=no
cleanup_partial() {
    if [ "$completed" != "yes" ]; then
        if [ "$local_set_complete" = "yes" ]; then
            # Набор собран целиком, но выгрузить его не удалось. Удалять
            # нельзя: это последняя копия данных. О сбое сообщают отметка и
            # healthcheck, а файлы дождутся следующей попытки.
            log "Выгрузка не удалась — набор оставлен на диске как последняя копия"
        else
            rm -f "$target" "${target}.sha256" "${target}.counts" "$tmp"
            rm -f "$attachments_target" "${attachments_target}.sha256" \
                "${attachments_target}.counts" "$attachments_tmp"
            log "Копия неполная — файлы удалены, чтобы не выглядели как рабочая копия"
        fi

        # Отметка о сбое — по ней healthcheck контейнера видит проблему.
        # Без неё падение ночной копии остаётся только в логах: ровно так
        # пустой PGSSLMODE четыре дня подряд валил cron, и выяснилось это
        # случайно, при разговоре про хранилище.
        date -u +%Y-%m-%dT%H:%M:%SZ > "$BACKUP_DIR/last-failure" 2>/dev/null || true
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

# Набор собран целиком. С этого момента сбой означает «не выгрузилось», а не
# «копия битая», и локальные файлы нужно сохранить — см. cleanup_partial.
local_set_complete=yes

# ---------------------------------------------------------------------------
#  Выгрузка во внешнее хранилище.
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

    rclone copy "$target" "$destination_dir/" || return 1
    upload_file "${target}.sha256" "$destination_dir"
    upload_file "$counts_file" "$destination_dir"

    # Вложения выгружаем вместе с дампом: копия базы без файлов не спасёт —
    # восстановить из неё заметки можно, а вложения нет.
    if [ -f "$attachments_target" ]; then
        rclone copy "$attachments_target" "$destination_dir/" || return 1
        upload_file "${attachments_target}.sha256" "$destination_dir"
        upload_file "${attachments_target}.counts" "$destination_dir"
    fi
    return 0
}

# Удаляет локальные файлы текущего набора: они были нужны, чтобы собрать
# и выгрузить копию. Вызывается только после успешной выгрузки.
remove_local_set() {
    rm -f "$target" "${target}.sha256" "$counts_file" "$tmp"
    rm -f "$attachments_target" "${attachments_target}.sha256" \
        "${attachments_target}.counts" "$attachments_tmp"
}

# Политика хранения в хранилище: в наборе остаётся не больше N последних копий.
#
# Перечень берём у rclone: локального списка копий больше нет, а имя содержит
# UTC-таймстамп фиксированной ширины, поэтому при LC_ALL=C сортировка по имени
# совпадает с хронологической — на этом и построен отбор.
prune_remote_set() {
    set_name="$1"
    keep="$2"

    dumps=$(rclone lsf "${remote_path}/${set_name}/" 2>/dev/null \
        | grep '\.dump$' | sort)
    total=$(printf '%s\n' "$dumps" | grep -c . || true)
    remove_count=$((total - keep))
    [ "$remove_count" -gt 0 ] || return 0

    printf '%s\n' "$dumps" | head -n "$remove_count" | while IFS= read -r name; do
        [ -n "$name" ] || continue
        stamp=$(printf '%s' "$name" | sed 's/^notescout-//; s/\.dump$//')

        # Архив вложений носит тот же таймстамп, что и дамп, поэтому уходит
        # вместе с ним. Иначе в наборе копились бы сироты.
        for candidate in \
            "notescout-${stamp}.dump" \
            "notescout-${stamp}.dump.sha256" \
            "notescout-${stamp}.dump.counts" \
            "notescout-${stamp}-attachments.tar.gz" \
            "notescout-${stamp}-attachments.tar.gz.sha256" \
            "notescout-${stamp}-attachments.tar.gz.counts"
        do
            rclone deletefile "${remote_path}/${set_name}/${candidate}" \
                >/dev/null 2>&1 || true
        done
        log "Удалена устаревшая копия из хранилища: ${name}"
    done
}

# Страховка на случай затяжного сбоя выгрузки: если наборы всё-таки копятся
# на диске, оставляем только несколько последних.
prune_local_safety() {
    total=0
    for candidate in "$BACKUP_DIR/daily"/notescout-*.dump; do
        [ -e "$candidate" ] || continue
        total=$((total + 1))
    done
    remove_count=$((total - LOCAL_SAFETY_SETS))
    [ "$remove_count" -gt 0 ] || return 0

    index=0
    for candidate in "$BACKUP_DIR/daily"/notescout-*.dump; do
        [ -e "$candidate" ] || continue
        index=$((index + 1))
        [ "$index" -le "$remove_count" ] || break

        stamp=$(basename "$candidate" .dump)
        stamp=${stamp#notescout-}
        rm -f "$candidate" "${candidate}.sha256" "${candidate}.counts"
        rm -f "$BACKUP_DIR/daily/notescout-${stamp}-attachments.tar.gz" \
            "$BACKUP_DIR/daily/notescout-${stamp}-attachments.tar.gz.sha256" \
            "$BACKUP_DIR/daily/notescout-${stamp}-attachments.tar.gz.counts"
        log "Удалён локальный остаток $(basename "$candidate")"
    done
}

day_of_week=$(date -u +%u)   # 1..7, 7 — воскресенье
day_of_month=$(date -u +%d)

log "Выгружаю копию в ${remote_path}"
upload_set "${remote_path}/daily" \
    || fail "не удалось выгрузить копию в хранилище"

if [ "$day_of_week" = "7" ]; then
    upload_set "${remote_path}/weekly" \
        || fail "не удалось выгрузить недельную копию"
    log "Копия добавлена в недельный набор"
fi

if [ "$day_of_month" = "01" ]; then
    upload_set "${remote_path}/monthly" \
        || fail "не удалось выгрузить месячную копию"
    log "Копия добавлена в месячный набор"
fi

prune_remote_set "daily" "$RETENTION_DAILY"
prune_remote_set "weekly" "$RETENTION_WEEKLY"
prune_remote_set "monthly" "$RETENTION_MONTHLY"

remove_local_set
log "Локальные файлы удалены: копия хранится в хранилище"

prune_local_safety

# Только здесь копия считается состоявшейся: до этой строки любой сбой
# приводит к удалению файлов обработчиком cleanup_partial (или к их сохранению,
# если набор был собран целиком).
completed=yes

# Отметки для healthcheck: успех снимает отметку о сбое и ставит свою.
rm -f "$BACKUP_DIR/last-failure"
date -u +%Y-%m-%dT%H:%M:%SZ > "$BACKUP_DIR/last-success" 2>/dev/null || true

log "Резервное копирование успешно завершено"
