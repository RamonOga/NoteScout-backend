# План разработки: NoteScout

Статус: **этапы 0–4 реализованы** (бэкенд и бэкапы). Мобильное приложение (этап 5) — следующий этап.
Дата: зафиксированы ответы заказчика по развилкам (см. раздел 1).

---

## 1. Согласованные решения

| Вопрос | Решение |
|---|---|
| Backend | **Kotlin + Spring Boot 3**, Java 21, Gradle Kotlin DSL |
| Мобильное приложение | **Flutter** (Android + iOS, одна кодовая база) |
| Развёртывание | **Один VPS + docker-compose** (api + postgres + TLS + backup) |
| Бэкапы | Пока **на тот же VPS**, внешнее S3-хранилище — отдельной доработкой (см. риск R1) |
| Объём MVP | Заметки с тегами + **ссылки как отдельный тип записи** |
| Вне MVP | Подтверждение email, сброс пароля, оффлайн-режим, вложения |
| Окружение разработки | Сборка и запуск — на машине заказчика; в этом окружении только код и документация |

Зафиксированные версии (проверим актуальность на Этапе 0): Java 21 LTS, Spring Boot 3.3.x,
Kotlin 2.x, PostgreSQL 16, Flutter 3.x, Caddy 2.

---

## 2. Что делает продукт (границы MVP)

Пользователь регистрируется по email и паролю, входит в приложение и получает доступ к своим записям.

1. **Регистрация / вход / выход**, обновление access-токена без повторного ввода пароля.
2. **Создание текстовой записи** — заголовок, текст, произвольный набор тегов.
3. **Создание записи-ссылки** — URL + заголовок, теги.
4. **Поиск по тегу** (один тег, несколько тегов, режимы «любой из» / «все сразу»).
5. Дополнительно: полнотекстовый поиск по заголовку и тексту, пагинация, редактирование,
   архивирование и мягкое удаление записей.

Явно **не входит** в MVP: шаринг записей, вложения, оффлайн-синхронизация, письма на email,
публичные ссылки. Модель данных и API проектируются так, чтобы это добавить без ломки схемы.

---

## 3. Архитектура

```
 ┌──────────────────────┐
 │  Flutter (Android/iOS)│
 └──────────┬───────────┘
            │ HTTPS, JWT Bearer
            v
 ┌──────────────────────────────────────────┐        VPS
 │  Caddy (TLS, reverse proxy) :443          │
 └──────────┬───────────────────────────────┘
            v
 ┌──────────────────────────┐      ┌────────────────────┐
 │  Spring Boot API :8080    │─────>│  PostgreSQL 16      │
 │  (stateless, JWT)         │ JDBC │  volume: pgdata     │
 └──────────┬───────────────┘      └─────────┬──────────┘
            │                                │ pg_dump -Fc (cron)
            │                                v
            │                      ┌────────────────────┐
            └──health/metrics─────>│ backup sidecar      │
                                   │ volume: backups     │
                                   └────────────────────┘
```

Ключевые свойства:

- API **stateless** — всё состояние в JWT и БД, бэкенд можно масштабировать копиями.
- Все запросы к данным фильтруются по `user_id` из токена — защита от IDOR.
- Секреты (пароль БД, JWT-ключ) — только через переменные окружения и `.env`, не в образе и не в git.
- Порт БД наружу не публикуется, только внутренняя docker-сеть.

---

## 4. Структура репозитория

