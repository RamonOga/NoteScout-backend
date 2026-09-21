#!/bin/sh
# ============================================================================
#  Точка входа контейнера backup.
#
#  Готовит каталоги, переносит переменные окружения в файл (busybox crond
#  запускает задания с урезанным окружением) и запускает планировщик.
# ============================================================================
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_CRON="${BACKUP_CRON:-0 3 * * *}"
ENV_FILE="/etc/notescout-backup.env"
CRONTAB_FILE="/etc/crontabs/root"

mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"

# ---------------------------------------------------------------------------
#  cron не наследует окружение контейнера, поэтому сохраняем нужные переменные
#  в файл. Значения экранируются одинарными кавычками — пароль со спецсимволами
#  не сломает разбор.
# ---------------------------------------------------------------------------
umask 077
: > "$ENV_FILE"
for name in PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGSSLMODE \
            BACKUP_DIR RETENTION_DAILY RETENTION_WEEKLY RETENTION_MONTHLY TZ \
            S3_REMOTE S3_BUCKET S3_PREFIX AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
    value=""
    eval "value=\${$name:-}"
    escaped=$(printf '%s' "$value" | sed "s/'/'\\\\''/g")
    printf "%s='%s'\n" "$name" "$escaped" >> "$ENV_FILE"
done
chmod 600 "$ENV_FILE"
umask 022

printf '%s /usr/local/bin/cron-backup.sh >> /proc/1/fd/1 2>&1\n' "$BACKUP_CRON" > "$CRONTAB_FILE"
chmod 600 "$CRONTAB_FILE"

echo "[backup] Планировщик запущен: расписание '$BACKUP_CRON', таймзона ${TZ:-UTC}, каталог $BACKUP_DIR"

if [ "${BACKUP_ON_START:-false}" = "true" ]; then
    echo "[backup] BACKUP_ON_START=true — создаю копию сразу при запуске"
    /usr/local/bin/backup.sh || echo "[backup] Копия при запуске завершилась с ошибкой" >&2
fi

exec crond -f -l 8 -c /etc/crontabs
