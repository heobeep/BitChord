#!/usr/bin/env python3
"""Apply maintained fork-specific polish after the embedded Credits patch."""
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Could not find the expected source block for: {label}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")



def mark_patch_strings_without_translations(resources_dir: Path) -> None:
    """Keep generated English-only credits strings out of the translation contract.

    The embedded upstream patch adds new strings to values/strings.xml but does
    not ship translations for every supported locale. Android will correctly
    fall back to the default English resource when a locale-specific value is
    absent; mark only those newly-added base entries as non-translatable so
    LocaleStringsTest continues to enforce completeness for genuinely
    translatable app strings.
    """
    import re
    import xml.etree.ElementTree as ET

    base_file = resources_dir / "values/strings.xml"
    localized_files = sorted(resources_dir.glob("values-*/strings.xml"))
    if not base_file.is_file() or not localized_files:
        raise SystemExit("Could not inspect Android string resources for locale coverage.")

    resource_tags = {"string", "plurals", "string-array"}

    def keys(path: Path) -> set[tuple[str, str]]:
        root = ET.parse(path).getroot()
        return {
            (element.tag, element.attrib["name"])
            for element in root
            if element.tag in resource_tags and element.attrib.get("name")
        }

    base_root = ET.parse(base_file).getroot()
    base_keys = {
        (element.tag, element.attrib["name"])
        for element in base_root
        if element.tag in resource_tags
        and element.attrib.get("name")
        and element.attrib.get("translatable") != "false"
    }
    locale_keys = [keys(path) for path in localized_files]
    missing = {
        key
        for key in base_keys
        if any(key not in translated for translated in locale_keys)
    }
    if not missing:
        print("All default strings are present in every locale.")
        return

    source = base_file.read_text(encoding="utf-8")
    for tag, name in sorted(missing):
        pattern = re.compile(
            rf'(<{re.escape(tag)}\b(?=[^>]*\bname="{re.escape(name)}")[^>]*)(>)'
        )
        source, count = pattern.subn(
            lambda match: match.group(1) + ' translatable="false"' + match.group(2),
            source,
            count=1,
        )
        if count != 1:
            raise SystemExit(f"Could not mark generated English-only string as non-translatable: {name}")

    base_file.write_text(source, encoding="utf-8")
    print(
        f"Marked {len(missing)} generated English-only string resource(s) "
        "as non-translatable; localized resources remain untouched."
    )



def matching_kotlin_brace(source: str, opening: int) -> int:
    """Find a Kotlin block's closing brace without counting braces in strings/comments."""
    if opening < 0 or source[opening] != "{":
        raise SystemExit("Kotlin block opening brace not found")
    depth = 0
    index = opening
    while index < len(source):
        if source.startswith('"""', index):
            end = source.find('"""', index + 3)
            if end < 0:
                raise SystemExit("Unterminated Kotlin raw string")
            index = end + 3
            continue
        if source.startswith("//", index):
            end = source.find("\n", index + 2)
            index = len(source) if end < 0 else end + 1
            continue
        if source.startswith("/*", index):
            end = source.find("*/", index + 2)
            if end < 0:
                raise SystemExit("Unterminated Kotlin block comment")
            index = end + 2
            continue
        char = source[index]
        if char == '"':
            index += 1
            while index < len(source):
                if source[index] == "\\":
                    index += 2
                elif source[index] == '"':
                    index += 1
                    break
                else:
                    index += 1
            continue
        if char == "'":
            index += 1
            while index < len(source):
                if source[index] == "\\":
                    index += 2
                elif source[index] == "'":
                    index += 1
                    break
                else:
                    index += 1
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise SystemExit("Unterminated Kotlin block")


def add_credits_fm_shortcuts(source: str) -> str:
    """Put native-looking links below the Credits.fm card and its description."""
    if "Get a free Credits.fm API key" in source:
        return source
    anchor = "onClick = onOpenCreditsFmKey,"
    anchor_at = source.find(anchor)
    if anchor_at < 0:
        raise SystemExit("Credits.fm settings row not found for shortcut links")
    group_at = source.rfind("SettingsGroup(", 0, anchor_at)
    if group_at < 0:
        raise SystemExit("Credits.fm SettingsGroup not found")
    opening = source.find("{", group_at, anchor_at)
    if opening < 0:
        raise SystemExit("Credits.fm SettingsGroup content block not found")
    closing = matching_kotlin_brace(source, opening)
    links = r'''


        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
        ) {
            Text(
                text = "Get a free Credits.fm API key",
                modifier = Modifier
                    .clickable { creditsFmUriHandler.openUri("https://credits.fm/api") }
                    .padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.Underline,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Manage Song Info & Credits",
                modifier = Modifier
                    .clickable(onClick = onOpenSongInfoCreditsSettings)
                    .padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.Underline,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }'''
    return source[:closing + 1] + links + source[closing + 1:]



