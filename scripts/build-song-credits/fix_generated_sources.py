#!/usr/bin/env python3
"""Fix generated BitChord Credits sources after the embedded upstream patch is applied."""
from pathlib import Path
import argparse

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
                            val keyedRole = role(key)
                            walk(value, keyedRole ?: localRole)
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
                                credits.forEach { credit ->
                                    Text(
                                        text = credit.name,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 14.sp,
                                            lineHeight = 19.sp,
                                        ),
                                        color = onSurface,
                                        textAlign = TextAlign.Start,
                                    )
                                }
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
    fun deduplicatesRepeatedNamesWithinOneField() {
        assertEquals(
            listOf("Alex Petit"),
            SongCreditsParser.cleanCreditNames("00230071229 · Alex Petit · ALEX PETIT"),
        )
    }
}
''', encoding="utf-8")

    print("Applied credits identifier cleanup, readable contributor layout, and parser regression tests.")

if __name__ == "__main__":
    main()
