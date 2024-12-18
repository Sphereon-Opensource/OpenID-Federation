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

## Operational Model

- **Dynamic Federation**: Allows entities to join or adjust their federation relationships dynamically, based on
  real-time verification of entity statements.
- **Trust Model**: Establishes a model where trust is derived from known and verifiable sources and can be dynamically
  adjusted according to real-time interactions and policy evaluations.
- **Conflict Resolution**: Defines how disputes or mismatches in federation policies among entities are resolved.

# Local Key Management System - Important Notice

Local Key Management Service is designed primarily for testing, development, and local experimentation
purposes. **It is not intended for use in production environments** due to significant security and compliance risks.

# Data Structure

## Entity Statement Overview

### 1. Definition

- An Entity Statement is a signed JWT containing information necessary for the Entity to participate in federations.
- **Entity Configuration**: An Entity Statement about itself.
- **Subordinate Statement**: An Entity Statement about an Immediate Subordinate Entity by a Superior Entity.

### 2. Requirements and Structure

- **Type**: JWT must be explicitly typed as `entity-statement+jwt`.
- **Signature**: Signed using the issuer’s private key, preferably using ECDSA with P-256 and SHA-256 (ES256).
- **Key ID (kid)**: The header must include the Key ID of the signing key.

### 3. Claims in an Entity Statement

- **iss (Issuer)**: Entity Identifier of the issuer.
- **sub (Subject)**: Entity Identifier of the subject.
- **iat (Issued At)**: Time the statement was issued.
- **exp (Expiration Time)**: Time after which the statement is no longer valid.
- **jwks (JSON Web Key Set)**: Public keys for verifying signatures. Required except in specific cases like Explicit
  Registration.
- **authority_hints** (Optional): Identifiers of Intermediate Entities or Trust Anchors that may issue Subordinate
  Statements.
- **metadata** (Optional): Represents the Entity’s Types and metadata.
- **metadata_policy** (Optional): Defines a metadata policy, applicable to the subject and its Subordinates.
- **constraints** (Optional): Defines Trust Chain constraints.
- **crit** (Optional): Specifies critical claims that must be understood and processed.
- **metadata_policy_crit** (Optional): Specifies critical metadata policy operators that must be understood and
  processed.
- **trust_marks** (Optional): Array of JSON objects, each representing a Trust Mark.
- **trust_mark_issuers** (Optional): Specifies trusted issuers of Trust Marks.
- **trust_mark_owners** (Optional): Specifies ownership of Trust Marks by different Entities.
- **source_endpoint** (Optional): URL to fetch the Entity Statement from the issuer.

### 4. Usage and Flexibility

- Entity Statements can include additional claims as required by applications and protocols.
- Metadata in Subordinate Statements overrides that in the Entity’s own configuration.

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

## How to Acquire a Bearer Token from the default Keycloak Server

The admin endpoints requires a Bearer token for authentication. To obtain a token, follow these steps:

1. Use a tool like Postman or cURL to send a **POST request** to the Keycloak server.

- **URL**:
  ```
  http://localhost:8082/realms/openid-federation/protocol/openid-connect/token
  ```

- **Headers**:
  ```
  Content-Type: application/x-www-form-urlencoded
  ```

- **Body**:
  ```
  grant_type=client_credentials
  client_id=openid-client
  client_secret=th1s1s4s3cr3tth4tMUSTb3ch4ng3d
  ```

2. **Example cURL Command**:
   ```bash
   curl -X POST http://localhost:8082/realms/openid-federation/protocol/openid-connect/token \
     -H "Content-Type: application/x-www-form-urlencoded" \
     -d "grant_type=client_credentials" \
     -d "client_id=openid-client" \
     -d "client_secret=th1s1s4s3cr3tth4tMUSTb3ch4ng3d"

3. **Example Response**:
   ```json
   {
     "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
     "expires_in": 300,
     "token_type": "Bearer",
     "not-before-policy": 0,
     "scope": "openid"
   }
   ```

4. **Use the Access Token**:  
   Add the `access_token` in the `Authorization` header as follows:
   ```
    Authorization: Bearer <access_token>
   ```

## Configuring Your Own OpenID Provider Using Environment Variables

To use your own OpenID Connect provider, configure the `OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI` environment variable.

### Steps to Configure

#### 1. Set the Environment Variable:

Update the following line on your environment configuration file or export it directly in your shell:

   ```bash
   export OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI=https://my-new-provider/realms/openid-federation
   ```

#### 2. Verify the Configuration:

Run the following command to confirm that the environment variable is correctly set:

   ```bash
   echo $OAUTH2_RESOURCE_SERVER_JWT_ISSUER_URI
   ```

The output should display:

   ```bash
  https://my-new-provider/realms/openid-federation
   ```

#### 3. Restart Your Application:

After setting the environment variable, restart your application to apply the changes.

#### 4. Validate Token Issuance:

Ensure the application validates tokens issued by the new provider. The issuer URI should match
the `iss` claim in the JWT tokens.
