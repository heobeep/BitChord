# BitChord Credits build

This branch builds a separate app package: `com.music.bitchord.songcredits` (label: BitChord Credits). It does not replace the original BitChord package.

The workflow applies the native Song Credits patch, runs `:shared:jvmTest`, compiles `:app:assembleCreditsDebug`, and uploads the installable debug-signed APK as the workflow artifact **BitChord-Credits-debug-apk**.

The feature uses Credits.fm (anonymous mode works; key optional), the existing ListenBrainz token only when enabled, and MusicBrainz as a public fallback. The Credits.fm key is stored via BitChord's encrypted AuthStore.

Workflow: Actions → Build BitChord Credits APK. On completion, open the run and download the artifact.

## Credits.fm navigation and catalogue matching

The Account & Integrations page exposes a direct link to the Credits.fm API page for
an optional free API key, plus an in-app shortcut to Appearance → Song Info & Credits.
A key is not required for ordinary public lookups.

MusicBrainz candidate retrieval is title-based so differently formatted artist credits
do not suppress otherwise relevant recording candidates. BitChord still validates the
recording title and artist credit locally before accepting a match; this deliberately
favours a missing credit over displaying credits for the wrong recording.
