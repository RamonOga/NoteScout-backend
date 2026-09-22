# Развёртывание на VPS

Пошаговая инструкция для одного сервера. Расчётное время — 30–40 минут, из них
большая часть уходит на выпуск TLS-сертификата.

## Что понадобится

* VPS: **2 vCPU, 2 ГБ RAM, 20+ ГБ SSD** (Ubuntu 22.04 или 24.04 LTS).
  Одновременно работают 4 контейнера: PostgreSQL, API, Caddy, backup.
* Домен, A-запись которого указывает на IP сервера. Без домена Let's Encrypt
  не выпустит сертификат.
* Открытые порты **80** и **443** (80 нужен для проверки домена и редиректа).

## Если порт 80 занят или домена ещё нет

Схема выше рассчитывает, что порты 80 и 443 целиком принадлежат NoteScout. Так
бывает не всегда: например, на сервере уже работает Zabbix, у которого nginx
занимает 80. Для этого случая есть два режима.

### Режим «порт 80 занят, домен есть»

Caddy работает только на 443, а сертификат получает проверкой **TLS-ALPN-01**:
она проходит внутри TLS-рукопожатия на 443 и порт 80 не использует вовсе.
В `ops/caddy/Caddyfile` это уже подготовлено — глобальная опция
`auto_https disable_redirects` и `disable_http_challenge` в блоке `tls`.
В `docker-compose.yml` при этом публикуется только `"443:443"`.

