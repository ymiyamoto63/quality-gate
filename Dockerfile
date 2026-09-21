# フロントエンドを同梱した実行可能 jar をビルドする。
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml -B -q dependency:go-offline -DskipTests || true
COPY backend backend
COPY frontend frontend
COPY api api
RUN mvn -f backend/pom.xml -B -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 10001 quality-gate \
 && mkdir -p /var/lib/quality-gate/artifacts \
 && chown -R quality-gate /var/lib/quality-gate
COPY --from=build /build/backend/target/quality-gate.jar /app/quality-gate.jar
USER quality-gate
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quality-gate.jar"]
