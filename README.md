<h1 align="center">
  <br>
  <a href="https://www.sphereon.com"><img src="https://sphereon.com/content/themes/sphereon/assets/img/logo.svg" alt="Sphereon" width="400"></a>
  <br>OpenID Federation Monorepo
  <br>
</h1>

# Background

OpenID Federation is a framework designed to facilitate the secure and interoperable interaction of entities within a
federation. This involves the use of JSON Web Tokens (JWTs) to represent and convey necessary information for entities
to participate in federations, ensuring trust and security across different organizations and systems.

In the context of OpenID Federation, Entity Statements play a crucial role. These are signed JWTs that contain details
about the entity, such as its public keys and metadata. This framework allows entities to assert their identity and
capabilities in a standardized manner, enabling seamless integration and interoperability within federations.

# Key Concepts

- **Federation**: A group of organizations that agree to interoperate under a set of common rules defined in a
  federation policy.
- **Entity Statements**: JSON objects that contain metadata about entities (IdPs, RPs) and their federation
  relationships.
- **Trust Chains**: Mechanisms by which parties in a federation verify each other’s trustworthiness through a chain of
  entity statements, leading back to a trusted authority.
- **Federation API**: Interfaces defined for entities to exchange information and perform operations necessary for
  federation management.

## Core Components

- **Federation Operator**: The central authority in a federation that manages policy and trust chain verification.
- **Identity Providers (IdPs)**: Entities that authenticate users and provide identity assertions to relying parties.
- **Relying Parties (RPs)**: Entities that rely on identity assertions provided by IdPs to offer services to users.

## Technical Features

- **JSON Web Tokens (JWT)**: Used for creating verifiable entity statements and security assertions.
- **JSON Object Signing and Encryption (JOSE)**: Standards for signing and encrypting JSON-based objects to ensure their
  integrity and confidentiality.

# Local Key Management System - Important Notice

Local Key Management Service is designed primarily for testing, development, and local experimentation
purposes. **It is not intended for use in production environments** due to significant security and compliance risks.

# Servers Deployment Instructions

## Docker Setup

For seamless deployment of the OpenID Federation servers, Docker and Docker Compose offer the most efficient and
straightforward approach.

## Essential Commands

### Build Docker Images

- `docker compose build` - Compile the Docker images for the services.
- `docker compose build --no-cache` - Compile the Docker images without utilizing the build cache, ensuring a clean
  build.

### Manage Services:

- `docker compose up` - Initiate all the services.
- `docker compose up -d` - Launch all the services in detached mode, allowing them to run in the background.
- `docker compose down` - Terminate the services.
- `docker compose down -v` - Terminate the services and remove associated volumes.
- `docker compose up db -d` - Start only the database container in detached mode for isolated database operations.
- `docker compose up federation-server -d` - Start only the Federation Server in detached mode.

## API Endpoints via Docker

* Federation API: Accessible at http://localhost:8080
* Admin Server API: Accessible at http://localhost:8081
* Default Keycloak Server: Accessible at http://localhost:8082

# OpenID Federation Configuration Guide

This guide will help new users configure and deploy the OpenID Federation service, including setting up environment
variables, the root entity, and necessary dependencies. Follow the steps outlined below.

## Introduction

The system comes with a preconfigured "root" account entity that responds to the root URL identifier's endpoints (
e.g., `/.well-known/openid-federation`) and not tenant account endpoints. This account is used for managing
configurations specific to the root entity.

**Important:** The local Key Management System (KMS) configuration provided in this guide is for development and testing
purposes only. **It must not be used in production environments.** For production, deploy a secure, production-grade KMS
provider.

---

## Step 1: Configure Environment Variables

Set the following environment variables in your deployment environment. These variables are critical for configuring the
service and connecting to the required resources.

### General Configuration

```env
APP_KEY=Nit5tWts42QeCynT1Q476LyStDeSd4xb
# A 32-byte random string that every deployer needs to create. It is used for application-level security.

ROOT_IDENTIFIER=http://localhost:8081
# The OpenID identifier of the root entity. It must be a valid URL hosting the well-known endpoint.

DATASOURCE_URL=jdbc:postgresql://db:5432/openid-federation-db
# The database instance URL. Defaults to the Docker Compose PostgreSQL instance.

DATASOURCE_USER=openid-federation-db-user
# The username for the database.

DATASOURCE_PASSWORD=openid-federation-db-password
# The password for the database.

DATASOURCE_DB=openid-federation-db
# The database name.
```

### Key Management System (KMS)