def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    root = parser.parse_args().repo.resolve()

    repository = root / "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/SongInfoRepository.kt"
    replace_once(
        repository,
        r'''            if (role == null || name.isNullOrBlank()) return
            val clean = name.trim()
            val key = "${role.lowercase()}|${clean.lowercase()}"
''',
        r'''            if (role == null || name.isNullOrBlank()) return
            val clean = name.trim()
            // IPI numbers are registry identifiers, not contributor display names.
            // Match identifier-only values so names like "2Pac" and "50 Cent" survive.
            if (Regex("(?i)^(?:IPI\\s*[:#-]?\\s*)?\\d{9,11}$").matches(clean)) return
            val key = "${role.lowercase()}|${clean.lowercase()}"
''',
        "Credits.fm IPI identifier filtering",
    )

    # Make Credits.fm's optional key editor use the same frosted alert shell as
    # the other BitChord integrations. The alert itself lives in AccountAlerts
    # and is hosted at MainActivity level so its scrim covers the player/nav bar.
    account_alerts = root / "app/src/main/java/com/music/bitchord/ui/components/AccountAlerts.kt"
    alert_source = account_alerts.read_text(encoding="utf-8")
    alert_marker = """@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LastfmLoginAlert("""
    if "fun CreditsFmApiKeyAlert(" not in alert_source:
        if alert_marker not in alert_source:
            raise SystemExit("Credits.fm native alert insertion point not found")
        credits_alert = r'''@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun CreditsFmApiKeyAlert(
    hazeState: HazeState,
    savedKey: String,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var keyInput by remember(savedKey) { mutableStateOf(savedKey) }
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.credits_fm_api_key),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.credits_fm_key_optional),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                placeholder = stringResource(R.string.credits_fm_api_key),
                isPassword = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (keyInput.isNotBlank()) onSave(keyInput.trim()) },
                ),
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.credits_fm_save),
            emphasised = true,
            onClick = { onSave(keyInput.trim()) },
            enabled = keyInput.isNotBlank(),
        )
        if (savedKey.isNotBlank()) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.credits_fm_remove),
                emphasised = false,
                destructive = true,
                onClick = onRemove,
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.credits_fm_cancel),
            emphasised = false,
            onClick = onDismiss,
        )
    }
}

'''
        alert_source = alert_source.replace(alert_marker, credits_alert + alert_marker, 1)
        account_alerts.write_text(alert_source, encoding="utf-8")

    # The account page keeps the saved/not-saved status row, but its click opens
    # the root-level native alert instead of a stock Material AlertDialog.
    account_screen = root / "app/src/main/java/com/music/bitchord/ui/screens/AccountAndScrobblingScreen.kt"
    replace_once(
        account_screen,
        "    onOpenLastfmLogin: () -> Unit,\n    onOpenDiscord: () -> Unit,",
        "    onOpenLastfmLogin: () -> Unit,\n    onOpenCreditsFmKey: () -> Unit,\n    onOpenDiscord: () -> Unit,",
        "Credits.fm key editor callback",
    )
    replace_once(
        account_screen,
        "    onOpenCreditsFmKey: () -> Unit,\n    onOpenDiscord: () -> Unit,",
        "    onOpenCreditsFmKey: () -> Unit,\n    onOpenSongInfoCreditsSettings: () -> Unit,\n    onOpenDiscord: () -> Unit,",
        "Song Info & Credits settings shortcut callback",
    )

    account_text = account_screen.read_text(encoding="utf-8")
    account_text = account_text.replace(
        "    var showCreditsFmKeyDialog by remember { mutableStateOf(false) }\n",
        "",
    ).replace(
        "    var creditsFmKeyDraft by remember { mutableStateOf(\"\") }\n",
        "",
    )
    old_key_click = """                onClick = {
                    creditsFmKeyDraft = creditsFmApiKey
                    showCreditsFmKeyDialog = true
                },"""
    if old_key_click in account_text:
        account_text = account_text.replace(old_key_click, "                onClick = onOpenCreditsFmKey,", 1)

    def remove_kotlin_block(source: str, marker: str) -> str:
        start_at = source.find(marker)
        if start_at < 0:
            return source
        opening = source.find("{", start_at)
        if opening < 0:
            raise SystemExit("Could not find the opening brace for " + marker)
        depth = 0
        quoted = False
        escaped = False
        for index in range(opening, len(source)):
            char = source[index]
            if quoted:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    quoted = False
            elif char == '"':
                quoted = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    end_at = index + 1
                    while end_at < len(source) and source[end_at] == "\n":
                        end_at += 1
                    return source[:start_at] + source[end_at:]
        raise SystemExit("Could not find the end of " + marker)

    account_text = remove_kotlin_block(account_text, "    if (showCreditsFmKeyDialog) {")

    package_line = "package com.music.bitchord.ui.screens\n"
    for import_line in (
        "import androidx.compose.foundation.clickable",
        "import androidx.compose.ui.platform.LocalUriHandler",
        "import androidx.compose.ui.text.style.TextDecoration",
        "import androidx.compose.ui.unit.sp",
    ):
        if import_line not in account_text:
            account_text = account_text.replace(package_line, package_line + import_line + "\n", 1)

    function_open = "    modifier: Modifier = Modifier,\n) {"
    if "val creditsFmUriHandler = LocalUriHandler.current" not in account_text:
        if function_open not in account_text:
            raise SystemExit("Account integration composable body marker not found")
        account_text = account_text.replace(
            function_open,
            function_open + "\n    val creditsFmUriHandler = LocalUriHandler.current",
            1,
        )
    account_text = add_credits_fm_shortcuts(account_text)
    account_screen.write_text(account_text, encoding="utf-8")

    # Root-level presentation mirrors the ListenBrainz/Last.fm alert lifecycle:
    # it survives route layout and places the scrim above the mini-player.
    main_activity = root / "app/src/main/java/com/music/bitchord/MainActivity.kt"
    replace_once(
        main_activity,
        "import com.music.bitchord.ui.components.ListenBrainzTokenAlert\n",
        "import com.music.bitchord.ui.components.ListenBrainzTokenAlert\nimport com.music.bitchord.ui.components.CreditsFmApiKeyAlert\n",
        "Credits.fm alert import",
    )
    replace_once(
        main_activity,
        "    var showLastfmLogin by remember { mutableStateOf(false) }\n",
        "    var showLastfmLogin by remember { mutableStateOf(false) }\n    var showCreditsFmKeyAlert by remember { mutableStateOf(false) }\n",
        "Credits.fm alert state",
    )
    replace_once(
        main_activity,
        "        BackHandler(enabled = showLastfmLogin) { showLastfmLogin = false }\n",
        "        BackHandler(enabled = showLastfmLogin) { showLastfmLogin = false }\n        BackHandler(enabled = showCreditsFmKeyAlert) { showCreditsFmKeyAlert = false }\n",
        "Credits.fm alert back handling",
    )
    replace_once(
        main_activity,
        "                                onOpenLastfmLogin = { showLastfmLogin = true },\n",
        "                                onOpenLastfmLogin = { showLastfmLogin = true },\n                                onOpenCreditsFmKey = { showCreditsFmKeyAlert = true },\n                                onOpenSongInfoCreditsSettings = {\n                                    navigator.pushAll(\n                                        AppRoute.Settings,\n                                        AppRoute.SettingsSection(com.music.bitchord.ui.screens.SettingsCategory.APPEARANCE),\n                                        AppRoute.SettingsChoice(com.music.bitchord.ui.screens.SettingsChoiceKind.SONG_INFO_CREDITS),\n                                    )\n                                },\n",
        "Credits.fm account page callback",
    )
    credits_alert_host = """        if (showCreditsFmKeyAlert) {
            CreditsFmApiKeyAlert(
                hazeState = hazeState,
                savedKey = AppSettings.creditsFmApiKey.value,
                onSave = {
                    AppSettings.setCreditsFmApiKey(it)
                    showCreditsFmKeyAlert = false
                },
                onRemove = {
                    AppSettings.setCreditsFmApiKey("")
                    showCreditsFmKeyAlert = false
                },
                onDismiss = { showCreditsFmKeyAlert = false },
            )
        }

"""
    replace_once(
        main_activity,
        "        if (showLastfmLogin) {\n",
        credits_alert_host + "        if (showLastfmLogin) {\n",
        "Credits.fm root alert host",
    )

    # A resolved Credits.fm response can be partial. Query the flat and graph
    # endpoints whenever we have a concrete ISRC, then merge by role + person.
    repository_text = repository.read_text(encoding="utf-8")
    # YouTube's auto-generated Topic byline is not part of the artist name.
    # Remove it before the existing Credits.fm and ListenBrainz title/artist resolver.
    lookup_signature = """    private suspend fun loadSongCredits(
        title: String,
        artist: String,
        album: String?,
        creditsFmApiKey: String,
        listenBrainzToken: String,
    ): List<SongCredit> {
"""
    lookup_with_searchable_artist = lookup_signature + r'''        val searchableArtist = artist.replace(
            Regex("""\s*[-–—]\s*Topic$""", RegexOption.IGNORE_CASE),
            "",
        ).trim()
'''
    if "val searchableArtist = artist.replace(" not in repository_text:
        if lookup_signature not in repository_text:
            raise SystemExit("Credits.fm loadSongCredits signature not found")
        repository_text = repository_text.replace(
            lookup_signature,
            lookup_with_searchable_artist,
            1,
        )
    repository_text = repository_text.replace(
        'put("artist", artist)',
        'put("artist", searchableArtist)',
        1,
    )
    repository_text = repository_text.replace(
        'parameter("artist_name", artist)',
        'parameter("artist_name", searchableArtist)',
        1,
    )
    repository_text = repository_text.replace(
        'parameter("limit", 5)',
        'parameter("limit", 100)',
    )
    old_query = 'val query = "recording:' + chr(36) + '{luceneQuote(title)} AND artist:' + chr(36) + '{luceneQuote(artist)}"'
    new_query = 'val query = "recording:" + luceneQuote(title)'
    if old_query in repository_text:
        repository_text = repository_text.replace(old_query, new_query, 1)

    repository_text = repository_text.replace(
        "if (isrc != null && credits.isEmpty()) {",
        "if (isrc != null) {",
    )

    # Search by recording title to avoid dropping candidates when provider artist
    # strings differ, as they often do with multi-writer / film-music credits.
    # The local matcher below still requires a close title AND matching artist.
    matcher_start = "    fun bestRecordingMbid(root: JsonElement, title: String, artist: String): String? {"
    matcher_end = "    fun findFirstString(root: JsonElement, keys: Set<String>): String? {"
    matcher_a = repository_text.find(matcher_start)
    matcher_b = repository_text.find(matcher_end, matcher_a)
    if matcher_a < 0 or matcher_b < 0:
        raise SystemExit("MusicBrainz recording matcher source block not found")
    new_matcher = r'''    fun bestRecordingMbid(root: JsonElement, title: String, artist: String): String? {
        val recordings = (root as? JsonObject)?.get("recordings") as? JsonArray ?: return null
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(
            artist.replace(Regex("""\s*[-–—]\s*Topic$""", RegexOption.IGNORE_CASE), ""),
        )
        val candidates = recordings.mapNotNull { it as? JsonObject }.mapNotNull { recording ->
            val id = recording.string("id") ?: return@mapNotNull null
            val candidateTitle = normalize(recording.string("title").orEmpty())
            val artistCredits = recording["artist-credit"] as? JsonArray
            val candidateArtist = artistCredits?.joinToString("") { part ->
                val credit = part as? JsonObject
                (credit?.string("name") ?: credit?.obj("artist")?.string("name") ?: "") +
                    (credit?.string("joinphrase") ?: "")
            }?.let { value ->
                normalize(value.replace(Regex("""\s*[-–—]\s*Topic$""", RegexOption.IGNORE_CASE), ""))
            }.orEmpty()
            val titleScore = when {
                candidateTitle == wantedTitle -> 3
                candidateTitle.startsWith(wantedTitle + " ") -> 2
                wantedTitle.startsWith(candidateTitle + " ") -> 2
                else -> 0
            }
            val artistScore = when {
                candidateArtist.isBlank() || wantedArtist.isBlank() -> 0
                candidateArtist == wantedArtist -> 3
                candidateArtist.contains(wantedArtist) -> 2
                wantedArtist.contains(candidateArtist) &&
                    candidateArtist.length.toFloat() / wantedArtist.length.coerceAtLeast(1) >= 0.72f -> 1
                else -> 0
            }
            if (titleScore < 2 || artistScore < 1) null
            else Triple(id, titleScore * 10 + artistScore, candidateTitle.length)
        }
        return candidates.maxWithOrNull(
            compareBy<Triple<String, Int, Int>> { it.second }.thenBy { it.third },
        )?.first
    }

'''
    repository_text = repository_text[:matcher_a] + new_matcher + repository_text[matcher_b:]

    # A MusicBrainz recording can have an ISRC even when its relationship list
    # is empty. Use that exact ID to ask Credits.fm for recording-level credits.
    old_mb_add = "                if (recording != null) addAll(SongCreditsParser.fromMusicBrainz(recording))"
    new_mb_add = r'''                if (recording != null) {
                    addAll(SongCreditsParser.fromMusicBrainz(recording))
                    val musicBrainzIsrc = ((recording as? JsonObject)?.get("isrcs") as? JsonArray)
                        ?.mapNotNull { item ->
                            (item as? JsonObject)?.get("id")?.let { value ->
                                (value as? JsonPrimitive)?.contentOrNull
                            }
                        }
                        ?.firstOrNull { Regex("[A-Za-z]{2}[A-Za-z0-9]{3}[0-9]{7}").matches(it) }

                    if (isrc == null && musicBrainzIsrc != null) {
                        isrc = musicBrainzIsrc
                        val byIsrc = attempt {
                            val response = client.get("https://api.credits.fm/v1/isrc/" + musicBrainzIsrc) {
                                if (creditsFmApiKey.isNotBlank()) header("x-api-key", creditsFmApiKey.trim())
                                parameter("contribute", "false")
                            }
                            check(response.status.value in 200..299) { "Credits.fm ISRC lookup failed" }
                            Json.parseToJsonElement(response.bodyAsText())
                        }.getOrNull()
                        if (byIsrc != null) addAll(SongCreditsParser.fromCreditsFm(byIsrc))

                        val graph = attempt {
                            val response = client.get("https://api.credits.fm/v1/graph/isrc/" + musicBrainzIsrc) {
                                if (creditsFmApiKey.isNotBlank()) header("x-api-key", creditsFmApiKey.trim())
                                parameter("depth", 2)
                                parameter("people", 1)
                            }
                            check(response.status.value in 200..299) { "Credits.fm graph lookup failed" }
                            Json.parseToJsonElement(response.bodyAsText())
                        }.getOrNull()
                        if (graph != null) addAll(SongCreditsParser.fromCreditsFm(graph))
                    }
                }'''
    if old_mb_add not in repository_text:
        raise SystemExit("MusicBrainz recording credit merge marker not found")
    repository_text = repository_text.replace(old_mb_add, new_mb_add, 1)
    repository.write_text(repository_text, encoding="utf-8")

    updater = root / "app/src/main/java/com/music/bitchord/data/AppUpdateChecker.kt"
    replace_once(
        updater,
        "https://api.github.com/repos/kushagrasinghx/BitChord/releases/latest",
        "https://api.github.com/repos/heobeep/BitChord/releases/latest",
        "Credits fork release endpoint",
    )
    replace_once(
        updater,
        '            val latest = tag.removePrefix("v")',
        '''            val latest = tag.substringAfterLast('v')
                .takeIf { it.firstOrNull()?.isDigit() == true }
                ?: tag.removePrefix("v")''',
        "Credits release tag version parsing",
    )

    gradle = root / "app/build.gradle.kts"
    gradle_text = gradle.read_text(encoding="utf-8")

    # One permanent signing identity is shared by both install modes. Keep it
    # in signingConfigs so both the side-by-side benchmark and clean-switch
    # release variants are signed identically.
    signing_marker = "    signingConfigs {"
    signing_start = gradle_text.find(signing_marker)
    if signing_start < 0:
        raise SystemExit("Android signingConfigs block not found")
    signing_open = gradle_text.find("{", signing_start)
    signing_close = matching_kotlin_brace(gradle_text, signing_open)
    if 'create("creditsStable")' not in gradle_text:
        signing_config = r'''
        create("creditsStable") {
            storeFile = rootProject.file(".ci/credits-signing.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
'''
        gradle_text = gradle_text[:signing_close] + signing_config + gradle_text[signing_close:]

    benchmark_debug_signing = 'signingConfig = signingConfigs.getByName("debug")'
    if benchmark_debug_signing in gradle_text:
        gradle_text = gradle_text.replace(
            benchmark_debug_signing,
            'signingConfig = signingConfigs.getByName("creditsStable")',
            1,
        )
    elif 'signingConfig = signingConfigs.getByName("creditsStable")' not in gradle_text:
        raise SystemExit("Could not configure the side-by-side APK signing key")

    # The replacement APK uses the original package ID. Its signing certificate
    # is checked against the official upstream APK in CI. If the certificates
    # differ, users must uninstall the original before installing this build.
    release_signing = '            signingConfig = signingConfigs.findByName("release")'
    release_signing_replacement = '''            signingConfig = if (providers.gradleProperty("bitchord.credits.cleanSwitch").orNull == "true") {
                signingConfigs.getByName("creditsStable")
            } else {
                signingConfigs.findByName("release")
            }'''
    if release_signing in gradle_text:
        gradle_text = gradle_text.replace(release_signing, release_signing_replacement, 1)
    elif release_signing_replacement not in gradle_text:
        raise SystemExit("Could not locate the release signing configuration")

    # The Credits product flavor switches package ID, app label and updater
    # asset prefix from a Gradle property. No source edits are needed between
    # the keep-original and replace-original builds.
    credits_marker = 'create("credits")'
    credits_start = gradle_text.find(credits_marker)
    if credits_start < 0:
        raise SystemExit("Credits product flavor was not generated by the embedded patch")
    credits_open = gradle_text.find("{", credits_start)
    credits_close = matching_kotlin_brace(gradle_text, credits_open)
    credits_block = gradle_text[credits_start:credits_close + 1]

    import re
    app_id_line = re.compile(r"(?m)^([ \t]*)applicationId\s*=.*$")
    app_id_value = (
        'applicationId = if (providers.gradleProperty("bitchord.credits.cleanSwitch").orNull == "true") '
        '"com.music.bitchord" else "com.music.bitchord.songcredits"'
    )
    credits_block, app_id_count = app_id_line.subn(
        lambda match: match.group(1) + app_id_value,
        credits_block,
        count=1,
    )
    if app_id_count == 0:
        flavor_open = credits_block.find("{")
        if flavor_open < 0:
            raise SystemExit("Credits product flavor opening brace not found")
        credits_block = (
            credits_block[:flavor_open + 1]
            + "\\n            "
            + app_id_value
            + credits_block[flavor_open + 1:]
        )
    # Remove an old flavor suffix if the source patch used one; the explicit
    # applicationId above is the complete package ID for each install mode.
    credits_block = re.sub(r"(?m)^[ \t]*applicationIdSuffix\s*=.*\n", "", credits_block)

    app_label_line = re.compile(r'(?m)^([ \t]*)resValue\("string",\s*"app_name",.*\)$')
    app_label_value = (
        'resValue("string", "app_name", if (providers.gradleProperty("bitchord.credits.cleanSwitch").orNull == "true") '
        '"BitChord Credits · Replace" else "BitChord Credits")'
    )
    credits_block, label_count = app_label_line.subn(
        lambda match: match.group(1) + app_label_value,
        credits_block,
        count=1,
    )
    if label_count == 0:
        credits_block = credits_block[:-1] + "    " + app_label_value + "\n}"

    updater_field = r'''        buildConfigField(
            "String",
            "UPDATE_APK_PREFIX",
            if (providers.gradleProperty("bitchord.credits.cleanSwitch").orNull == "true") {
                "\"BitChord-Credits-Replace-\""
            } else {
                "\"BitChord-Credits-Keep-\""
            },
        )
'''
    if '"UPDATE_APK_PREFIX"' not in credits_block:
        credits_block = credits_block[:-1] + updater_field + "    }\n"

    gradle_text = gradle_text[:credits_start] + credits_block + gradle_text[credits_close + 1:]
    gradle.write_text(gradle_text, encoding="utf-8")

    replace_once(
        gradle,
        r'        buildConfigField("String", "UPDATE_APK_PREFIX", "\"BitChord-\"")',
        r'        buildConfigField("String", "UPDATE_APK_PREFIX", "\"BitChord-Credits-Keep-\"")',
        "Credits-only default APK update asset prefix",
    )

    # The embedded patch adds English-only credits labels to the base catalog.
    # Preserve Android's English fallback without breaking locale completeness tests.
    mark_patch_strings_without_translations(root / "app/src/main/res")

    # Use a descriptive user agent for public MusicBrainz lookups.
    text = repository.read_text(encoding="utf-8")
    old_agent = 'private const val MUSICBRAINZ_USER_AGENT = "BitChord (https://github.com/kushagrasinghx/BitChord)"'
    new_agent = 'private const val MUSICBRAINZ_USER_AGENT = "BitChord Credits (https://github.com/heobeep/BitChord)"'
    if new_agent not in text and old_agent in text:
        repository.write_text(text.replace(old_agent, new_agent, 1), encoding="utf-8")

    print("Credits display cleanup and fork updater fixes applied.")


if __name__ == "__main__":
    main()
