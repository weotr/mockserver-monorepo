#
# MockServer Dockerfile
#
# https://github.com/mock-server/mockserver
# http://www.mock-server.com
#

# runtime image https://console.cloud.google.com/gcr/images/distroless/global/java17
FROM gcr.io/distroless/java17:nonroot@sha256:81d09cac6ec47f6a13c61a941557f95079213320f3ddbf9d353de9317669aab5

# maintainer details
MAINTAINER James Bloom "jamesdbloom@gmail.com"

# expose ports.
EXPOSE 1080

# copy in jar
COPY mockserver-netty-jar-with-dependencies.jar /

# don't run MockServer as root
USER nonroot

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT 1080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["java", "-cp", "/mockserver-netty-jar-with-dependencies.jar", "org.mockserver.cli.HealthCheck"]

CMD []
