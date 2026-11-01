#!/usr/bin/env python3
"""Fix generated BitChord Credits sources after the embedded upstream patch is applied."""
from pathlib import Path
import argparse
import re

PARSER_START = "    fun fromCreditsFm(root: JsonElement): List<SongCredit> {"
PARSER_END = "    fun fromMusicBrainz(root: JsonElement): List<SongCredit> {"

NEW_CREDITS_FM = r'''    /**
     * Credits.fm mixes human-readable names with registry identifiers in some
     * relationship fields. Keep names for display; IDs belong in a separate
     * identifier detail view and must never be rendered as people.
     */
    internal fun cleanCreditNames(raw: String): List<String> {
        val withoutTypedIds = raw.replace(
            Regex("""(?i)\b(?:IPI|ISNI|ISRC|ISWC|MBID|UUID)\s*[:#]?\s*[A-Z0-9-]{6,}\b"""),
            " ",
        )
        val withoutLongNumericIds = withoutTypedIds.replace(
            Regex("""(?<!\d)\d{9,16}(?!\d)"""),
            " ",
        )
        return withoutLongNumericIds
            .split(Regex("""\s*[·•|]\s*"""))
            .map { part ->
                part.replace(Regex("""\s+"""), " ")
                    .trim()
                    .trim(',', ';', ':', '·', '•', '|')
            }
            .filter { candidate ->
                candidate.any(Char::isLetter) &&
                    !Regex("""(?i)^(?:[A-Z]{2}[A-Z0-9]{3}\d{7}|[0-9a-f]{8}-[0-9a-f-]{27,})$""")
                        .matches(candidate)
            }
            .distinctBy { it.lowercase() }
    }

    fun fromCreditsFm(root: JsonElement): List<SongCredit> {
        val result = linkedMapOf<String, SongCredit>()

        fun add(role: String?, rawName: String?) {
            if (role == null || rawName.isNullOrBlank()) return
            cleanCreditNames(rawName).forEach { clean ->
                val key = "${role.lowercase()}|${clean.lowercase()}"
                if (!result.containsKey(key)) result[key] = SongCredit(role, clean)
            }
        }

        val identifierKeys = setOf(
            "id", "ipi", "ipi_number", "ipi_code", "ipis",
            "isni", "isni_code", "isrc", "iswc", "upc",
            "uuid", "mbid", "credits_id", "entity_id",
            "source_ids", "identifiers", "type_id", "type-id",
            // These are provenance/transport metadata, not contributor names.
            // Walking them with an inherited role caused labels like "discogs"
            // and "mb" to appear as if they were people in the credits list.
            "source", "sources", "source_name", "source_type", "source_label",
            "credit_source", "data_source", "provider", "providers",
            "catalog", "catalogue", "url", "uri", "href", "link", "links",
            "external_ids", "external_url", "resource_url", "metadata",
            "created_at", "updated_at", "confidence", "match_type",
            "notes", "comment", "description",
            // Instrument/equipment metadata describes what was played or used,
            // not a person. Do not inherit a Performer role into these branches.
            "instrument", "instruments", "instrumentation", "musical_instruments",
            "performer_instruments", "equipment", "recording_equipment",
            "gear", "devices", "hardware", "software", "tools", "setup",
        )
        val nameKeys = setOf(
            "name", "display_name", "person_name", "full_name",
            "legal_name", "artist_name", "writer_name", "publisher_name",
        )

        fun walk(element: JsonElement, inheritedRole: String? = null) {
            when (element) {
                is JsonObject -> {
                    val localRole = role(element.string("role"))
                        ?: role(element.string("credit_type"))
                        ?: role(element.string("relationship"))
                        ?: role(element.string("type"))
                        ?: inheritedRole

                    if (localRole != null) {
                        val artistName = element.obj("artist").string("name")
                            ?: element.obj("musician").string("name")
                            ?: element.obj("contributor").string("name")
                            ?: element.obj("person").string("name")
                            ?: element.obj("writer").string("name")
                            ?: element.obj("publisher").string("name")
                        val rawName = element.string("name")
                            ?: element.string("display_name")
                            ?: element.string("person_name")
                            ?: element.string("full_name")
                            ?: element.string("legal_name")
                            ?: element.string("artist_name")
                            ?: element.string("writer_name")
                            ?: element.string("publisher_name")
                            ?: artistName
                            ?: element.string("artist")
                        add(localRole, rawName)
                    }

                    element.forEach { (key, value) ->
                        if (
                            key !in identifierKeys &&
                            key !in nameKeys &&
                            key !in setOf("role", "credit_type", "relationship", "type")
                        ) {
                            // A role belongs to the record itself. Do not pass the
                            // parent's role into arbitrary nested metadata: doing so
                            // turns instrument values (e.g. "piano") into people.
                            // Only an explicitly role-named child collection starts
                            // a new contributor traversal.
                            val keyedRole = role(key)
                            walk(value, keyedRole)
                        }
                    }
                }
                is JsonArray -> element.forEach { walk(it, inheritedRole) }
                is JsonPrimitive -> if (inheritedRole != null && element.isString) {
                    add(inheritedRole, element.content)
                }
                else -> Unit
            }
        }

        walk(root)
        return result.values.toList()
    }

'''

