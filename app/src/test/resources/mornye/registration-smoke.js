// Minimal contract fixture for the native Mornye runtime POC.
// This fixture intentionally uses only the registration contract. It does not
// assume any optional host APIs or provider-specific behavior.

registerExtension({
  id: "bitchord-runtime-smoke",
  name: "BitChord Runtime Smoke Test",
  version: "1.0.0",
  description: "Deterministic fixture used to verify Mornye registration and calls.",
  async initialize() {
    this.initialized = true;
    return { success: true };
  },
  async searchTracks(query) {
    if (!this.initialized) {
      throw new Error("initialize() must run before searchTracks()");
    }
    return [{
      id: "fixture-track-001",
      title: "Runtime Smoke Test",
      artist: "BitChord Test Fixture",
      album: "Mornye Compatibility",
      duration_ms: 120000,
      query
    }];
  },
  async cleanup() {
    this.initialized = false;
    return { success: true };
  }
});
