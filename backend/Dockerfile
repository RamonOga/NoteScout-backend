# ============================================================================
#  Сборка образа API: multi-stage, без Gradle-обёртки в репозитории.
#  Стадия сборки использует официальный образ Gradle, поэтому локально
#  устанавливать Gradle не обязательно — достаточно Docker.
# ============================================================================

# ---------- Стадия 1: сборка ----------
FROM gradle:8.14.2-jdk21 AS build

WORKDIR /workspace

# Сначала только файлы сборки — слой с зависимостями кешируется между сборками.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon --console=plain dependencies --quiet > /dev/null 2>&1 || true

# Затем исходники.
COPY src ./src
RUN gradle --no-daemon --console=plain bootJar -x test

# ---------- Стадия 2: рантайм ----------
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache tzdata wget \
    && addgroup -S app \
    && adduser -S app -G app

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

# Каталог вложений создаётся здесь, а не приложением на старте.
#
# Том Docker создаётся от root, и каталог точки монтирования тоже достаётся
# root. Приложение работает под непривилегированным app и записать туда не
# может: загрузка вложения падает с 500, хотя на старте всё выглядит исправно,
# потому что createDirectories на существующем каталоге прав не требует.
#
# Каталог в образе с нужным владельцем чинит это для НОВОГО тома: при первом
# создании Docker копирует в том содержимое и права каталога образа. Уже
# существующий том так не исправить — Docker права не переносит, — поэтому при
# обновлении развёртывания нужен разовый chown, см. docs/deploy.md.
RUN mkdir -p /var/lib/notescout/attachments \
    && chown -R app:app /var/lib/notescout

USER app
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Duser.timezone=UTC"

# Healthcheck объявлен в docker-compose.yml — там он виден и для оркестратора.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
