(() => {
  "use strict";

  const API = "https://api.github.com/repos/heobeep/BitChord/releases/latest";
  const RELEASES = "https://github.com/heobeep/BitChord/releases";
  const DEVICE_SUFFIX = {
    universal: ["-universal.apk", "_universal.apk"],
    "arm64-v8a": ["_arm64-v8a.apk"],
    "armeabi-v7a": ["_armeabi-v7a.apk"],
    x86_64: ["_x86_64.apk"]
  };
  const DEVICE_COPY = {
    universal: "Universal includes all supported architectures. It is larger, but you do not need to identify your phone’s processor.",
    "arm64-v8a": "A smaller download for most modern Android phones and tablets.",
    "armeabi-v7a": "For older 32-bit ARM Android phones. Most newer phones use ARM64 instead.",
    x86_64: "For Android emulators and x86_64 Chromebooks. Most Android phones use ARM."
  };

  const el = (id) => document.getElementById(id);
  const state = { release: null, assets: [], mode: "keep", device: "universal", replaceSafe: false, loadFailed: false };

  function setTheme(theme) {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem("bitchord-theme", theme); } catch (_) {}
    const button = el("themeToggle");
    if (button) button.setAttribute("aria-label", theme === "dark" ? "Switch to light theme" : "Switch to dark theme");
  }

  function initTheme() {
    let saved = "";
    try { saved = localStorage.getItem("bitchord-theme") || ""; } catch (_) {}
    const preferred = window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
    setTheme(saved || preferred);
    el("themeToggle").addEventListener("click", () => {
      setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
    });
  }

  function findApk(mode, device) {
    const prefix = "BitChord-Credits-" + (mode === "keep" ? "Keep-" : "Replace-");
    const endings = DEVICE_SUFFIX[device] || [];
    return state.assets.find(asset =>
      asset.name.startsWith(prefix) &&
      asset.name.toLowerCase().endsWith(".apk") &&
      endings.some(ending => asset.name.endsWith(ending))
    ) || null;
  }

  function findChecksum(apk) {
    if (!apk) return null;
    const exact = state.assets.find(asset => asset.name === apk.name + ".sha256");
    return exact || state.assets.find(asset => asset.name === apk.name.replace(/\.apk$/i, ".apk.sha256")) || null;
  }

  function setReleaseState(label, kind) {
    const node = el("releaseState");
    node.className = "release-state" + (kind ? " is-" + kind : "");
    node.lastElementChild.textContent = label;
  }

  function setMode(mode) {
    if (mode === "replace" && !state.replaceSafe) return;
    state.mode = mode;
    document.querySelectorAll("[data-mode]").forEach(button => {
      const selected = button.dataset.mode === mode;
      button.classList.toggle("is-selected", selected);
      button.setAttribute("aria-pressed", selected ? "true" : "false");
    });
    render();
  }

  function render() {
    const mode = state.mode;
    const device = state.device;
    const apk = findApk(mode, device);
    const checksum = findChecksum(apk);
    const title = state.release ? state.release.name || state.release.tag_name : "No release loaded";
    el("releaseTitle").textContent = state.release ? title : "No public APK release found yet";
    el("releaseSubtitle").textContent = state.release
      ? "Published " + new Date(state.release.published_at || state.release.created_at).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" })
      : "Use the Releases page to check for the first published installer.";
    if (state.release) setReleaseState("Release found", "ready");
    else setReleaseState(state.loadFailed ? "Unavailable" : "No release", "error");

    el("deviceHint").textContent = DEVICE_COPY[device];
    const note = el("modeNote");
    if (mode === "keep") {
      note.className = "safety-note";
      note.innerHTML = '<span class="safety-note__icon">✓</span><p><strong>Recommended · Your original app stays untouched.</strong><br>Keep Original installs separately. Your existing BitChord and its data are left in place.</p>';
    } else {
      note.className = "safety-note is-warning";
      note.innerHTML = '<span class="safety-note__icon">!</span><p><strong>Compatibility checked · Read the release report before installing.</strong><br>This Replace build is included only when CI verified the checked upstream package, signer and version.</p>';
    }

    el("downloadName").textContent = apk ? apk.name : (state.release ? "This build is not available in the latest release" : "Waiting for a published build");
    el("fileTag").textContent = apk ? (device === "universal" ? "UNIVERSAL" : device.toUpperCase()) : "APK";
    const button = el("downloadButton");
    if (apk) {
      button.href = apk.browser_download_url;
      button.classList.remove("is-disabled");
      button.setAttribute("aria-disabled", "false");
      button.textContent = "Download " + (mode === "keep" ? "Keep Original" : "Replace") + " APK";
      const arrow = document.createElement("span");
      arrow.setAttribute("aria-hidden", "true");
      arrow.textContent = "↓";
      button.appendChild(arrow);
      el("downloadStatus").textContent = "This exact installer is attached to " + (state.release.tag_name || "the latest release") + ". Verify its SHA-256 checksum after downloading.";
    } else {
      button.href = RELEASES;
      button.classList.add("is-disabled");
      button.setAttribute("aria-disabled", "true");
      button.textContent = mode === "replace" ? "Replace build not published" : "No matching APK in latest release";
      el("downloadStatus").textContent = mode === "replace"
        ? "Replace downloads are withheld unless the compatibility check passes. Keep Original is the safe route."
        : "No matching installer is available in the current release. Check the release page for details.";
    }
    const checksumLink = el("checksumLink");
    checksumLink.href = checksum ? checksum.browser_download_url : RELEASES;
    checksumLink.setAttribute("aria-disabled", checksum ? "false" : "true");
    checksumLink.style.opacity = checksum ? "1" : ".55";
    checksumLink.textContent = checksum ? "SHA-256 checksum ↗" : "Checksum unavailable";
  }

  async function checkReplaceCompatibility() {
    const replaceNames = ["universal", "arm64-v8a", "armeabi-v7a", "x86_64"];
    const allReplaceAssets = replaceNames.every(device => !!findApk("replace", device));
    const report = state.assets.find(asset => asset.name === "INSTALL-COMPATIBILITY.txt");
    if (!allReplaceAssets || !report) return false;

    // Fail closed: never expose Replace if the report cannot be retrieved and read.
    try {
      const response = await fetch(report.browser_download_url, { cache: "no-store" });
      if (!response.ok) return false;
      const text = await response.text();
      return /^INSTALL COMPATIBILITY: MATCH\s*-/m.test(text.trim());
    } catch (_) {
      return false;
    }
  }

  async function loadRelease() {
    try {
      const response = await fetch(API, { headers: { Accept: "application/vnd.github+json" }, cache: "no-store" });
      if (response.status === 404) {
        state.release = null;
        render();
        return;
      }
      if (!response.ok) throw new Error("Release lookup returned " + response.status);
      state.release = await response.json();
      state.assets = Array.isArray(state.release.assets) ? state.release.assets : [];
      state.replaceSafe = await checkReplaceCompatibility();
      const replaceButton = el("replaceMode");
      replaceButton.disabled = !state.replaceSafe;
      replaceButton.title = state.replaceSafe ? "Compatibility report says MATCH" : "Not offered: all Replace assets and a readable MATCH report are required";
      if (state.mode === "replace" && !state.replaceSafe) state.mode = "keep";
      render();
    } catch (error) {
      state.loadFailed = true;
      state.release = null;
      state.assets = [];
      render();
      el("releaseSubtitle").textContent = "Could not verify release assets right now. Downloads stay disabled until they can be checked.";
      el("downloadStatus").textContent = "The release lookup failed or was rate-limited. Open Releases directly; this page will not guess a download URL.";
      el("downloadButton").textContent = "Open GitHub Releases ↗";
      el("downloadButton").classList.remove("is-disabled");
      el("downloadButton").setAttribute("aria-disabled", "false");
      el("downloadButton").href = RELEASES;
    }
  }

  function initControls() {
    document.querySelectorAll("[data-mode]").forEach(button => {
      button.addEventListener("click", () => setMode(button.dataset.mode));
    });
    el("deviceSelect").addEventListener("change", event => {
      state.device = event.target.value;
      render();
    });
    el("chooseUniversal").addEventListener("click", () => {
      state.device = "universal";
      el("deviceSelect").value = "universal";
      render();
      document.querySelector(".download-card").scrollIntoView({ behavior: "smooth", block: "center" });
    });
    el("downloadButton").addEventListener("click", event => {
      if (el("downloadButton").getAttribute("aria-disabled") === "true") event.preventDefault();
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    initTheme();
    initControls();
    loadRelease();
  });
})();
