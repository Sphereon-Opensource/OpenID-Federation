package com.sphereon.openid.fed.client

/**
 * Internal placeholder to ensure this facade module compiles on all platforms.
 *
 * This facade module re-exports both -public (interfaces) and -impl (implementations)
 * modules for backward compatibility. New code should prefer depending directly on:
 *
 * - `openid-federation-client-public`: For interfaces only (minimal dependencies)
 * - `openid-federation-client-impl`: For implementations with DI support
 *
 * This module provides all exports transitively through its dependencies.
 */
internal object ClientModule
