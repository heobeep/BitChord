# BitChord Credits build

This branch builds a separate app package: `com.music.bitchord.songcredits` (label: BitChord Credits). It does not replace the original BitChord package.

The workflow applies the native Song Credits patch, runs `:shared:jvmTest`, compiles `:app:assembleCreditsDebug`, and uploads the installable debug-signed APK as the workflow artifact **BitChord-Credits-debug-apk**.

The feature uses Credits.fm (anonymous mode works; key optional), the existing ListenBrainz token only when enabled, and MusicBrainz as a public fallback. The Credits.fm key is stored via BitChord's encrypted AuthStore.

Workflow: Actions → Build BitChord Credits APK. On completion, open the run and download the artifact.
