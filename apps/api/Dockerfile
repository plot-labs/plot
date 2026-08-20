# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx2g -Dkotlin.daemon.jvmargs=-Xmx2g"

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test && \
    find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' \
        -exec cp '{}' /workspace/app.jar ';' && \
    test -f /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system plot && \
    useradd --system --gid plot --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin plot

COPY --from=build --chown=plot:plot /workspace/app.jar ./app.jar

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=20.0 -XX:MaxRAMPercentage=65.0 -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -XX:+ExitOnOutOfMemoryError" \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_SHUTDOWN=graceful \
    SPRINGDOC_API_DOCS_ENABLED=false \
    SPRINGDOC_SWAGGER_UI_ENABLED=false \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

USER plot
ENTRYPOINT ["java", "-jar", "app.jar"]
