# Stage 1: Build
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Mirror Maven Central through Google's GCS replica: repo.maven.apache.org
# rate-limits (HTTP 429) under repeated local image builds and fails them.
RUN mkdir -p /root/.m2 && printf '%s' \
    '<settings><mirrors><mirror>' \
    '<id>gcs-central</id><mirrorOf>central</mirrorOf>' \
    '<url>https://maven-central.storage-download.googleapis.com/maven2/</url>' \
    '</mirror></mirrors></settings>' > /root/.m2/settings.xml

COPY pom.xml .
# resolve-plugins caches build plugins (surefire, jar, resources, ...) in
# this pom.xml-keyed layer; without it every image build re-fetches them
# on every build.
RUN mvn dependency:go-offline dependency:resolve-plugins -q

COPY src/ src/
# No `clean`: the build stage is fresh (no target/ to clean), and resolving
# the clean-plugin pulls from Maven Central on every image build — which
# rate-limits (HTTP 429) under repeated local builds.
RUN mvn package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-noble

ARG VERSION=latest
ENV FLOCI_VERSION=${VERSION}
ENV FLOCI_STORAGE_PERSISTENT_PATH=/app/data
# Alchemy Lambda StartSyncExecution targets https://sync-states.{region}.amazonaws.com:443
# (Smithy hostPrefix "sync-"). TLS + the TlsProxyServer 443 bind + CA mount in
# ContainerLauncher are required for that hop; HTTP on 4566 stays dual-protocol.
ENV FLOCI_TLS_ENABLED=true

ENV AWS_DEFAULT_REGION=us-east-1
ENV AWS_ACCESS_KEY_ID=test
ENV AWS_SECRET_ACCESS_KEY=test
ENV AWS_CONFIG_FILE=/etc/floci/aws/config

ENV GOSU_VERSION=1.17
ENV GOSU_AMD64_SHA256=bbc4136d03ab138b1ad66fa4fc051bafc6cc7ffae632b069a53657279a450de3
ENV GOSU_ARM64_SHA256=c3805a85d17f4454c23d7059bcb97e1ec1af272b90126e79ed002342de08389b
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates passwd python3 python3-pip wget nodejs; \
    rm -rf /var/lib/apt/lists/*; \
    pip3 install --no-cache-dir --break-system-packages awscli boto3; \
    aws_bin="$(command -v aws)"; \
    mv "$aws_bin" /usr/bin/aws.real; \
    printf '#!/bin/sh\nexec /usr/bin/aws.real --endpoint-url=http://localhost:4566 "$@"\n' > /usr/bin/aws; \
    chmod +x /usr/bin/aws; \
    useradd -r -u 1001 -g root -d /app -s /sbin/nologin floci; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        amd64) gosuArch='amd64'; gosuSha256="$GOSU_AMD64_SHA256" ;; \
        arm64) gosuArch='arm64'; gosuSha256="$GOSU_ARM64_SHA256" ;; \
        *) echo >&2 "unsupported arch: $arch"; exit 1 ;; \
    esac; \
    wget -q -O /usr/local/bin/gosu "https://github.com/tianon/gosu/releases/download/${GOSU_VERSION}/gosu-${gosuArch}"; \
    echo "${gosuSha256}  /usr/local/bin/gosu" | sha256sum -c -; \
    chmod +x /usr/local/bin/gosu

WORKDIR /app

RUN mkdir -p /app/data /etc/floci/aws \
    && printf '[default]\nendpoint_url = http://localhost:4566\n' > /etc/floci/aws/config \
    && chown 1001:root /app \
    && chmod "g+rwX" /app \
    && chown 1001:root /app/data

COPY --from=build /build/target/quarkus-app/ quarkus-app/
RUN chown -R 1001:root /app/quarkus-app

COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY --chmod=755 docker/localstack-parity.sh /usr/local/bin/localstack-parity.sh

VOLUME /app/data

EXPOSE 4566
EXPOSE 443

# Need to expose separately due to https://github.com/floci-io/floci/issues/2095.
# This preseres compatibility with https://github.com/docker-java/docker-java/blob/002b6eb7aa2b892be729c7c2a5e95f782924db38/docker-java-api/src/main/java/com/github/dockerjava/api/model/ExposedPort.java#L119
EXPOSE 6379
EXPOSE 6380
EXPOSE 6381
EXPOSE 6382
EXPOSE 6383
EXPOSE 6384
EXPOSE 6385
EXPOSE 6386
EXPOSE 6387
EXPOSE 6388
EXPOSE 6389
EXPOSE 6390
EXPOSE 6391
EXPOSE 6392
EXPOSE 6393
EXPOSE 6394
EXPOSE 6395
EXPOSE 6396
EXPOSE 6397
EXPOSE 6398
EXPOSE 6399

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
    CMD wget -q --spider http://localhost:4566/_floci/health || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/quarkus-app/quarkus-run.jar"]
