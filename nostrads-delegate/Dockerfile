FROM eclipse-temurin:21-jre-noble@sha256:ca397720325ceefe39ce397f186759fc87d9efafb2dc4ce53315980844c2f4f2 AS native

ARG TARGETARCH
COPY dist/portable/nostrads-delegate-fat-all.jar /tmp/nostrads-delegate.jar
RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && case "$TARGETARCH" in amd64) native_arch=x86_64 ;; arm64) native_arch=aarch64 ;; *) exit 1 ;; esac \
    && mkdir -p /native \
    && unzip -p /tmp/nostrads-delegate.jar "natives/linux/${native_arch}/libsaferalloc.so" > /native/libsaferalloc.so \
    && chmod 0555 /native/libsaferalloc.so \
    && rm -rf /var/lib/apt/lists/* /tmp/nostrads-delegate.jar

FROM eclipse-temurin:21-jre-noble@sha256:ca397720325ceefe39ce397f186759fc87d9efafb2dc4ce53315980844c2f4f2

LABEL org.opencontainers.image.source="https://github.com/NostrGameEngine/nostrads"
LABEL org.opencontainers.image.description="NostrAds delegate service"

RUN groupadd --system --gid 10001 nostrads \
    && useradd --system --uid 10001 --gid 10001 --home-dir /data --shell /usr/sbin/nologin nostrads \
    && install -d -o nostrads -g nostrads -m 0700 /data

COPY --chmod=0444 dist/portable/nostrads-delegate-fat-all.jar /app/nostrads-delegate.jar
COPY --from=native --chmod=0555 /native/libsaferalloc.so /app/native/libsaferalloc.so

ENV APP_DATA_DIR=/data

VOLUME ["/data"]
WORKDIR /data
USER 10001:10001
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-Dsaferalloc.native.override=/app/native/libsaferalloc.so", "-jar", "/app/nostrads-delegate.jar"]