def replace_section(source: str, start_token: str, end_token: str, replacement: str) -> str:
    start = source.find(start_token)
    if start < 0:
        raise SystemExit(f"Expected source marker not found: {start_token}")
    end = source.find(end_token, start)
    if end < 0:
        raise SystemExit(f"Expected source end marker not found: {end_token}")
    return source[:start] + replacement + source[end:]

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    args = parser.parse_args()
    root = args.repo.resolve()

    parser_path = root / "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/SongInfoRepository.kt"
    text = parser_path.read_text(encoding="utf-8")
    text = replace_section(text, PARSER_START, PARSER_END, NEW_CREDITS_FM)
    old = '''            val clean = name.trim()
            val key = "${label.lowercase()}|${clean.lowercase()}"
            if (!result.containsKey(key)) result[key] = SongCredit(label, clean)'''
    new = '''            cleanCreditNames(name).forEach { clean ->
                val key = "${label.lowercase()}|${clean.lowercase()}"
                if (!result.containsKey(key)) result[key] = SongCredit(label, clean)
            }'''
    if old not in text:
        raise SystemExit("MusicBrainz credit normalization marker not found")
    text = text.replace(old, new, 1)
    parser_path.write_text(text, encoding="utf-8")

    dialog_path = root / "app/src/main/java/com/music/bitchord/ui/components/SongInfoDialog.kt"
    dialog = dialog_path.read_text(encoding="utf-8")
    old_ui = '''                        creditGroups.forEach { (role, credits) ->
                            SongInfoRow(
                                label = role,
                                value = credits.joinToString(" · ") { it.name },
                            )
                        }'''
    new_ui = '''                        creditGroups.forEachIndexed { index, (role, credits) ->
                            if (index > 0) AlertRule(Modifier.padding(start = 16.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    text = role,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.W600,
                                    ),
                                    color = onSurface.copy(alpha = 0.65f),
                                    textAlign = TextAlign.Start,
                                )
                                Spacer(Modifier.height(5.dp))
                                // Keep the credits compact: one wrapped paragraph per
                                // role, rather than spending a full screen row per person.
                                Text(
                                    text = credits.joinToString(" · ") { it.name },
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 14.sp,
                                        lineHeight = 19.sp,
                                    ),
                                    color = onSurface,
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }'''
    if old_ui in dialog:
        dialog = dialog.replace(old_ui, new_ui, 1)
    elif "creditGroups.forEachIndexed { index, (role, credits) ->" not in dialog and "creditGroups.entries.forEachIndexed { index, (role, credits) ->" not in dialog:
        raise SystemExit("Song Info credits UI marker not found")
    dialog = dialog.replace(
        "creditGroups.forEachIndexed { index, (role, credits) ->",
        "creditGroups.entries.forEachIndexed { index, (role, credits) ->",
    )
    # Native BitChord settings drive both panels independently. Field filtering
    # uses resource/role identities, never keyword matching against display names.
    package_line = "package com.music.bitchord.ui.components\n"
    for import_line in (
        "import androidx.lifecycle.compose.collectAsStateWithLifecycle",
        "import com.music.bitchord.data.settings.AppSettings",
        "import com.music.bitchord.data.settings.SongInfoField",
        "import com.music.bitchord.data.settings.SongCreditField",
    ):
        if import_line not in dialog:
            dialog = dialog.replace(package_line, package_line + import_line + "\n", 1)

    dialog_function = re.search(r"\bfun\s+SongInfoDialog\s*\(", dialog)
    if dialog_function is None:
        raise SystemExit("Song Info dialog composable declaration not found")
    function_body = dialog.find("{", dialog_function.end())
    if function_body < 0:
        raise SystemExit("Song Info dialog composable body not found")
    dialog_preferences = """
    val showYoutubeInfo by AppSettings.showYoutubeSongInfo.collectAsStateWithLifecycle()
    val showSongCredits by AppSettings.showSongCredits.collectAsStateWithLifecycle()
    val selectedSongInfoFields by AppSettings.songInfoFields.collectAsStateWithLifecycle()
    val selectedSongCreditFields by AppSettings.songCreditFields.collectAsStateWithLifecycle()
"""
    if "val showYoutubeInfo by AppSettings.showYoutubeSongInfo" not in dialog:
        dialog = dialog[:function_body + 1] + dialog_preferences + dialog[function_body + 1:]

    load_call = "SongInfoRepository.load(song)"
    if load_call not in dialog:
        raise SystemExit("Song Info YouTube metadata load call not found")
    dialog = dialog.replace(
        load_call,
        "if (showYoutubeInfo && selectedSongInfoFields.isNotEmpty()) "
        + "SongInfoRepository.load(song) else null",
        1,
    )

    old_rows = "                rows.forEachIndexed { index, (label, value) ->"
    new_rows = """                rows.filter { (label, _) ->
                    showYoutubeInfo && when (label) {
                        R.string.song_info_album -> SongInfoField.ALBUM in selectedSongInfoFields
                        R.string.song_info_release_date -> SongInfoField.RELEASE_DATE in selectedSongInfoFields
                        R.string.song_info_views -> SongInfoField.VIEWS in selectedSongInfoFields
                        R.string.song_info_likes -> SongInfoField.LIKES in selectedSongInfoFields
                        R.string.song_info_comments -> SongInfoField.COMMENTS in selectedSongInfoFields
                        else -> false
                    }
                }.forEachIndexed { index, (label, value) ->"""
    if old_rows not in dialog:
        raise SystemExit("Song Info visible-fields row marker not found")
    dialog = dialog.replace(old_rows, new_rows, 1)

    old_groups = "                        creditGroups.entries.forEachIndexed { index, (role, credits) ->"
    new_groups = """                        creditGroups.entries
                            .filter { (role, _) ->
                                showSongCredits && SongCreditField.fromRole(role) in selectedSongCreditFields
                            }
                            .forEachIndexed { index, (role, credits) ->"""
    if old_groups not in dialog:
        raise SystemExit("Song credit role filter marker not found")
    dialog = dialog.replace(old_groups, new_groups, 1)

    dialog_path.write_text(dialog, encoding="utf-8")

    tests_path = root / "shared/src/jvmTest/kotlin/com/music/bitchord/data/SongCreditsNameCleanupTest.kt"
    tests_path.parent.mkdir(parents=True, exist_ok=True)
    tests_path.write_text(r'''package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SongCreditsNameCleanupTest {
    @Test
    fun stripsIpiNumbersAndKeepsThePersonName() {
        assertEquals(
            listOf("CEDRIC BENJAMIN LEUTWYL..."),
            SongCreditsParser.cleanCreditNames("00861590127 · CEDRIC BENJAMIN LEUTWYL..."),
        )
    }

    @Test
    fun splitsPublisherNamesAfterRemovingRegistryIdentifiers() {
        assertEquals(
            listOf("JKSN PUBLISHING", "QUALITY CONTROL QC PRO", "UNIVERSAL MUSIC CORP."),
            SongCreditsParser.cleanCreditNames(
                "00031312147 · 00392888203 · 00538724724 · JKSN PUBLISHING · QUALITY CONTROL QC PRO · UNIVERSAL MUSIC CORP."
            ),
        )
    }

    @Test
    fun keepsArtistNamesThatLegitimatelyContainDigits() {
        assertEquals(
            listOf("21 Savage", "blink-182", "3 Doors Down"),
            listOf("21 Savage", "blink-182", "3 Doors Down").flatMap(SongCreditsParser::cleanCreditNames),
        )
    }

    @Test
    fun rejectsAnIdentifierWithNoHumanName() {
        assertEquals(emptyList(), SongCreditsParser.cleanCreditNames("00861590127"))
        assertEquals(emptyList(), SongCreditsParser.cleanCreditNames("IPI: 00861590127"))
    }

    @Test
    fun doesNotBlacklistWordsThatCouldBeNames() {
        assertEquals(listOf("Discogs"), SongCreditsParser.cleanCreditNames("Discogs"))
        assertEquals(listOf("MB"), SongCreditsParser.cleanCreditNames("MB"))
        assertEquals(listOf("Piano"), SongCreditsParser.cleanCreditNames("Piano"))
    }

    @Test
    fun ignoresProviderAndSourceFieldsStructurally() {
        val input = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"credits":[{"role":"Performer","name":"Michael Jackson","source":"discogs","provider":"mb"}]}""",
        )
        assertEquals(
            listOf(SongCredit("Performer", "Michael Jackson")),
            SongCreditsParser.fromCreditsFm(input),
        )
    }

    @Test
    fun doesNotTreatInstrumentValuesAsPerformerNames() {
        val input = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"credits":[{"role":"Performer","person":{"name":"Michael Jackson"},"instruments":["drum machine","piano"],"instrumentation":{"primary":"synthesizer"},"equipment":["microphone"]}]}""",
        )
        assertEquals(
            listOf(SongCredit("Performer", "Michael Jackson")),
            SongCreditsParser.fromCreditsFm(input),
        )
    }

    @Test
    fun doesNotCarryARecordRoleIntoUnrelatedNestedFields() {
        val input = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"credits":[{"role":"Performer","name":"Michael Jackson","details":{"instrument":"piano","device":"drum machine"}}]}""",
        )
        assertEquals(
            listOf(SongCredit("Performer", "Michael Jackson")),
            SongCreditsParser.fromCreditsFm(input),
        )
    }

    @Test
    fun deduplicatesRepeatedNamesWithinOneField() {
        assertEquals(
            listOf("Alex Petit"),
            SongCreditsParser.cleanCreditNames("00230071229 · Alex Petit · ALEX PETIT"),
        )
    }
}
''', encoding="utf-8")

    settings_test_path = root / "app/src/test/java/com/music/bitchord/data/settings/SongCreditFieldTest.kt"
    settings_test_path.parent.mkdir(parents=True, exist_ok=True)
    settings_test_path.write_text(r'''package com.music.bitchord.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SongCreditFieldTest {
    @Test
    fun groupsProviderRoleLabelsWithoutLookingAtContributorNames() {
        assertEquals(SongCreditField.PERFORMERS, SongCreditField.fromRole("Performing"))
        assertEquals(SongCreditField.SONGWRITERS, SongCreditField.fromRole("Songwriter"))
        assertEquals(SongCreditField.PRODUCERS, SongCreditField.fromRole("Co-producer"))
        assertEquals(SongCreditField.MIXING_ENGINEERS, SongCreditField.fromRole("Mixing engineer"))
        assertEquals(SongCreditField.MASTERING_ENGINEERS, SongCreditField.fromRole("Mastering engineer"))
    }

    @Test
    fun preservesUnknownButValidCreditRolesUnderOther() {
        assertEquals(SongCreditField.OTHER, SongCreditField.fromRole("Music supervisor"))
    }
}
''', encoding="utf-8")

    print("Applied credits parser cleanup, native Song Info preferences, and role-filter regression tests.")

if __name__ == "__main__":
    main()
