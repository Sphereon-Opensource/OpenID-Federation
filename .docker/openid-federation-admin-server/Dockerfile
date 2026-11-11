FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY . /app
RUN chmod +x ./gradlew
RUN rm -f .env*

# Build the Ktor-based admin server
RUN ./gradlew :modules:openid-federation-admin-server-ktor:installDist -x test -x allTests -x integrationTests --no-daemon -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=512m"

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the installDist output (includes jars and start scripts)
COPY --from=builder /app/modules/openid-federation-admin-server-ktor/build/install/openid-federation-admin-server-ktor ./
# The Ktor admin server binds 8081 by default so that it can run next to the public server on one host. The earlier admin image
# listened on 8080 and the compose files, the proxy labels and the health check below use 8080, so the image says so itself.
# An environment variable overrides a port in a mounted configuration file, set this one to change the port.
ENV OIDF_SERVER_ADMIN_PORT=8080
# The server migrates the schema before it binds its port and may wait up to ten minutes for the migration lock, so failures do not count until then.
HEALTHCHECK --interval=30s --timeout=3s --start-period=600s CMD curl -f http://localhost:8080/health || exit 1

# Create non-root user
RUN useradd -r -u 1002 -g root admin-server
USER admin-server

ENTRYPOINT ["./bin/openid-federation-admin-server-ktor"]
CMD []