```
NoteScout-backend/
├─ PLAN.md                  # этот файл
├─ README.md                # быстрый старт: как поднять локально и на VPS
├─ docker-compose.yml       # prod-профиль: api, postgres, caddy, backup
├─ docker-compose.dev.yml   # локальный профиль для разработки
├─ .env.example             # шаблон переменных окружения
├─ backend/
│  ├─ Dockerfile            # multi-stage: gradle build -> JRE 21 slim
│  ├─ build.gradle.kts, settings.gradle.kts, gradlew
│  └─ src/
│     ├─ main/kotlin/com/notescout/
│     │  ├─ NotesApplication.kt
│     │  ├─ common/         # ошибки, обработчик исключений, пагинация, аудит
│     │  ├─ security/       # JWT, фильтры, SecurityConfig, текущий пользователь
│     │  ├─ user/           # регистрация, профиль, репозиторий
│     │  ├─ auth/           # login, refresh, logout, refresh_tokens
│     │  ├─ note/           # заметки и ссылки: entity, service, controller, DTO
│     │  ├─ tag/            # теги: нормализация, CRUD, привязка к заметкам
│     │  └─ search/         # поиск по тегам и по тексту
│     ├─ main/resources/
│     │  ├─ application.yml, application-prod.yml
│     │  └─ db/migration/   # Flyway: V1__init.sql, V2__search.sql, ...
│     └─ test/kotlin/com/notescout/   # unit + интеграционные (Testcontainers)
├─ mobile/                  # проект Flutter
│  └─ lib/
│     ├─ main.dart
│     ├─ core/              # http-клиент, хранение токенов, тема, роутинг
│     └─ features/
│        ├─ auth/           # экраны входа и регистрации
│        ├─ notes/          # список, карточка, редактор заметки/ссылки
│        ├─ tags/           # список тегов, поиск по тегу
│        └─ search/         # строка поиска
├─ ops/
│  ├─ caddy/Caddyfile
│  └─ backup/backup.sh, restore.sh, crontab, README.md
├─ .github/workflows/ci.yml
└─ docs/
   ├─ api.md                # описание эндпоинтов + примеры curl
   ├─ deploy.md             # пошаговый деплой на VPS
   └─ runbook-backup.md     # как проверить бэкап и как восстановиться
```

---

## 5. Модель данных

Схема управляется Flyway, миграции — только вперёд, каждая проверяется на копии прод-дампа.

```sql
create table users (
    id            uuid primary key default gen_random_uuid(),
    email         varchar(255) not null,
    password_hash text         not null,           -- Argon2id
    display_name  varchar(100) not null,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);
create unique index users_email_lower_idx on users (lower(email));

create type note_type as enum ('TEXT', 'LINK');

create table notes (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid        not null references users (id) on delete cascade,
    type        note_type   not null default 'TEXT',
    title       varchar(255) not null,
    content     text,
    url         text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    archived_at timestamptz,
    deleted_at  timestamptz,
    constraint link_requires_url check (type <> 'LINK' or url is not null),
    -- полнотекстовый поиск: русский словарь + триграммы для частичных совпадений
    search_vector tsvector generated always as (
        setweight(to_tsvector('russian', coalesce(title, '')),   'A') ||
        setweight(to_tsvector('russian', coalesce(content, '')), 'B')
    ) stored
);
create index notes_user_created_idx on notes (user_id, created_at desc) where deleted_at is null;
create index notes_search_idx       on notes using gin (search_vector);

create table tags (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid         not null references users (id) on delete cascade,
    name            varchar(64)  not null,   -- как ввёл пользователь
    normalized_name varchar(64)  not null,   -- lower + trim, ключ уникальности
    created_at      timestamptz  not null default now()
);
create unique index tags_user_name_idx on tags (user_id, normalized_name);

create table note_tags (
    note_id uuid not null references notes (id) on delete cascade,
    tag_id  uuid not null references tags  (id) on delete cascade,
    primary key (note_id, tag_id)
);
create index note_tags_tag_idx on note_tags (tag_id);

create table refresh_tokens (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid        not null references users (id) on delete cascade,
    token_hash  text        not null,        -- хранится только SHA-256 от токена
    device_info varchar(255),
    expires_at  timestamptz not null,
    revoked_at  timestamptz,
    created_at  timestamptz not null default now()
);
create index refresh_tokens_user_idx on refresh_tokens (user_id);
```

Осознанные решения:

- **Мягкое удаление** (`deleted_at`) — меньше риска безвозвратной потери данных пользователем.
- **Теги приватны** (`tags.user_id`) — у разных пользователей свои наборы, никакого общего словаря.
- **`search_vector` — generated column** — индекс всегда согласован с данными, не нужны триггеры.
- `pg_trgm` подключаем для поиска по подстроке (когда пользователь вводит «половину» слова).

---

## 6. API

Базовый путь `/api/v1`, формат — JSON, аутентификация — `Authorization: Bearer <access_token>`.

