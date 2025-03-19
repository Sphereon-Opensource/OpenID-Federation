FROM openjdk:21-jdk AS builder
RUN microdnf install findutils

WORKDIR /app
COPY . /app
RUN rm .env*

RUN chmod +x ./gradlew

RUN ./gradlew :modules:federation-server:bootJar -x test -x allTests -x jsBrowserTest

FROM openjdk:21-jdk
RUN microdnf install curl

WORKDIR /app

COPY --from=builder /app/modules/federation-server/build/libs/federation-server-*.jar ./federation-server.jar
HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost:8080/status || exit 1

# Create non-root user
RUN useradd -r -u 1001 -g root federation-server
USER federation-server

ENTRYPOINT ["java"]
CMD ["-XX:MaxRAMPercentage=75.0", "-XX:InitialRAMPercentage=50.0", "-XX:+UseG1GC", "-jar", "federation-server.jar"]