```env
KMS_PROVIDER=local
# Defaults to the local KMS provider that runs on Docker. **Do not use in production.**

LOCAL_KMS_DATASOURCE_URL=jdbc:postgresql://local-kms-db:5432/openid-federation-local-kms-db
# The database instance URL for the local KMS.

LOCAL_KMS_DATASOURCE_USER=openid-federation-local-kms-db-user
# The username for the local KMS database.

LOCAL_KMS_DATASOURCE_PASSWORD=openid-federation-local-kms-db-password
# The password for the local KMS database.

LOCAL_KMS_DATASOURCE_DB=openid-federation-local-kms-db
# The database name for the local KMS.
```

### Keycloak OAuth2 Provider

```env
KC_BOOTSTRAP_ADMIN_USERNAME=admin
# Default username for the local Keycloak OAuth2 provider.

KC_BOOTSTRAP_ADMIN_PASSWORD=admin
# Default password for the local Keycloak instance.

OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI=http://keycloak:8080/realms/openid-federation
# The JWT issuer URI for the local Keycloak instance.
```

---

### Notes:

1. Replace default values (e.g., `admin`, `localhost`, `password`) with secure values for production environments.
2. Ensure the `ROOT_IDENTIFIER` is a publicly accessible URL if deploying in a live environment.
3. Use a production-grade KMS provider in production environments instead of the local Docker-based KMS.

## Step 2: Start the Service Stack

Once the environment variables are configured, you can start the OpenID Federation service stack using Docker Compose:

```bash
docker compose up
```

This command will initialize all necessary services, including the database, KMS provider, and Keycloak, as defined in
the Docker Compose configuration file.

---

## Step 3: Obtain an Access Token

The admin endpoints are protected and require a valid JWT access token. To acquire one, follow these steps:

### Using Keycloak Token Endpoint

1. **Send a POST Request to the Keycloak Token Endpoint**:

   ```http
   POST http://localhost:8082/realms/openid-federation/protocol/openid-connect/token
   ```

2. **Provide the Required Credentials in the Request Body**:

   Use `x-www-form-urlencoded` format with the following parameters:

   ```text
   grant_type=client_credentials
   client_id=openid-client
   client_secret=th1s1s4s3cr3tth4tMUSTb3ch4ng3d
   ```

3. **Example cURL Command**:

   ```bash
   curl -X POST http://localhost:8082/realms/openid-federation/protocol/openid-connect/token \
     -H "Content-Type: application/x-www-form-urlencoded" \
     -d "grant_type=client_credentials" \
     -d "client_id=openid-client" \
     -d "client_secret=th1s1s4s3cr3tth4tMUSTb3ch4ng3d"
   ```

4. **Parse the Response**:

   A successful request returns a JSON object. Extract the `access_token` field:

   ```json
   {
       "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
       "expires_in": 300,
       "token_type": "Bearer",
       "not-before-policy": 0,
       "scope": "openid"
   }
   ```

5. **Use the Access Token in Subsequent API Requests**:

   Add the `access_token` to the `Authorization` header:

   ```http
   Authorization: Bearer <access_token>
   ```

### Notes

- Replace `client_secret` with a secure value in a production environment.
- The token expires after a specified duration (`expires_in` field). Acquire a new token as needed.

---

## Step 4: Create a New Tenant Account

To create a new tenant account, follow these steps. Any username can be used, and the same process in the following
steps applies to all accounts, including the root account, which we will use as an example.

1. Send a `POST` request to the following endpoint:

   ```http
   POST http://localhost:8081/accounts/{username}
   ```

2. Include a JSON body with the desired account details. For example:

   ```json
   {
       "username": "{username}",
       "identifier": "https://example.com/{username}"
   }
   ```

---

## Step 5: Delete an Account by Username

To delete an account by its username, follow these steps:

1. Send a `DELETE` request to the following endpoint:

   ```http
   DELETE http://localhost:8081/accounts/{username}
   ```

---

## Step 6: Create and Manage Account Keys

### Create a New Key Pair for the Account

1. Send a `POST` request to create a new key pair:

   ```http
   POST http://localhost:8081/accounts/{username}/keys
   ```

### List Keys for an Account

1. Send a `GET` request to list the keys associated with the account:

   ```http
   GET http://localhost:8081/accounts/{username}/keys
   ```

### Revoke a Key

To revoke a key for an account, follow these steps:

1. Send a `DELETE` request to the following endpoint:

   ```http
   DELETE http://localhost:8081/accounts/{username}/keys/{keyId}
   ```

2. Optionally, include a `reason` query parameter to specify the reason for revocation. For example:

   ```http
   DELETE http://localhost:8081/accounts/{username}/keys/{keyId}?reason=Key+compromised
   ```

---

