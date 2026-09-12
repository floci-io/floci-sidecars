# Build from the repository root: docker build -f cedar/Dockerfile .
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY sidecar-core/pom.xml ./sidecar-core/pom.xml
COPY cedar/pom.xml ./cedar/pom.xml
RUN mvn -B -q -pl cedar -am -DskipTests dependency:go-offline
COPY sidecar-core/src/main ./sidecar-core/src/main
COPY cedar/src/main ./cedar/src/main
RUN mvn -B -q -pl cedar -am -DskipTests package

FROM eclipse-temurin:25-jre
ARG SIDECAR_VERSION=dev
ENV SIDECAR_VERSION=${SIDECAR_VERSION}
LABEL io.floci.sidecar.contract="1" \
      io.floci.sidecar.name="cedar" \
      io.floci.sidecar.version="${SIDECAR_VERSION}" \
      io.floci.sidecar.port="8180" \
      io.floci.sidecar.health-path="/health" \
      org.opencontainers.image.title="Floci Cedar sidecar" \
      org.opencontainers.image.version="${SIDECAR_VERSION}" \
      org.opencontainers.image.source="https://github.com/floci-io/floci-sidecars" \
      org.opencontainers.image.licenses="MIT"
RUN groupadd --system --gid 10001 sidecar \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin sidecar
WORKDIR /app
COPY --chown=10001:10001 --from=build /build/cedar/target/sidecar.jar /app/sidecar.jar
COPY --chown=10001:10001 --from=build /build/cedar/target/dependency /app/lib
USER 10001:10001
EXPOSE 8180
ENTRYPOINT ["java", "-cp", "/app/sidecar.jar:/app/lib/*", "io.floci.sidecar.cedar.CedarSidecar"]
