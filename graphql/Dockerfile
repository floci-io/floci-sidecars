# Local native build from the repository root: docker build -f graphql/Dockerfile .
# CI builds the executable on the host and packages it with graphql/Dockerfile.package instead.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
ARG TARGETARCH
WORKDIR /home/quarkus/build
COPY --chown=quarkus:quarkus pom.xml mvnw ./
COPY --chown=quarkus:quarkus .mvn ./.mvn
COPY --chown=quarkus:quarkus sidecar-core/pom.xml ./sidecar-core/pom.xml
COPY --chown=quarkus:quarkus graphql/pom.xml ./graphql/pom.xml
RUN ./mvnw -B -q -pl graphql -am -DskipTests -Dnative quarkus:go-offline
COPY --chown=quarkus:quarkus sidecar-core/src/main ./sidecar-core/src/main
COPY --chown=quarkus:quarkus graphql/src/main ./graphql/src/main
# x86-64-v2 is the CPU floor Floci's own image uses; arm64 keeps the builder's defaults.
RUN march=""; if [ "$TARGETARCH" = "amd64" ]; then march="-Dquarkus.native.additional-build-args-append=-march=x86-64-v2"; fi; \
    ./mvnw -B -pl graphql -am -DskipTests -Dnative package $march

FROM registry.access.redhat.com/ubi9-micro:9.7
ARG SIDECAR_VERSION=dev
ENV SIDECAR_VERSION=${SIDECAR_VERSION}
LABEL io.floci.sidecar.contract="1" \
      io.floci.sidecar.name="graphql" \
      io.floci.sidecar.version="${SIDECAR_VERSION}" \
      io.floci.sidecar.port="8181" \
      io.floci.sidecar.health-path="/health" \
      org.opencontainers.image.title="Floci GraphQL sidecar" \
      org.opencontainers.image.version="${SIDECAR_VERSION}" \
      org.opencontainers.image.source="https://github.com/floci-io/floci-sidecars" \
      org.opencontainers.image.licenses="MIT"
RUN echo 'sidecar:x:10001:10001:Sidecar:/app:/sbin/nologin' >> /etc/passwd \
    && echo 'sidecar:x:10001:' >> /etc/group
WORKDIR /app
# The builder has no static zlib, so the executable links libz dynamically; ubi9-micro lacks it.
COPY --from=build /usr/lib64/libz.so.1 /usr/lib64/libz.so.1
COPY --chmod=755 --chown=10001:10001 --from=build /home/quarkus/build/graphql/target/sidecar-runner /app/application
USER 10001:10001
EXPOSE 8181
HEALTHCHECK --interval=5s --timeout=3s --retries=5 CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8181}"]
ENTRYPOINT ["/app/application"]
