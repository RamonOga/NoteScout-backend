# ============================================================================
#  Контейнер резервного копирования.
#
#  База — официальный образ postgres:16-alpine: в нём уже есть pg_dump,
#  pg_restore и psql ровно той версии, что и сервер. Это важно: восстанавливать
#  дамп клиентом другой мажорной версии нельзя.
# ============================================================================
FROM postgres:16-alpine

# rclone — для выгрузки копий во внешнее S3-совместимое хранилище.
# tzdata — чтобы расписание cron работало в нужной таймзоне.
RUN apk add --no-cache rclone tzdata

COPY entrypoint.sh cron-backup.sh backup.sh restore.sh verify-restore.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/*.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
