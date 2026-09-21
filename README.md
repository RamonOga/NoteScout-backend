# NoteScout — бэкенд

API для приложения заметок и ссылок: регистрация и вход по JWT, текстовые записи и
ссылки с тегами, поиск по тегам и по тексту, резервное копирование с проверкой
восстановления.

**Текущее состояние:** реализованы этапы 0–4 плана ([PLAN.md](PLAN.md)) — бэкенд и
система бэкапов. Мобильное приложение на Flutter (этап 5) — следующий этап.

---

## Стек

| Компонент | Версия | Зачем |
|---|---|---|
| Kotlin | 2.1.21 | язык бэкенда |
| Spring Boot | 3.5.3 | web, security, data-jpa, validation, actuator |
| Java | 21 (LTS) | среда выполнения |
| PostgreSQL | 16 | данные + полнотекстовый поиск (`tsvector`) и триграммы |
| Flyway | из BOM Spring Boot | версионирование схемы |
| jjwt | 0.12.6 | access-токены |
| Bucket4j | 8.10.1 | ограничение частоты запросов |
| springdoc-openapi | 2.8.6 | OpenAPI/Swagger |
| Testcontainers | из BOM Spring Boot | интеграционные тесты на реальном Postgres |
| Caddy | 2 | TLS и reverse proxy |

---

## Структура репозитория

```
NoteScout-backend/
├─ docker-compose.yml          # прод: api + postgres + caddy + backup
├─ docker-compose.dev.yml      # разработка: postgres + api
├─ .env.example                # шаблон переменных окружения
├─ backend/
│  ├─ src/main/kotlin/com/notescout/
│  │  ├─ auth/        регистрация, вход, refresh, logout, refresh_tokens
│  │  ├─ user/        пользователь и профиль
│  │  ├─ note/        заметки и ссылки, нативный поиск
│  │  ├─ tag/         теги: нормализация, upsert, счётчики
│  │  ├─ search/      отдельный эндпоинт поиска
│  │  ├─ security/    JWT, SecurityConfig, rate limit
│  │  ├─ config/      свойства приложения, Clock, OpenAPI
│  │  └─ common/      единый формат ошибок, пагинация
│  ├─ src/main/resources/db/migration/   V1 схема, V2 поиск
│  └─ src/test/kotlin/                   интеграционные тесты
├─ ops/
│  ├─ backup/         backup.sh, restore.sh, verify-restore.sh, cron
│  └─ caddy/Caddyfile
└─ docs/
   ├─ api.md               описание эндпоинтов и примеры curl
   ├─ deploy.md            развёртывание на VPS
   └─ runbook-backup.md    бэкап, восстановление, переезд
```

---

## Быстрый старт (разработка)

Нужен только Docker:

```bash
cd NoteScout-backend
docker compose -f docker-compose.dev.yml up -d --build
```

* API — <http://localhost:8080>
* Swagger UI — <http://localhost:8080/swagger-ui.html>
* PostgreSQL — `localhost:5432`, база/пользователь/пароль: `notescout`/`notescout`/`notescout`

Проверка, что всё поднялось:

```bash
curl -s http://localhost:8080/actuator/health | jq
```

### Запуск API из IDE

Поднимите только базу:

```bash
docker compose -f docker-compose.dev.yml up -d postgres
```

Затем в `backend/`:

```bash
gradle wrapper --gradle-version 8.14.2   # один раз: создаёт ./gradlew
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Профиль `dev` подставляет локальный секрет JWT, поэтому дополнительных
переменных окружения не требуется.

### Тесты

Интеграционные тесты поднимают PostgreSQL через Testcontainers, поэтому нужен
запущенный Docker:

```bash
cd backend
./gradlew test
```

Тесты проверяют не моки, а реальные SQL-запросы: нативный поиск по тегам и тексту,
`insert ... on conflict do nothing` для тегов, генерируемую колонку `search_vector`,
изоляцию данных между пользователями и ротацию refresh-токенов.

### Проверка бэкапов локально

В dev-окружении есть сервис `backup`, поэтому весь цикл проверяется до выхода на прод:

```bash
# создать копию вручную
docker compose -f docker-compose.dev.yml exec backup /usr/local/bin/backup.sh

