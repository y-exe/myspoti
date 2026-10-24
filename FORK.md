# Myspoti Yexe fork

This fork keeps upstream Myspoti's player and lossless download sources, and adds a YouTube Music fallback.

- Try the existing lossless sources first. If they fail, find a matching YouTube Music song by title, artist and duration.
- Keep the best advertised AAC/M4A or Opus/WebM stream without re-encoding. No promise of Premium bitrates: only streams the extractor actually returns are used.
- Check against the original preview when available. Without a preview, show that the song was matched by metadata rather than verified by audio.
- Show source, codec and advertised bitrate in Downloads. Old offline indexes remain readable.
- Settings → Downloads → YouTube Music fallback turns supplementation off.
- Downloads → Try lossless again for YouTube songs explicitly retries those songs with CD-quality FLAC. It keeps the old audio until the replacement is saved. This is a manual operation, not a background polling job.

Builds install as **Myspoti Yexe dev** (`app.myspoti.yexe.dev`), alongside upstream. They have separate login and storage. Upstream's signing keys and private data are not copied.

## Validation

Run the upstream unit tests and debug build from `base/`:

```
./gradlew testDebugUnitTest :api:test :api-spotify:test :host-cdp:test assembleDebug
```

The device-only YouTube fallback test requires network access and deliberately exercises the extractor, file download and Android decoder metadata. Run it explicitly with the instrumentation class filter rather than as an offline CI test.

The original GPL-3.0 license and upstream credits continue to apply. See README.md and THIRD_PARTY_NOTICES.md.

Matching design references: spotDL's `spotdl/providers/audio/base.py`, `ytmusic.py`, and `spotdl/utils/matching.py` (MIT). This fork independently implements ISRC-first search, song-only candidates, title/artist/duration ranking, and exclusion of unrequested versions in Kotlin. A uniquely returned ISRC candidate with different script is accepted only when its actual audio can be compared with a preview. It does not assume that an ISRC search result is an exact ISRC match.
