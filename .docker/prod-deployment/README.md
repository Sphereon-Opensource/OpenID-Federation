# OpenID Federation Server Deployment

This repository contains scripts and configuration files for deploying the OpenID Federation Server and Admin Server
using Docker.

## Repository Contents

### Deployment Scripts (Local Use Only)

```
├── build.sh          # Build script for local development
├── push.sh           # Script to push images to registry
└── setup-env.sh      # Environment setup script
```

### Deployment Files (To Be Deployed)

```
├── docker-compose.yaml
└── config/
    ├── openid-federation-server/
    │   └── application.properties
    └── openid-federation-admin-server/
        └── application.properties
```

## Prerequisites

- Docker and Docker Compose (version 3.9 or higher)
- Access to a Docker Container Registry
- Traefik as reverse proxy (configured with HTTPS and acme resolver)

## Environment Variables

Before running the deployment, ensure the following environment variables are properly configured:

### Database Configuration

- `DATASOURCE_USER` - PostgreSQL user for the main database
- `DATASOURCE_PASSWORD` - PostgreSQL password for the main database
- `DATASOURCE_DB` - Main database name

### Application Configuration

- `APP_KEY` - Application key for encryption
- `KMS_PROVIDER` - Key Management Service provider configuration
- `ROOT_IDENTIFIER` - Root identifier for the federation
- `FEDERATION_HOSTS` - Host rules for the federation server
- `FEDERATION_ADMIN_HOSTS` - Host rules for the admin server
- `ADMIN_IP_WHITELIST` - Comma-separated list of IP ranges allowed to access the admin server

## Deployment Steps

1. Create required directories for persistent storage:

```bash
sudo mkdir -p /mnt/openid-federation/volumes/{postgres}
```

2. Copy deployment files to target system:

```bash
docker-compose.yaml
config/
```

3. Start the services using Docker Compose:

```bash
docker-compose up -d
```

## Service Architecture

The deployment consists of the following services:

- **db**: Main PostgreSQL database
- **openid-federation-server**: Federation server service
- **openid-federation-admin-server**: Administrative interface for the federation server

### Networking

The deployment uses two Docker networks:

- `frontend`: For external communication (must be created manually)
- `backend`: For internal service communication (automatically created)

### Security

- The admin server is protected by IP whitelisting through Traefik middleware
- All services use TLS encryption through Traefik's ACME resolver
- Database credentials are managed through environment variables
- Persistent data is stored in volume mounts

## Maintenance

### Updating Services

To update to the latest version of the services:

```bash
docker-compose pull
docker-compose up -d
```

### Logs

To view service logs:

```bash
docker-compose logs -f [service-name]
```

## Troubleshooting

1. If database services fail to start, check:
    - Volume permissions
    - Available disk space
    - PostgreSQL port conflicts

2. If servers fail to start, verify:
    - Database connectivity
    - Environment variable configuration
    - Network connectivity

3. For admin server access issues:
    - Verify IP whitelist configuration
    - Check Traefik logs for middleware issues

## Support

For additional support or questions, please contact the Sphereon support team.
