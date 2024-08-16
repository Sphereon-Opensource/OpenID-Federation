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

## Key Concepts

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
