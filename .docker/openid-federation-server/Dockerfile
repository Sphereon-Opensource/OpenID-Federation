FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY . /app
RUN chmod +x ./gradlew
RUN rm -f .env*

# Build the Ktor-based federation server
RUN ./gradlew :modules:openid-federation-public-server-ktor:installDist -x test -x allTests -x integrationTests --no-daemon -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=512m"

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the installDist output (includes jars and start scripts)
COPY --from=builder /app/modules/openid-federation-public-server-ktor/build/install/openid-federation-public-server-ktor ./
HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost:8080/.well-known/openid-federation || exit 1

# Create non-root user
RUN useradd -r -u 1001 -g root federation-server
USER federation-server

ENTRYPOINT ["./bin/openid-federation-public-server-ktor"]
CMD []