# убедиться, что она восстанавливается и содержит те же данные
docker compose -f docker-compose.dev.yml exec backup /usr/local/bin/verify-restore.sh

# посмотреть, что лежит в каталоге копий
docker compose -f docker-compose.dev.yml exec backup ls -lh /backups/daily
```

---

## Продуктовый запуск

Кратко:

```bash
cp .env.example .env
# заполнить DOMAIN, POSTGRES_PASSWORD и JWT_SECRET (openssl rand -base64 48)
docker compose up -d --build
```

Подробная инструкция, включая DNS, firewall и проверку после деплоя, —
[docs/deploy.md](docs/deploy.md).

---

## Переменные окружения

| Переменная | Обяз. | По умолчанию | Назначение |
|---|---|---|---|
| `DOMAIN` | да | — | домен для Caddy и сертификата Let's Encrypt |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | да | — | доступ к базе |
| `JWT_SECRET` | да | — | ключ подписи, минимум 32 байта. Без него прод не стартует |
| `JWT_ACCESS_TTL` | нет | `15m` | время жизни access-токена |
| `JWT_REFRESH_TTL` | нет | `30d` | время жизни refresh-токена |
| `RATE_LIMIT_ENABLED` | нет | `true` | ограничение запросов к `/api/v1/auth/*` |
| `RATE_LIMIT_AUTH_CAPACITY` | нет | `10` | запросов с одного IP за окно |
| `RATE_LIMIT_AUTH_WINDOW` | нет | `1m` | размер окна |
| `BACKUP_CRON` | нет | `0 3 * * *` | расписание резервного копирования |
| `BACKUP_ON_START` | нет | `false` | сделать копию сразу при старте контейнера |
| `RETENTION_DAILY` / `RETENTION_WEEKLY` / `RETENTION_MONTHLY` | нет | `7`/`4`/`12` | сколько копий хранить |
| `S3_REMOTE` / `S3_BUCKET` / `S3_PREFIX` | нет | — | внешнее хранилище копий (пока не используется) |
| `OPENAPI_ENABLED` / `SWAGGER_ENABLED` | нет | `true` | закрыть документацию в проде |

---

## Как устроена безопасность

* Пароли — **Argon2id**. Ответ на «нет пользователя» и «неверный пароль» одинаков
  и по тексту, и по времени (выполняется холостая проверка хеша).
* **Access-токен** живёт 15 минут. **Refresh-токен** — 30 дней с ротацией:
  при обновлении старый отзывается. Предъявление уже отозванного токена трактуется
  как кража и отзывает все сессии пользователя.
* В базе хранится только SHA-256 от refresh-токена — дамп не даёт войти в аккаунт.
* Заметка другого пользователя отвечает **404**, а не 403: перебором нельзя
  выяснить, какие идентификаторы существуют.
* Все запросы к данным фильтруются по `user_id` из токена.
* `/actuator/*` закрыт на уровне Caddy, кроме `/actuator/health`.
* Секрет JWT проверяется при старте: приложение не поднимется с пустым или
  коротким ключом.

---

## Резервное копирование

* Ночной `pg_dump -Fc` в контейнере `backup`, ротация 7 дней / 4 недели / 12 месяцев.
* Каждая копия проверяется на читаемость и снабжается контрольной суммой и
  снимком количества строк по таблицам.
* `verify-restore.sh` восстанавливает последнюю копию во временную базу и сверяет
  количество строк — резервная копия считается рабочей только после успешной проверки.

Инструкции: [docs/runbook-backup.md](docs/runbook-backup.md).

> **Важно.** Сейчас копии лежат на том же сервере. Это защищает от ошибок
> приложения и порчи данных, но не от отказа диска. Скрипт уже умеет выгружать
> копии в S3-совместимое хранилище — достаточно заполнить `S3_REMOTE` и `S3_BUCKET`.

---

## Что дальше

1. Мобильное приложение на Flutter (этап 5 плана).
2. Внешнее хранилище для резервных копий.
3. Подтверждение email и сброс пароля.
4. Метрики Prometheus + алерты о неудачном бэкапе.
