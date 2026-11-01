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

    dialog = root / "app/src/main/java/com/music/bitchord/ui/components/SongInfoDialog.kt"
    replace_once(
        dialog,
        r'''                        creditGroups.forEach { (role, credits) ->
                            SongInfoRow(
                                label = role,
                                value = credits.joinToString(" · ") { it.name },
                            )
                        }
''',
        r'''                        creditGroups.forEachIndexed { index, (role, credits) ->
                            if (index > 0) AlertRule(Modifier.padding(start = 16.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    text = role,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.W600,
                                    ),
                                    color = onSurface.copy(alpha = 0.68f),
                                )
                                credits.forEach { credit ->
                                    Text(
                                        text = credit.name,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 15.sp,
                                            lineHeight = 19.sp,
                                        ),
                                        color = onSurface,
                                        textAlign = TextAlign.Start,
                                    )
                                }
                            }
                        }
''',
        "left-aligned song credits layout",
    )

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
    replace_once(
        gradle,
        r'        buildConfigField("String", "UPDATE_APK_PREFIX", "\"BitChord-\"")',
        r'        buildConfigField("String", "UPDATE_APK_PREFIX", "\"BitChord-Credits-\"")',
        "Credits-only APK update asset prefix",
    )

    # Use a descriptive user agent for public MusicBrainz lookups.
    text = repository.read_text(encoding="utf-8")
    old_agent = 'private const val MUSICBRAINZ_USER_AGENT = "BitChord (https://github.com/kushagrasinghx/BitChord)"'
    new_agent = 'private const val MUSICBRAINZ_USER_AGENT = "BitChord Credits (https://github.com/heobeep/BitChord)"'
    if new_agent not in text and old_agent in text:
        repository.write_text(text.replace(old_agent, new_agent, 1), encoding="utf-8")

    print("Credits display cleanup and fork updater fixes applied.")


if __name__ == "__main__":
    main()
