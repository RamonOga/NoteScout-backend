# Runbook: резервное копирование и восстановление

Документ для дежурного. Описывает, что и когда копируется, как проверить копию,
как восстановить данные и как переехать на новый сервер.

---

## Что и когда

| Параметр | Значение |
|---|---|
| Что копируется | вся база `notes` целиком (`pg_dump -Fc`) **и файлы вложений** (`attachments.tar.gz`) |
| Расписание | ежедневно в 03:00 по `TZ` контейнера (по умолчанию UTC), `BACKUP_CRON` |
| Где лежит | том `backups`, каталоги `/daily`, `/weekly`, `/monthly` |
| Ротация | 7 ежедневных, 4 недельных (по воскресеньям), 12 месячных (1-го числа) |
| Рядом с копией | `.sha256` — контрольная сумма, `.counts` — количество строк по таблицам (у архива вложений — количество файлов) |
| Внешнее хранилище | выключено, пока не заполнены `S3_REMOTE` и `S3_BUCKET` |

**Копия — это дамп и архив вложений вместе.** Файлы лежат в томе `attachments`,
а не в базе, поэтому дамп их не покрывает: без второго артефакта после
восстановления заметки вернулись бы без файлов. Оба архива носят один и тот же
таймстамп и уходят из ротации вместе.

**RPO** (сколько данных можно потерять) — до 24 часов.
**RTO** (сколько занимает восстановление) — 10–20 минут для базы до нескольких ГБ.

> **Сейчас копии лежат на том же сервере.** Это защищает от ошибок приложения,
> случайного удаления и порчи данных, но **не** от отказа диска или потери VPS.
> Первый же шаг для повышения надёжности — включить внешнее хранилище (см. ниже).

---

## Ежедневные проверки

```bash
cd /opt/notescout

# копия за сегодня есть?
docker compose exec backup ls -lh /backups/daily | tail -5

# последние строки журнала
docker compose logs --since 24h backup | tail -30
```

Признак проблемы — отсутствие файла `notescout-<сегодняшняя-дата>T030000Z.dump`.

---

## Ручное создание копии

Полезно перед миграцией, обновлением или рискованной операцией:

```bash
docker compose exec backup /usr/local/bin/backup.sh
```

Скрипт печатает размер копии и завершается с ненулевым кодом при ошибке.

Если копию снять не удалось, скрипт **удаляет за собой файлы**: неполный дамп,
контрольную сумму и счётчики. Это сделано намеренно — иначе обрывок выглядел бы
как рабочая копия, и следующая же проверка приняла бы его за чистую монету.
Поэтому в каталоге не бывает «почти копий»: либо все три файла, либо ни одного.

---

## Проверка копии (обязательно)

Резервная копия, которую ни разу не восстанавливали, копией не считается.
`verify-restore.sh` разворачивает последнюю копию в отдельную временную базу,
сверяет количество строк по таблицам с записанным при создании дампа и удаляет
временную базу.

```bash
# последняя ежедневная копия
docker compose exec backup /usr/local/bin/verify-restore.sh

# конкретный набор
docker compose exec backup /usr/local/bin/verify-restore.sh weekly
```

Успешный вывод:

```
[verify] Проверяю копию: notescout-20250601T030000Z.dump
[verify]   users: 42 строк — совпадает
[verify]   notes: 318 строк — совпадает
[verify] Проверка пройдена: сверено таблиц — 5, расхождений нет
```

Проверка падает с ненулевым кодом, если копия снята до появления схемы или
повреждена. Признак такого состояния — пустой файл `.counts`: сверять нечего,
и раньше это молча считалось успехом. Если вы видите

```
[verify] ПРОВЕРКА НЕ ПРОЙДЕНА: файл счётчиков ... пуст
```

значит, копия неполная — берите следующую по свежести.

Рекомендуется запускать раз в неделю по cron (см. `deploy.md`).

---

## Восстановление в текущую базу

> Операция **перезаписывает** боевые данные. Делайте её только осознанно и
> по возможности сохраните текущее состояние: `docker compose exec backup /usr/local/bin/backup.sh`.

### Шаг 1. Остановить API

Чтобы клиенты не писали в базу во время восстановления:

```bash
docker compose stop api
```

### Шаг 2. Выбрать копию

```bash
docker compose exec backup ls -lht /backups/daily
```

### Шаг 3. Восстановить

```bash
docker compose exec backup sh -c \
  'CONFIRM_RESTORE=yes /usr/local/bin/restore.sh /backups/daily/notescout-20250601T030000Z.dump'
```

Скрипт проверит контрольную сумму, при необходимости создаст базу и перезапишет
содержимое (`pg_restore --clean --if-exists`).

### Шаг 4. Вернуть файлы вложений

**Без этого шага заметки восстановятся без вложений.** Файлы лежат в томе
`attachments`, а не в базе, поэтому дамп их не содержит.

```bash
docker compose exec backup sh -c \
  'tar -xzf /backups/daily/notescout-20250601T030000Z-attachments.tar.gz \
     -C /var/lib/notescout/attachments'
```

Архив распаковывается поверх текущего содержимого. Если нужно начать с чистого
листа, сначала очистите каталог — но помните, что там могут быть вложения,
созданные после снятия копии:

```bash
docker compose exec backup sh -c 'rm -rf /var/lib/notescout/attachments/*'
```

### Шаг 5. Поднять API и проверить

```bash
docker compose start api
docker compose logs -f api          # Flyway должен написать "Schema is up to date"
curl -s https://<домен>/actuator/health | jq
```

Проверьте данные вручную — например, что заметки пользователей на месте:

