registerExtension({
  id: "bitchord-runtime-smoke",
  name: "BitChord Runtime Smoke Test",
  version: "1.0.0",
  async initialize() {
    this.initialized = true;
    return { success: true };
  },
  async searchTracks(query, limit) {
    if (!this.initialized) throw new Error("initialize() must run first");
    return [{
      id: "fixture-track-001",
      name: "Runtime Smoke Test",
      artists: "BitChord Test Fixture",
      album_name: "Mornye Compatibility",
      duration_ms: 120000,
      query,
      limit
    }];
  },
  async cleanup() {
    this.initialized = false;
    return { success: true };
  }
});
