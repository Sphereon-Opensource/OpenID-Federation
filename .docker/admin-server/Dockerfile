FROM openjdk:21-jdk as builder
RUN microdnf install findutils

WORKDIR /app

COPY . /app

RUN chmod +x ./gradlew

RUN ./gradlew :modules:admin-server:bootJar -x test -x allTests -x jsBrowserTest

FROM openjdk:21-jdk as runner

WORKDIR /app

COPY --from=builder /app/modules/admin-server/build/libs/admin-server-0.1.2-SNAPSHOT.jar ./admin-server-0.1.2.jar

ENTRYPOINT ["java", "-jar", "admin-server-0.1.2.jar"]
