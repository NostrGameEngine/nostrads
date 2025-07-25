FROM  alpine/java:21-jre

ADD dist/portable/nostrads-delegate-fat-all.jar /nostrads-delegate.jar
RUN mkdir -p /data
VOLUME ["/data"]

ENV APP_DATA_DIR=/data

WORKDIR /data
ENTRYPOINT ["java", "-jar", "/nostrads-delegate.jar"]