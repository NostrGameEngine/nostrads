FROM ubuntu:latest

ADD build/native/nativeCompile/nostrads /nostrads
RUN chmod +x /nostrads
RUN mkdir -p /data
VOLUME ["/data"]

ENV APP_DATA_DIR=/data
ENV NOSTRADS_DELEGATE_NAME=nostrads-delegate-container

WORKDIR /data
ENTRYPOINT ["/nostrads"]

