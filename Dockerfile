# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 vendornex && mkdir -p /data/documents && chown -R vendornex /data
WORKDIR /app
COPY --from=build /src/target/vendornex-api.jar app.jar
USER vendornex
ENV JAVA_OPTS="-Duser.timezone=UTC -XX:MaxRAMPercentage=75 -Dstdout.encoding=UTF-8" \
    DOCUMENT_STORE_DIR=/data/documents \
    HTTP_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
