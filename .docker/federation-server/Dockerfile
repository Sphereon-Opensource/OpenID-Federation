FROM openjdk:21-jdk as builder
RUN microdnf install findutils

WORKDIR /app

COPY . /app

RUN chmod +x ./gradlew

RUN ./gradlew :modules:federation-server:bootJar -x test -x allTests -x jsBrowserTest

FROM openjdk:21-jdk as runner

WORKDIR /app

COPY .env .env
COPY --from=builder /app/modules/federation-server/build/libs/federation-server-0.0.1.jar ./federation-server-0.0.1.jar

ENTRYPOINT ["java", "-jar", "federation-server-0.0.1.jar"]
