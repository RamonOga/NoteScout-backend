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

USER app
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Duser.timezone=UTC"

# Healthcheck объявлен в docker-compose.yml — там он виден и для оркестратора.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
