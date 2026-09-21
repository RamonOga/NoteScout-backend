#!/bin/sh
# Обёртка для cron: подгружает сохранённое окружение и запускает бэкап.
set -eu

set -a
# shellcheck source=/dev/null
. /etc/notescout-backup.env
set +a

exec /usr/local/bin/backup.sh
