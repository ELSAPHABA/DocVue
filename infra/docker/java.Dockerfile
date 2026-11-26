FROM gradle:8.14.4-jdk21@sha256:cc90b5198f747c454f4a3e17495b626fbdfa7ac77f1ad08502c355e6f81e6772 AS builder
USER gradle
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle settings.gradle build.gradle gradle.properties ./
COPY --chown=gradle:gradle services/core-api/build.gradle services/core-api/build.gradle
COPY --chown=gradle:gradle services/realtime-api/build.gradle services/realtime-api/build.gradle
COPY --chown=gradle:gradle services/core-api/gradle.lockfile services/core-api/gradle.lockfile
COPY --chown=gradle:gradle services/realtime-api/gradle.lockfile services/realtime-api/gradle.lockfile
ARG SERVICE
COPY --chown=gradle:gradle services/${SERVICE}/src services/${SERVICE}/src
RUN gradle --no-daemon :services:${SERVICE}:bootJar && cp services/${SERVICE}/build/libs/app.jar /tmp/app.jar

FROM eclipse-temurin:21-jre-jammy@sha256:a8bff88f760e87e0c8844f8461f807c203fd5e8f78c951c843024647e965eaf5
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 dockvue && useradd --uid 10001 --gid 10001 --no-create-home dockvue
COPY --from=builder /tmp/app.jar /app.jar
USER 10001:10001
ENTRYPOINT ["java","-jar","/app.jar"]
