/**
 * TypeScript tests for the FederationClient JS export.
 *
 * These tests import the compiled Kotlin/JS library and exercise the
 * createFederationClient factory which wires up the full DI graph.
 *
 * By default, tests that require a real server are skipped unless
 * FEDERATION_SERVER_URL is set in the environment.
 */

// The compiled library path will be resolved relative to the build output.
// After `jsNodeProductionLibraryDistribution`, the output is at:
// ../../../../build/dist/js/productionLibrary/
const LIB_PATH =
  "../../../../../build/dist/js/productionLibrary/@sphereon/openid-federation-client-test-js.mjs";

describe("FederationClient", () => {
  let client: any;

  beforeAll(async () => {
    try {
      const lib = await import(LIB_PATH);
      client = lib.createFederationClient();
    } catch (e) {
      console.error(
        "Failed to load compiled library. Run `./gradlew :modules:openid-federation-client-test-js:jsNodeProductionLibraryDistribution` first.",
        e
      );
      throw e;
    }
  });

  test("client should be instantiated", () => {
    expect(client).toBeDefined();
  });

  const serverUrl = process.env.FEDERATION_SERVER_URL;
  const trustAnchorUrl = process.env.FEDERATION_TRUST_ANCHOR_URL;

  const describeWithServer = serverUrl ? describe : describe.skip;

  describeWithServer("with real federation server", () => {
    test("entityConfigurationStatementGet returns typed result", async () => {
      const result = await client.entityConfigurationStatementGet(serverUrl);
      expect(result).toBeDefined();
      expect(result.iss).toBeDefined();
    });

    test("trustChainResolve returns typed result", async () => {
      if (!trustAnchorUrl) {
        console.log("Skipping: FEDERATION_TRUST_ANCHOR_URL not set");
        return;
      }
      const result = await client.trustChainResolve(serverUrl, [
        trustAnchorUrl,
      ]);
      expect(result).toBeDefined();
    });
  });

  const swamidLeaf = "https://oidf-dco-poc-rp-1.swamid.se";

  describeWithServer("SWAMID leaf (2 layers deep)", () => {
    test("entityConfigurationStatementGet for SWAMID leaf", async () => {
      const result = await client.entityConfigurationStatementGet(swamidLeaf);
      expect(result).toBeDefined();
      expect(result.iss).toBe(swamidLeaf);
    });

    test("trustChainResolve for SWAMID leaf (2 intermediaries)", async () => {
      if (!trustAnchorUrl) {
        console.log("Skipping: FEDERATION_TRUST_ANCHOR_URL not set");
        return;
      }
      const result = await client.trustChainResolve(swamidLeaf, [
        trustAnchorUrl,
      ]);
      expect(result).toBeDefined();
      expect(result.trustChain).toBeDefined();
      expect(result.trustChain.size).toBeGreaterThanOrEqual(3);
    });
  });
});