```bash
docker compose exec postgres psql -U notescout -d notescout \
  -c 'select count(*) as notes from notes where deleted_at is null;'

# вложений в базе столько же, сколько файлов в хранилище?
docker compose exec postgres psql -U notescout -d notescout \
  -c 'select count(*) as attachments from attachments;'
docker compose exec backup sh -c 'find /var/lib/notescout/attachments -type f | wc -l'
```

---

## Проверка восстановления без остановки боевой базы

Безопасный вариант: развернуть копию в отдельную базу и посмотреть данные там.

```bash
docker compose exec backup sh -c \
  'CONFIRM_RESTORE=yes /usr/local/bin/restore.sh /backups/daily/notescout-20250601T030000Z.dump notescout_check'

docker compose exec postgres psql -U notescout -d notescout_check \
  -c 'select id, email, created_at from users order by created_at desc limit 5;'

# убрать за собой
docker compose exec postgres psql -U notescout -d postgres -c 'drop database notescout_check;'
```

---

## Переезд на новый сервер

1. **На старом сервере** — свежая копия и выгрузка наружу:

   ```bash
   docker compose exec backup /usr/local/bin/backup.sh
   docker compose exec backup ls -lh /backups/daily | tail -3
   ```

   Скопируйте дамп на новый сервер (или дождитесь его в S3):

   ```bash
   docker compose cp backup:/backups/daily/notescout-20250601T030000Z.dump /tmp/
   scp /tmp/notescout-20250601T030000Z.dump* newserver:/tmp/
   ```

2. **На новом сервере** — развернуть стек по [deploy.md](deploy.md), но
   **до** первого запуска API.

3. Поднять только базу:

   ```bash
   docker compose up -d postgres
   ```

4. Скопировать дамп в контейнер бэкапа и восстановить:

   ```bash
   docker compose cp /tmp/notescout-20250601T030000Z.dump backup:/backups/daily/
   docker compose cp /tmp/notescout-20250601T030000Z.dump.sha256 backup:/backups/daily/
   docker compose exec backup sh -c \
     'CONFIRM_RESTORE=yes /usr/local/bin/restore.sh /backups/daily/notescout-20250601T030000Z.dump'
   ```

5. Поднять остальное и проверить:

   ```bash
   docker compose up -d
   curl -s https://<новый-домен>/actuator/health | jq
   docker compose exec backup /usr/local/bin/verify-restore.sh
   ```

6. Переключить DNS на новый сервер. **Обязательно** возьмите с собой старый
   `JWT_SECRET`: иначе все выданные access-токены станут недействительными, а
   refresh-токены перестанут проходить — пользователям придётся войти заново.
   Пароли пользователей не пострадают: они хранятся в базе в виде хешей.

---

## Включение внешнего хранилища

Пока `S3_REMOTE` пуст, копии остаются только на сервере. Чтобы выгружать их
наружу, нужно настроить remote в rclone:

```bash
docker compose exec backup rclone config
```

Создайте remote типа `s3` (подходит MinIO, Yandex Object Storage, AWS S3,
Selectel и другие S3-совместимые сервисы). Затем в `.env`:

```dotenv
S3_REMOTE=s3
S3_BUCKET=notescout-backups
S3_PREFIX=notescout
AWS_ACCESS_KEY_ID=<ключ>
AWS_SECRET_ACCESS_KEY=<секрет>
```

```bash
docker compose up -d backup
docker compose exec backup /usr/local/bin/backup.sh
docker compose exec backup rclone ls s3:notescout-backups/NoteScout-backend/daily
```

Конфигурация rclone хранится внутри контейнера и будет потеряна при пересоздании.
Для постоянного хранения смонтируйте каталог конфигурации в `docker-compose.yml`:

```yaml
  backup:
    volumes:
      - backups:/backups
      - ./ops/backup/rclone:/root/.config/rclone
```

После включения внешнего хранилища имеет смысл пересмотреть ротацию: локальные
копии можно хранить меньше, а внешние — дольше.

---

## Что делать в конкретных ситуациях

| Ситуация | Действия |
|---|---|
| Бэкап не создался ночью | `docker compose logs backup`; проверить место (`df -h`); запустить `backup.sh` вручную |
| Дамп повреждён, `pg_restore --list` падает | взять копию из `/weekly` или `/monthly` |
| Копия есть, но `verify-restore` показывает расхождение | копия неполная — проверить следующую по свежести; если расходится всё, проблема в окружении: место на диске, права, версия клиента |
| Случайно удалили заметки | они удалены мягко: восстановить через `POST /api/v1/notes/{id}/restore`; если записей много — восстановить копию в отдельную базу и перенести данные |
| Испортили данные миграцией | остановить API, восстановить копию, снятую перед обновлением |
| Потерян `JWT_SECRET` | задать новый: пользователи просто войдут заново, данные не пострадают |
| Потерян пароль `POSTGRES_PASSWORD` | он есть в `/opt/notescout/.env`; если утерян и там — восстановить том нельзя, нужен дамп |

---

## Ограничения текущей схемы

1. Копии лежат на том же диске, что и данные — нет защиты от отказа сервера.
2. Нет уведомлений: об ошибке бэкапа видно только в логах контейнера
   (`docker compose logs backup`).
3. Нет point-in-time recovery: восстанавливаемся на момент ночного дампа,
   потеря — до суток.
4. Конфигурация rclone не переживает пересоздание контейнера без монтирования
   каталога.

Первые три пункта закрываются внешним хранилищем, алертами и переходом на
`pgBackRest`/WAL-G соответственно.