Домен не обязательно покупать: [duckdns.org](https://www.duckdns.org) бесплатно
выдаёт имя вида `имя.duckdns.org`, которое вы направляете на IP сервера,
и Let's Encrypt для таких имён сертификаты выпускает.

Не забыть:

* открыть 443 в UFW: `sudo ufw allow 443/tcp`;
* открыть 443 в панели хостера, если там есть свой фильтр — UFW это только
  половина дела;
* убедиться, что Caddy не занял 80: `sudo ss -tlnp | grep :80` должен
  по-прежнему показывать только прежний процесс.

Когда появится собственный домен и порт 80 освободится — верните `"80:80"`
в `docker-compose.yml` и раскомментируйте блок `http://{$DOMAIN}` в Caddyfile,
тогда заработает перенаправление на HTTPS.

### Режим «совсем без TLS»

Если домена нет и TLS не нужен — например, чтобы просто проверить, что стек
поднимается и миграции накатываются, — Caddy можно не запускать:

```bash
docker compose up -d postgres api backup
```

API окажется доступен только внутри docker-сети. Чтобы дотянуться до него
снаружи, добавьте сервису `api` публикацию порта на loopback:

```yaml
    ports:
      - "127.0.0.1:8080:8080"
```

и ходите через SSH-туннель:

```bash
ssh -L 8080:127.0.0.1:8080 user@сервер
```

Публиковать API в интернет по HTTP не стоит: токены и пароли пойдут открытым
текстом.

## 1. Подготовка сервера

```bash
# обновление системы
sudo apt update && sudo apt upgrade -y

# Docker и плагин compose
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
newgrp docker

docker --version && docker compose version
```

Firewall:

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

> Порт `5432` наружу открывать **не нужно**: база доступна только внутри
> docker-сети. Подключаться к ней извне следует через SSH-туннель.

Проверьте, что домен уже указывает на сервер:

```bash
dig +short notescout.example.com
```

## 2. Получение кода и настройка

```bash
sudo mkdir -p /opt && cd /opt
sudo chown "$USER" /opt
git clone <адрес-репозитория> NoteScout-backend
cd NoteScout-backend

cp .env.example .env
```

Сгенерируйте секреты и заполните `.env`:

```bash
# пароль базы
openssl rand -base64 24
# секрет JWT — обязателен, минимум 32 байта
openssl rand -base64 48
```

Минимально нужно задать:

```dotenv
DOMAIN=notescout.example.com
POSTGRES_DB=notescout
POSTGRES_USER=notescout
POSTGRES_PASSWORD=<пароль из первой команды>
JWT_SECRET=<секрет из второй команды>
```

Остальные значения можно оставить по умолчанию — они описаны в `.env.example`
и в [README](../README.md#переменные-окружения).

Проверьте, что секреты не попадут в git:

```bash
grep -n '^\.env$' .gitignore   # должно вывести .env
```

## 3. Запуск

```bash
docker compose up -d --build
```

Первая сборка занимает несколько минут: Gradle скачивает зависимости, затем
Flyway применяет миграции при старте API. Полезно сразу поставить
`BACKUP_ON_START=true` — тогда контейнер бэкапа сделает копию при запуске,
и вы сразу проверите, что механизм работает.

## 4. Проверка

```bash
# состояние контейнеров: все должны быть healthy/running
docker compose ps

# API жив и прошёл проверку готовности (включая доступность БД)
curl -s https://notescout.example.com/actuator/health | jq

# служебные эндпоинты наружу не отдаются — ожидаем 404
curl -s -o /dev/null -w '%{http_code}\n' https://notescout.example.com/actuator/prometheus

# полный сценарий: регистрация -> заметка -> поиск по тегу
API=https://notescout.example.com/api/v1
TOKEN=$(curl -s -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"smoke@example.com","password":"secret-password","displayName":"Smoke"}' \
  | jq -r .accessToken)

curl -s -X POST $API/notes -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Проверка","tags":["smoke"]}' | jq .

curl -s -G $API/notes -H "Authorization: Bearer $TOKEN" -d 'tag=smoke' | jq .totalElements
```

Бэкап работает:

```bash
docker compose logs backup | tail -20
docker compose exec backup ls -lh /backups/daily

# и главное — копия восстанавливается
docker compose exec backup /usr/local/bin/verify-restore.sh
```

## 5. Автозапуск после перезагрузки

Все сервисы объявлены с `restart: unless-stopped`, поэтому стек поднимется сам.
Проверить можно так:

```bash
sudo reboot
# после перезагрузки
docker compose -f /opt/notescout/docker-compose.yml ps
```

> `docker compose` нужно запускать из каталога проекта, иначе он не найдёт `.env`.
> Для удобства добавьте алиас или используйте `docker compose --project-directory /opt/notescout`.

## 6. Обновление версии

```bash
cd /opt/notescout

# 1. Свежая копия ПЕРЕД миграциями — это точка отката
docker compose exec backup /usr/local/bin/backup.sh

# 2. Новый код
git pull

# 3. Пересборка и перезапуск (Flyway применит новые миграции)
docker compose up -d --build

# 4. Проверка
docker compose logs -f api
curl -s https://notescout.example.com/actuator/health | jq
```

Миграции Flyway применяются только вперёд. Если новая версия что-то испортила:
откатите образ (`git checkout <предыдущий-тег>` и пересборка), а если миграция
изменила данные необратимо — восстанавливайте базу из копии, снятой на шаге 1:
см. [runbook-backup.md](runbook-backup.md).

## 7. Ежедневные операции

```bash
# логи
docker compose logs -f api
docker compose logs --since 24h backup

# состояние и потребление
docker compose ps
docker stats --no-stream

# место на диске: копии и данные
docker system df
df -h /var/lib/docker
```

Полезно добавить в cron проверку копий раз в неделю (на хосте):

```cron
# каждое воскресенье в 05:00 — убедиться, что последняя копия восстанавливается
0 5 * * 0 cd /opt/notescout && docker compose exec -T backup /usr/local/bin/verify-restore.sh >> /var/log/notescout-verify.log 2>&1
```

## Чек-лист приёмки

- [ ] `docker compose ps` — все сервисы запущены, `postgres` и `api` здоровы
- [ ] `https://<домен>/actuator/health` отвечает `{"status":"UP"}`
- [ ] сертификат валиден, HTTP редиректит на HTTPS
- [ ] `/actuator/prometheus` снаружи отдаёт 404
- [ ] регистрация, вход, создание заметки и поиск по тегу работают (скрипт из п. 4)
- [ ] `verify-restore.sh` завершается успешно
- [ ] `JWT_SECRET` и `POSTGRES_PASSWORD` уникальны и не равны значениям из `.env.example`
- [ ] `.env` не попал в git
- [ ] после `reboot` стек поднимается автоматически
- [ ] проверено, что порт 5432 закрыт снаружи: `nc -zv <IP> 5432` не подключается

## Частые проблемы

| Симптом | Причина | Что делать |
|---|---|---|
| Caddy не получает сертификат | A-запись не указывает на сервер, закрыт порт 80 | проверить `dig`, `ufw`, логи `docker compose logs caddy` |
| API не стартует, «JWT_SECRET не задан» | пустой или короткий секрет | задать `JWT_SECRET` (минимум 32 байта) и перезапустить |
| API перезапускается циклически | не прошли миграции или нет связи с БД | `docker compose logs api`, проверить `docker compose ps postgres` |
| `verify-restore.sh` падает | копия не создалась или повреждена | `docker compose logs backup`, затем ручной `backup.sh` |
| `413` при загрузке вложения | файл больше лимита или исчерпана квота | поднять `ATTACHMENT_MAX_FILE_SIZE` / `ATTACHMENT_MAX_TOTAL_PER_USER` или удалить ненужные вложения |
| Кончилось место | накопились старые образы, копии или вложения | уменьшить `ATTACHMENT_MAX_TOTAL_PER_USER` и `RETENTION_*`, `docker system prune -a` |
| Вложения пропали после восстановления | файлы лежат вне базы, дамп их не возвращает | выполнить шаг 4 из `runbook-backup.md` — распаковать архив вложений |