## Step 7: Define Metadata for an Entity

To assign metadata to your entity, follow these steps:

1. Send a `POST` request to the following endpoint:

   ```http
   POST http://localhost:8081/accounts/{username}/metadata
   ```

2. Include a JSON body with the metadata details. For example:

   ```json
   {
       "key": "basic_metadata",
       "metadata": {
           "client_uri": "https://example.com",
           "contacts": [
               "admin@example.com",
               "support@example.com"
           ]
       }
   }
   ```

### List Metadata for an Entity

1. Send a `GET` request to list all metadata for the entity:

   ```http
   GET http://localhost:8081/accounts/{username}/metadata
   ```

### Delete Metadata by ID

1. Send a `DELETE` request to delete a metadata entry by its ID:

   ```http
   DELETE http://localhost:8081/accounts/{username}/metadata/{id}
   ```

---

## Step 8: Create and Manage Subordinates

### Create a New Subordinate

1. Send a `POST` request to the following endpoint:

   ```http
   POST http://localhost:8081/accounts/{username}/subordinates
   ```

2. Include a JSON body with the subordinate details. For example:

   ```json
   {
       "identifier": "https://example.com/subordinate1"
   }
   ```

### List Subordinates

1. Send a `GET` request to list all subordinates for a given account:

   ```http
   GET http://localhost:8081/accounts/{username}/subordinates
   ```

### Delete a Subordinate

1. Send a `DELETE` request to delete a subordinate by its ID:

   ```http
   DELETE http://localhost:8081/accounts/{username}/subordinates/{id}
   ```

---

## Step 9: Manage Subordinate Metadata

### Add Metadata to a Subordinate

1. Send a `POST` request to the following endpoint:

   ```http
   POST http://localhost:8081/accounts/{username}/subordinates/{subordinateId}/metadata
   ```

2. Include a JSON body with the metadata details. For example:

   ```json
   {
       "key": "example_key",
       "metadata": {
           "description": "Example metadata description"
       }
   }
   ```

### List Metadata for a Subordinate

1. Send a `GET` request to list all metadata for a subordinate:
   ```http
   GET http://localhost:8081/accounts/{username}/subordinates/{subordinateId}/metadata
   ```

### Delete Metadata by ID

1. Send a `DELETE` request to delete a metadata entry by its ID:
   ```http
   DELETE http://localhost:8081/accounts/{username}/subordinates/{subordinateId}/metadata/{id}
   ```

---

## Step 10: Manage Subordinate JWKS

### Add a JWKS for a Subordinate

1. Send a `POST` request to the following endpoint:

   ```http
   POST http://localhost:8081/accounts/{username}/subordinates/{id}/jwks
   ```

2. Include a JSON body with the JWKS details. For example:

   ```json
   {
       "key": "example_key",
       "key_ops": ["sign", "verify"],
       "kty": "RSA"
   }
   ```

### List JWKS for a Subordinate

1. Send a `GET` request to list all JWKS for a subordinate:

   ```http
   GET http://localhost:8081/accounts/{username}/subordinates/{id}/jwks
   ```

### Delete a JWKS by ID

1. Send a `DELETE` request to delete a JWKS entry by its ID:

   ```http
   DELETE http://localhost:8081/accounts/{username}/subordinates/{id}/jwks/{jwkId}
   ```

---

## Step 11: Get Subordinate Statement Object

1. Send a `GET` request to retrieve the statement for a subordinate:

   ```http
   GET http://localhost:8081/accounts/{username}/subordinates/{id}/statement
   ```

---

## Step 12: Publish Subordinate Statement

1. Send a `POST` request to publish a subordinate statement:

   ```http
   POST http://localhost:8081/accounts/{username}/subordinates/{id}/statement
   ```

2. Optionally include a `dryRun` parameter in the request body to test the statement publication without making changes:

   ```json
   {
       "dryRun": true
   }
   ```

---

## Step 13: Get and Publish Entity Configuration Statement

### Get Entity Configuration Statement Object

1. Send a `GET` request to retrieve the entity configuration statement:

   ```http
   GET http://localhost:8081/accounts/{username}/entity-statement
   ```

2. Replace `{username}` with the specific account username for which you want to retrieve the entity configuration
   statement.

### Publish Entity Configuration Statement Object

1. Send a `POST` request to publish the entity configuration statement:

   ```http
   POST http://localhost:8081/accounts/{username}/entity-statement
   ```

2. Optionally, include a `dryRun` parameter in the request body to test the statement publication without making
   changes:

   ```json
   {
       "dryRun": true
   }
   ```

3. Replace `{username}` with the account username for which you want to publish the entity configuration statement.
