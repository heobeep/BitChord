import base64, gzip, hashlib
from pathlib import Path

PAYLOAD = "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"
if len(PAYLOAD) != 14276 or hashlib.sha256(PAYLOAD.encode("ascii")).hexdigest() != "fa11d5c85f3c8373dd89e1389129a1e01180ab5336fd107fdf572969cd14fec9":
    raise SystemExit("Embedded patch payload failed integrity check")
source = gzip.decompress(base64.b64decode(PAYLOAD)).decode("utf-8")
if hashlib.sha256(source.encode("utf-8")).hexdigest() != "003961af567095067f8a8d373609b62935e77022ae4c49b3da1240922367d408":
    raise SystemExit("Decoded patch source failed integrity check")
source = source.replace(".github/workflows/song-credits-apk.yml", ".github/workflows/song-credits-source-check.yml")
source += r'''
from pathlib import Path as _CreditsPath

# The generic Credits.fm graph includes IPI identifiers as sibling values.
# Never render an identifier-only value as a person's name.
_credits_repo = _CreditsPath(".")
_credits_repository = _credits_repo / "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/SongInfoRepository.kt"
_credits_text = _credits_repository.read_text(encoding="utf-8")
_parser_old = """            if (role == null || name.isNullOrBlank()) return
            val clean = name.trim()
            val key = "${role.lowercase()}|${clean.lowercase()}"
"""
_parser_new = """            if (role == null || name.isNullOrBlank()) return
            val clean = name.trim()
            // IPI identifiers are numeric registry codes, not contributor names.
            // Keep artist names such as "2Pac" and "50 Cent" untouched.
            if (Regex("(?i)^(?:IPI\\\\s*[:#-]?\\\\s*)?\\\\d{9,11}$").matches(clean)) return
            val key = "${role.lowercase()}|${clean.lowercase()}"
"""
if _parser_old in _credits_text:
    _credits_text = _credits_text.replace(_parser_old, _parser_new, 1)
elif "IPI identifiers are numeric registry codes" not in _credits_text:
    raise RuntimeError("Could not locate the Credits.fm display-name parser")
_credits_repository.write_text(_credits_text, encoding="utf-8")

# Credit roles get their own full-width block instead of a right-aligned
# key/value row, so long lists wrap naturally and stay readable.
_credits_ui = _credits_repo / "app/src/main/java/com/music/bitchord/ui/components/SongInfoDialog.kt"
_credits_ui_text = _credits_ui.read_text(encoding="utf-8")
_ui_old = """                        creditGroups.forEach { (role, credits) ->
                            SongInfoRow(
                                label = role,
                                value = credits.joinToString(" · ") { it.name },
                            )
                        }
"""
_ui_new = """                        creditGroups.forEachIndexed { index, (role, credits) ->
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
"""
if _ui_old in _credits_ui_text:
    _credits_ui_text = _credits_ui_text.replace(_ui_old, _ui_new, 1)
elif "creditGroups.forEachIndexed" not in _credits_ui_text:
    raise RuntimeError("Could not locate the Song Info credits layout")
_credits_ui.write_text(_credits_ui_text, encoding="utf-8")

# The Credits fork must check its own releases, not upstream BitChord.
_credits_updater = _credits_repo / "app/src/main/java/com/music/bitchord/data/AppUpdateChecker.kt"
_updater_text = _credits_updater.read_text(encoding="utf-8")
_upstream_url = "https://api.github.com/repos/kushagrasinghx/BitChord/releases/latest"
_fork_url = "https://api.github.com/repos/heobeep/BitChord/releases/latest"
if _upstream_url in _updater_text:
    _updater_text = _updater_text.replace(_upstream_url, _fork_url, 1)
elif _fork_url not in _updater_text:
    raise RuntimeError("Could not locate the app update release URL")
_credits_updater.write_text(_updater_text, encoding="utf-8")

# Release tags are "credits-vX.Y", not just "vX.Y". Extract the numeric
# version suffix so the in-app updater can compare the fork's releases.
_updater_text = _credits_updater.read_text(encoding="utf-8")
_old_version_parse = '            latest = tag.removePrefix("v")'
_new_version_parse = '''            latest = tag.substringAfterLast('v')
                .takeIf { it.firstOrNull()?.isDigit() == true }
                ?: tag.removePrefix("v")'''
if _old_version_parse in _updater_text:
    _updater_text = _updater_text.replace(_old_version_parse, _new_version_parse, 1)
elif "tag.substringAfterLast('v')" not in _updater_text:
    raise RuntimeError("Could not locate the release-tag version parser")
_credits_updater.write_text(_updater_text, encoding="utf-8")

# Limit the updater to this fork's APK assets, and let it distinguish the
# ARM64 split from a universal APK using the expected "_arm64-v8a" suffix.
_credits_gradle = _credits_repo / "app/build.gradle.kts"
_gradle_text = _credits_gradle.read_text(encoding="utf-8")
_old_prefix = r'buildConfigField("String", "UPDATE_APK_PREFIX", "\\\"BitChord-\\\"")'
_new_prefix = r'buildConfigField("String", "UPDATE_APK_PREFIX", "\\\"BitChord-Credits-\\\"")'
if _old_prefix in _gradle_text:
    _gradle_text = _gradle_text.replace(_old_prefix, _new_prefix, 1)
elif "UPDATE_APK_PREFIX" not in _gradle_text or "BitChord-Credits-" not in _gradle_text:
    raise RuntimeError("Could not locate the Credits APK update prefix")
_credits_gradle.write_text(_gradle_text, encoding="utf-8")

# Identify this fork politely when querying the public MusicBrainz service.
_credits_text = _credits_repository.read_text(encoding="utf-8")
_credits_text = _credits_text.replace(
    'private const val MUSICBRAINZ_USER_AGENT = "BitChord (https://github.com/kushagrasinghx/BitChord)"',
    'private const val MUSICBRAINZ_USER_AGENT = "BitChord Credits (https://github.com/heobeep/BitChord)"',
    1,
)
_credits_repository.write_text(_credits_text, encoding="utf-8")
'''
namespace = {"__name__": "__main__", "__file__": str(Path(__file__).resolve())}
exec(compile(source, "apply_song_credits.py", "exec"), namespace)