### Аутентификация

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/auth/register` | Регистрация: email, password, displayName → токены |
| POST | `/auth/login` | Вход → access + refresh |
| POST | `/auth/refresh` | Обмен refresh-токена на новую пару (с ротацией) |
| POST | `/auth/logout` | Отзыв текущего refresh-токена |
| GET | `/users/me` | Профиль текущего пользователя |

### Заметки

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/notes` | Создать заметку или ссылку (теги передаются именами) |
| GET | `/notes?tag=&q=&page=&size=&type=` | Список с фильтрами и пагинацией |
| GET | `/notes/{id}` | Одна запись |
| PATCH | `/notes/{id}` | Частичное обновление (в т.ч. замена набора тегов) |
| DELETE | `/notes/{id}` | Мягкое удаление |
| POST | `/notes/{id}/restore` | Восстановление из удалённых |

### Теги и поиск

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/tags` | Теги пользователя с числом заметок |
| GET | `/search?q=&tags=работа,идеи&mode=any\|all` | Поиск по тексту и/или тегам |

Пример запроса:

```bash
curl -X POST https://api.example.com/api/v1/notes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"LINK","title":"Документация Spring","url":"https://spring.io","tags":["java","работа"]}'
```

Ошибки — единый формат (`code`, `message`, `details`), коды 400/401/403/404/409/429/500.
Спецификация OpenAPI отдаётся по `/swagger-ui.html` и служит контрактом для Flutter-клиента.

---

## 7. Безопасность

- Пароли — **Argon2id** (fallback BCrypt), минимальная длина 8, проверка на утечки не в MVP.
- **Access-токен** — 15 минут, **refresh** — 30 дней с ротацией: при обновлении старый отзывается,
  повторное использование отозванного токена инвалидирует всю цепочку.
- Хранение токенов на мобильном — `flutter_secure_storage` (Keychain / Keystore), не в SharedPreferences.
- **Rate limiting** на `/auth/*` (Bucket4j): защита от перебора пароля и массовых регистраций —
  это осознанный ответ на отказ от подтверждения email.
- Валидация всех входных данных (Bean Validation), параметризованные запросы (JPA) — защита от SQL-инъекций.
- Детали ошибок наружу не раскрываются: «неверный email или пароль» без уточнений.
- Логи — без паролей, токенов и содержимого заметок.
- HTTPS обязателен, HSTS, ограниченный CORS (мобильный клиент CORS не использует — оставляем закрытым).

---

## 8. Резервное копирование

На текущем этапе — **на тот же VPS**, с заложенной возможностью вынести во внешнее хранилище.

- Ночной `pg_dump -Fc` (сжатый формат, восстанавливается через `pg_restore`) + дамп перед каждой миграцией.
- Ротация: 7 ежедневных, 4 недельных, 12 месячных копий.
- Отдельный контейнер `backup` с cron, том `backups` на хосте.
- **Регулярная проверка восстановления**: `restore.sh` разворачивает последний дамп в отдельную
  временную БД, считает число записей и сравнивает с прод-БД. Бэкап, который ни разу не восстанавливали,
  бэкапом не считается.
- `docs/runbook-backup.md` — пошаговая инструкция «сервер упал, что делать», включая полный переезд на новый VPS.
- `backup.sh` пишет результат в лог, при ошибке ненулевой код выхода (дальше — алерт, вне MVP).

**Риск R1 (принят осознанно):** копия на том же диске не спасает от отказа диска или удаления сервера.
Скрипт сразу пишется с целевым каталогом, вынесенным в переменную, поэтому включение внешнего
S3-совместимого хранилища (rclone/`aws s3 cp`) — это добавление одной строки, без переписывания.

**Риск R2:** бесплатный/дешёвый VPS обычно имеет снапшоты на уровне гипервизора — их стоит включить
как вторую линию защиты независимо от `pg_dump`.

---

## 9. Этапы работ

Этапы идут последовательно; каждый заканчивается рабочим, проверяемым результатом.

### Этап 0. Каркас проекта — 1–2 дня
Структура репозитория, `docker-compose` (dev и prod), `Dockerfile` с multi-stage сборкой,
пустое Spring Boot приложение с `/health`, Flyway, CI (сборка + тесты), `README`.
**Готово, когда:** `docker compose up` поднимает API и Postgres, `/health` отвечает 200, CI зелёный.

### Этап 1. Домен и схема БД — 3–4 дня
Миграции V1/V2 (таблицы, индексы, `pg_trgm`), JPA-сущности, репозитории, базовые DTO и
обработчик ошибок, Testcontainers-инфраструктура для тестов.
**Готово, когда:** миграции применяются на чистой БД, интеграционный тест сохраняет и читает заметку с тегами.

### Этап 2. Регистрация и авторизация — 3–5 дней
`/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/users/me`, Spring Security,
JWT-фильтр, Argon2, ротация refresh-токенов, rate limiting на `/auth/*`.
**Готово, когда:** тесты покрывают успешный вход, неверный пароль, истёкший токен,
повторное использование отозванного refresh-токена и доступ к чужой заметке (403/404).

### Этап 3. Заметки, теги, поиск — 4–6 дней
CRUD заметок и ссылок, нормализация и привязка тегов, фильтр по тегам (`any`/`all`),
полнотекстовый поиск, пагинация, архивирование, мягкое удаление и восстановление.
**Готово, когда:** сценарий из ТЗ проходится целиком через curl: создать заметку с тегами →
найти её по тегу → отредактировать → удалить. OpenAPI-спецификация отдаётся и актуальна.

### Этап 4. Бэкапы — 2–3 дня
Контейнер `backup`, `backup.sh`, `restore.sh`, ротация, cron, проверка восстановления,
`docs/runbook-backup.md`.
**Готово, когда:** дамп создаётся по расписанию, `restore.sh` поднимает копию в отдельную БД,
число записей совпадает, инструкция проверена «с нуля» другим человеком.

### Этап 5. Мобильное приложение (Flutter) — 6–10 дней
Экраны: вход, регистрация, список заметок, редактор заметки/ссылки с тегами, список тегов,
поиск по тегу, профиль с выходом. Хранение токенов в secure storage, автообновление access-токена
при 401, состояния загрузки/ошибки/пустого списка.
**Готово, когда:** на Android-устройстве/эмуляторе полный сценарий проходится вручную без перезапуска,
сессия сохраняется между запусками приложения.

### Этап 6. Подготовка к проду — 2–4 дня
Caddy с автоматическим TLS, `docker-compose.prod.yml`, метрики и структурные логи,
ограничения ресурсов, `docs/deploy.md`, чек-лист бэкапа перед первым деплоем.
**Готово, когда:** приложение доступно по HTTPS на домен, перезагрузка VPS поднимает стек без ручных шагов.

**Итого: ~4–6 недель** для одного разработчика до работающего MVP.
Порядок 4 и 5 можно поменять местами, если нужно раньше показать мобильный клиент на мок-данных.

---

## 10. Риски и как их снимаем

| Риск | Влияние | Мера |
|---|---|---|
| Бэкап на том же VPS (R1) | Потеря данных при отказе диска | Внешнее S3 — следующая доработка; скрипт уже параметризован под неё |
| Отказ от подтверждения email | Спам-регистрации, «мёртвые» аккаунты | Rate limiting, лимиты на пользователя, email-верификация как задел на будущее |
| Полнотекстовый поиск по русскому языку | Плохое качество выдачи | Словарь `russian` + `pg_trgm`; при необходимости — внешний поисковик позже |
| JWT не отзывается до истечения срока | Окно 15 минут после кражи токена | Короткий TTL access-токена + отзыв refresh-токенов + plan на blacklist при необходимости |
| Рост объёма заметок | Деградация запросов | Правильные составные и GIN-индексы, keyset-пагинация как запасной вариант |
| Локальная среда не содержит JDK/Docker | Невозможно проверить сборку здесь | Сборка и запуск на машине заказчика; я поставляю код, тесты и точные команды |

---

## 11. Definition of Done для MVP

1. Стек поднимается одной командой `docker compose up -d` на чистом VPS.
2. Регистрация, вход, создание заметки и ссылки с тегами, поиск по тегу — работают из мобильного приложения.
3. Пользователь не имеет доступа к чужим данным (проверено тестами).
4. Ночной бэкап создаётся и **успешно восстанавливается** по инструкции.
5. Автотесты проходят в CI; OpenAPI-спецификация актуальна.
6. Есть `README.md`, `docs/deploy.md`, `docs/runbook-backup.md` — развёртывание воспроизводимо сторонним человеком.

---

## 12. Что нужно от заказчика перед стартом Этапа 1

- Домен (или согласие на тестовый) и доступ к VPS с установленным Docker.
- Решение по названию проекта, пакету и идентификатору приложения (сейчас предлагается
  `com.notescout`, пакет Flutter — `com.notescout.mobile`).
- Подтверждение, что Gradle-обёртка и сборка проверяются на машине разработки (в этом окружении нет JDK/Docker).

---

## 13. Статус реализации: этапы 0–4

| Этап | Что сделано | Где смотреть |
|---|---|---|
| 0. Каркас | Gradle-проект, Docker-образ (multi-stage), `docker-compose.yml` и `docker-compose.dev.yml`, Caddy, CI | `backend/build.gradle.kts`, `backend/Dockerfile`, `docker-compose*.yml`, `.github/workflows/ci.yml` |
| 1. Домен и схема | Flyway V1 (таблицы, индексы, ограничения) и V2 (`search_vector`, GIN, триграммы); JPA-сущности и репозитории | `backend/src/main/resources/db/migration/`, `com/notescout/{user,note,tag,auth}` |
| 2. Аутентификация | Регистрация, вход, refresh с ротацией, logout, logout-all, профиль; Argon2id; rate limiting | `com/notescout/auth`, `com/notescout/security` |
| 3. Заметки и поиск | CRUD заметок и ссылок, теги с нормализацией, фильтр `ANY`/`ALL`, полнотекстовый поиск, пагинация, архив, мягкое удаление и восстановление | `com/notescout/note`, `com/notescout/tag`, `com/notescout/search` |
| 4. Бэкапы | Контейнер с cron, `backup.sh` / `restore.sh` / `verify-restore.sh`, ротация, контрольные суммы, снимки счётчиков строк, задел под S3 | `ops/backup/`, `docs/runbook-backup.md` |

Дополнительно: интеграционные тесты на Testcontainers (аутентификация, изоляция данных,
поиск, пагинация, rate limit), `README.md`, `docs/api.md`, `docs/deploy.md`,
`docs/runbook-backup.md`.

### Отклонения от первоначального плана

1. **`note_type` — `varchar(16)` + `CHECK`, а не отдельный enum-тип Postgres.**
   Hibernate 6 требует отдельных аннотаций для нативных PG-enum, а в нативном поиске —
   явных приведений типов. Поведение то же, схема и маппинг проще.
2. **Счётчики rate limit — в памяти процесса**, как и планировалось для одного инстанса;
   при переходе на несколько копий API их нужно вынести в Redis.
3. **Swagger и OpenAPI в проде включены по умолчанию** — удобно для отладки мобильного
   клиента. Закрываются переменными `OPENAPI_ENABLED` / `SWAGGER_ENABLED` или правилом в Caddy.
4. **`ddl-auto: none`**, а не `validate`: схемой управляет Flyway. Переключение на `validate`
   имеет смысл после первого успешного запуска в продуктовой среде.

### Что пока не проверено

Код написан в окружении без JDK, Gradle и Docker, поэтому **сборка и тесты не запускались**.
Первая проверка — на машине разработки:

```bash
cd NoteScout-backend/backend
gradle wrapper --gradle-version 8.14.2
./gradlew build            # сборка + интеграционные тесты (нужен Docker)
```

Затем проверка стека целиком:

```bash
cd NoteScout-backend
docker compose -f docker-compose.dev.yml up -d --build
docker compose -f docker-compose.dev.yml exec backup /usr/local/bin/backup.sh
docker compose -f docker-compose.dev.yml exec backup /usr/local/bin/verify-restore.sh
```

Ожидаемые правки после первой сборки: версии зависимостей, мелкие расхождения в
маппинге сущностей и текстах SQL-запросов.
