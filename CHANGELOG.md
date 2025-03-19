# Changelog

## 0.6.0 - 20250319
### Documentation

- **Postman Collection:** Added a new Postman collection to the documentation, providing ready-to-use API examples including OAuth2 integration.
- **Enhanced README:** Improved examples and instructions for:
  - Setting up environment variables (including guidance on copying the example file).
  - Deploying with Docker and Docker Compose.
  - Configuring the Key Management System with details for in-memory, AWS, and Azure setups.
- **Clarity and Consistency:** Revised text formatting and clarified descriptions for better readability.

### Deployment
- **Docker environment variables:** Unified usage of .env and .env.local files for environment values. Moved .env to .env.example as a user should create a .env or .env.local file themselves.
  - WARNING: This potentially means you will have to migrate/create the .env or .env.local file.

### API and codebase
- **JSON Payload Updates:** Standardized JSON keys (e.g., updated "dry-run" to "dry_run") in multiple API examples.
- **Key Management Examples:** Updated examples to show usage of parameters like `kms_key_ref` and provided guidance on signature algorithms.
- **Migrations:** The DB should migrate to support the new kms_key_ref values a user can set for keys. Existing keys will get their `kid` value for the `kms_key_ref`
- **Dependency updates:** Moved to latest versions of Kotlin serialization, coroutines and ktor
 
