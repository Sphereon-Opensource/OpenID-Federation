/**
 * CJS preload shim that provides `require` to ESM modules via globalThis.
 *
 * kotlinx-io uses `eval('require')('os')` internally, which fails in ESM mode
 * because `require` is not available in ES modules. This shim makes `require`
 * available on globalThis so eval-based require calls work.
 *
 * Loaded via Node.js --require flag before ESM test modules.
 * See: https://github.com/Kotlin/kotlinx-io/issues/345
 */
const { createRequire } = require('module');
globalThis.require = createRequire(__filename);
