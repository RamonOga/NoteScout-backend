# API NoteScout

Базовый URL: `https://<ваш-домен>/api/v1`
Формат: JSON, кодировка UTF-8.
Живая спецификация: `/swagger-ui.html`, JSON — `/v3/api-docs`.

Все эндпоинты, кроме `/auth/register`, `/auth/login` и `/auth/refresh`, требуют
заголовок `Authorization: Bearer <access_token>`.

## Формат ошибок

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Запрос не прошёл валидацию",
  "details": [
    { "field": "email", "message": "Некорректный email" }
  ],
  "timestamp": "2025-06-01T12:00:00Z"
}
```

| HTTP | `code` | Когда |
|---|---|---|
| 400 | `VALIDATION_ERROR` | не прошла валидация полей |
| 400 | `BAD_REQUEST` | некорректные данные, например ссылка без `url` |
| 400 | `MALFORMED_REQUEST` | нечитаемый JSON или неизвестный тип записи |
| 401 | `UNAUTHORIZED` | нет/просрочен токен, неверный пароль |
| 403 | `FORBIDDEN` | недостаточно прав |
| 404 | `NOT_FOUND` | объекта нет **или** он принадлежит другому пользователю |
| 409 | `CONFLICT` | email уже занят |
| 429 | `RATE_LIMIT_EXCEEDED` | превышен лимит на `/auth/*` |
| 500 | `INTERNAL_ERROR` | внутренняя ошибка |

---

## Аутентификация

### POST /auth/register

```bash
curl -X POST https://api.example.com/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"ivan@example.com","password":"secret-password","displayName":"Иван"}'
```

`201 Created`

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "id": "0f8fad5b-d9cb-469f-a165-70867728950e",
    "email": "ivan@example.com",
    "displayName": "Иван",
    "createdAt": "2025-06-01T12:00:00Z"
  }
}
```

Ограничения: `email` — валидный и не длиннее 255 символов, `password` — от 8 до 128,
`displayName` — не пустой, до 100 символов. Email приводится к нижнему регистру.
Повторная регистрация того же адреса (в любом регистре) — `409`.

### POST /auth/login

```bash
curl -X POST https://api.example.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ivan@example.com","password":"secret-password"}'
```

Ответ такой же, как у регистрации. Неверный пароль и несуществующий email дают
одинаковый ответ `401` с текстом «Неверный email или пароль».

### POST /auth/refresh

```bash
curl -X POST https://api.example.com/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"}'
```

Возвращает **новую пару** токенов; переданный refresh-токен при этом отзывается.

> Повторное использование уже отозванного токена считается признаком кражи:
> сервер отзывает **все** сессии пользователя, и войти заново можно только по паролю.

### POST /auth/logout

```bash
curl -X POST https://api.example.com/api/v1/auth/logout \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"}'
```

`204 No Content`. Идемпотентен: неизвестный токен не считается ошибкой.

### POST /auth/logout-all

Выход со всех устройств. Требует access-токен. `204 No Content`.

### GET /users/me

```bash
curl https://api.example.com/api/v1/users/me -H "Authorization: Bearer $TOKEN"
```

---

## Заметки

### POST /notes

Создать текстовую заметку:

```bash
curl -X POST https://api.example.com/api/v1/notes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Планы на неделю","content":"Закончить план разработки","tags":["работа","идеи"]}'
```

Создать ссылку:

```bash
curl -X POST https://api.example.com/api/v1/notes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"LINK","title":"Документация Spring","url":"https://spring.io","tags":["java"]}'
```

Поля:

| Поле | Обяз. | Ограничения |
|---|---|---|
| `type` | нет | `TEXT` (по умолчанию) или `LINK` |
| `title` | да | не пустой, до 255 символов |
| `content` | нет | до 100 000 символов |
| `url` | для `LINK` | до 2048 символов |
| `tags` | нет | до 20 тегов, каждый до 64 символов |

`201 Created`

```json
{
  "id": "8c1f1c2e-6a3e-4f6b-9a1e-2b3c4d5e6f70",
  "type": "LINK",
  "title": "Документация Spring",
  "url": "https://spring.io",
  "tags": ["java"],
  "createdAt": "2025-06-01T12:00:00Z",
  "updatedAt": "2025-06-01T12:00:00Z"
}
```

Теги нормализуются: обрезаются пробелы по краям, регистр приводится к нижнему,
повторные пробелы схлопываются. `"Работа"`, `"работа "` и `"РАБОТА"` — один тег,
отображается написание из первого создания.

### GET /notes

```bash
curl -G https://api.example.com/api/v1/notes \
  -H "Authorization: Bearer $TOKEN" \
  -d 'tag=работа' -d 'tag=java' -d 'tagsMode=ALL' -d 'q=докум' -d 'page=0' -d 'size=20'
```

| Параметр | По умолчанию | Значение |
|---|---|---|
| `q` | — | полнотекстовый поиск по заголовку и тексту + подстрока в заголовке |
| `tag` | — | можно повторять: `?tag=работа&tag=java` |
| `tagsMode` | `ANY` | `ANY` — хотя бы один тег, `ALL` — все теги одновременно |
| `type` | — | `TEXT` или `LINK` |
| `includeArchived` | `false` | включать записи из архива |
| `deletedOnly` | `false` | вернуть только удалённые — корзина. Режимы взаимоисключающие: в корзине `includeArchived` не учитывается, потому что удалённую заметку нужно вернуть независимо от того, лежала ли она в архиве |
| `page` | `0` | номер страницы с нуля |
| `size` | `20` | от 1 до 100 |

```json
{
  "items": [ { "id": "...", "type": "TEXT", "title": "...", "tags": ["java"] } ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3,
  "hasNext": true
}
```

Сортировка: сначала наиболее релевантные запросу, затем по времени изменения.
В корзине это даёт порядок «сначала недавно удалённые»: мягкое удаление
выставляет `updatedAt` в момент удаления.

Корзина:

```bash
curl -G https://api.example.com/api/v1/notes \
  -H "Authorization: Bearer $TOKEN" -d 'deletedOnly=true'
```

Удалённые записи приходят с заполненным `deletedAt`; у активных заметок поле
в ответе отсутствует.

### GET /notes/{id}

`200 OK` или `404 NOT_FOUND`.

### PATCH /notes/{id}

Частичное обновление: меняются только переданные поля.

```bash
curl -X PATCH https://api.example.com/api/v1/notes/$NOTE_ID \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Новый заголовок","tags":["работа"],"archived":false}'
```

* `tags` заменяет набор целиком, `[]` снимает все теги.
* `archived: true` отправляет в архив, `false` возвращает из архива.
* Чтобы **очистить** `content` или `url`, передайте пустую строку `""`.
  `null` и отсутствие поля означают «не менять».

### DELETE /notes/{id}

Мягкое удаление, `204 No Content`. Запись остаётся в базе и может быть восстановлена.

### POST /notes/{id}/restore

Возвращает удалённую заметку. Если она не была удалена — просто отдаёт её.

---

## Вложения

Файлы, приложенные к заметке. Хранятся не в базе: в таблице только описание и
ключ, по которому файл лежит в хранилище (том на сервере).

Пределы задаются настройками: `app.attachments.max-file-size` (по умолчанию
10 МБ на файл) и `app.attachments.max-total-per-user` (по умолчанию 1 ГБ на
пользователя). Превышение любого из них — `413 PAYLOAD_TOO_LARGE`.

### POST /notes/{id}/attachments

`multipart/form-data`, поле `file`. Отвечает `201` и описанием вложения.

```bash
curl -X POST https://api.example.com/api/v1/notes/$NOTE_ID/attachments \
  -H "Authorization: Bearer $TOKEN" \
  -F 'file=@photo.jpg'
```

```json
{
  "id": "…",
  "fileName": "photo.jpg",
  "contentType": "image/jpeg",
  "sizeBytes": 184320,
  "createdAt": "2026-09-22T10:00:00Z"
}
```

Заметка чужая или удалённая — `404`. Файл пустой — `400`.

### GET /notes/{id}/attachments

Список вложений заметки, от старых к новым.

### GET /attachments/{id}/content

Отдаёт файл потоком. Требует заголовка `Authorization` — **публичной ссылки у
файла нет**, поэтому клиенту нужно скачивать байты самому, а не подставлять
адрес в `Image.network`.

Ответ содержит `Content-Disposition: attachment; filename*=UTF-8''…`, так что
русские имена файлов не превращаются в кракозябры.

### DELETE /attachments/{id}

`204 No Content`. Файл удаляется после фиксации транзакции: если она
откатится, строка останется и файл обязан остаться вместе с ней.

### GET /attachments/usage

```json
{ "usedBytes": 184320, "limitBytes": 1073741824 }
```

---

## Теги

### GET /tags

```bash
curl https://api.example.com/api/v1/tags -H "Authorization: Bearer $TOKEN"
```

```json
[
  { "id": "…", "name": "java", "noteCount": 5 },
  { "id": "…", "name": "работа", "noteCount": 2 }
]
```

`noteCount` считает только активные заметки — без удалённых и архивных.
Теги приватны: у каждого пользователя свой набор.

---

## Поиск

### GET /search

```bash
curl -G https://api.example.com/api/v1/search \
  -H "Authorization: Bearer $TOKEN" \
  -d 'q=документация' -d 'tags=java,kotlin' -d 'mode=any' -d 'page=0' -d 'size=20'
```

| Параметр | По умолчанию | Значение |
|---|---|---|
| `q` | — | строка поиска |
| `tags` | — | список через запятую: `tags=java,kotlin` |
| `mode` | `ANY` | `ANY` или `ALL` |
| `includeArchived` | `false` | включать архив |
| `page` / `size` | `0` / `20` | пагинация |

Отличие от `GET /notes` только в форме передачи тегов (через запятую) — удобно
для строки поиска в мобильном клиенте. Формат ответа идентичен.

---

## Служебные эндпоинты

| Путь | Доступ | Назначение |
|---|---|---|
| `/actuator/health` | публично | liveness/readiness, используется docker healthcheck |
| `/actuator/health/liveness`, `/readiness` | публично | раздельные проверки |
| `/actuator/prometheus` | только внутри сети | метрики; наружу закрыт в Caddy |
| `/swagger-ui.html`, `/v3/api-docs` | публично | документация; можно закрыть в проде |

---

## Пример полного сценария

```bash
API=https://api.example.com/api/v1

# 1. регистрация
TOKEN=$(curl -s -X POST $API/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"ivan@example.com","password":"secret-password","displayName":"Иван"}' \
  | jq -r .accessToken)

# 2. заметка с тегами
NOTE_ID=$(curl -s -X POST $API/notes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Документация Spring","content":"описание фреймворка","tags":["java","работа"]}' \
  | jq -r .id)

# 3. поиск по тегу
curl -s -G $API/notes -H "Authorization: Bearer $TOKEN" -d 'tag=java' | jq '.totalElements'

# 4. поиск по тексту
curl -s -G $API/search -H "Authorization: Bearer $TOKEN" -d 'q=фреймворк' | jq '.items[0].title'

# 5. изменение и архивирование
curl -s -X PATCH $API/notes/$NOTE_ID \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"archived":true}' | jq .archivedAt

# 6. удаление и восстановление
curl -s -X DELETE $API/notes/$NOTE_ID -H "Authorization: Bearer $TOKEN" -o /dev/null -w '%{http_code}\n'
curl -s -X POST $API/notes/$NOTE_ID/restore -H "Authorization: Bearer $TOKEN" | jq .title
```
