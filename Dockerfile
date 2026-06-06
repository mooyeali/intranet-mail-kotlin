FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /opt/intranet-mail
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 mailapp \
    && mkdir -p /opt/intranet-mail/data \
    && chown -R mailapp:mailapp /opt/intranet-mail
COPY --from=build /app/target/intranet-mail-kotlin-0.1.0.jar /opt/intranet-mail/app.jar
USER mailapp
EXPOSE 8080 2525 1110
ENV H2_URL="jdbc:h2:/opt/intranet-mail/data/intranet-mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" \
    ATTACHMENT_DIR="/opt/intranet-mail/data/attachments"
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health/ready >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/opt/intranet-mail/app.jar"]
