FROM openjdk:21-jdk as builder
RUN microdnf install findutils

WORKDIR /app

COPY . /app

RUN chmod +x ./gradlew

RUN ./gradlew :modules:local-kms:bootJar -x test -x allTests -x jsBrowserTest

FROM openjdk:21-jdk as runner

WORKDIR /app

COPY .env .env
COPY --from=builder /app/modules/local-kms/build/libs/local-kms-0.0.1.jar ./local-kms-0.0.1.jar

ENTRYPOINT ["java", "-jar", "local-kms-0.0.1.jar"]
