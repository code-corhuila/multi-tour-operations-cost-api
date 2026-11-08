# The service in two stages: Maven builds the jar, a JRE runs it as a user that is not root.
# Exact versions, never latest; the CI passes the same versions from a registry without the Docker Hub limit.
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21.0.8_9-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
COPY pom.xml .
COPY core/pom.xml core/
COPY adapters/pom.xml adapters/
COPY app/pom.xml app/
RUN mvn -q -B dependency:go-offline -pl app -am
COPY core core
COPY adapters adapters
COPY app app
RUN mvn -q -B -DskipTests package -pl app -am

FROM ${JRE_IMAGE}
# the name is fixed by finalName in app/pom.xml, so a version bump does not break this line
COPY --from=build /src/app/target/operations-cost.jar /app.jar
USER 10001
EXPOSE 8084
# Healthy when /health answers 200: the service is alive. Only what the image has (bash, head, grep) is used, no wget or
# curl; a database that is down does not make the container unhealthy, /health says so apart.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT:-8084}; printf "GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; head -n 1 <&3 | grep -q " 200"'
ENTRYPOINT ["java", "-jar", "/app.jar"]
