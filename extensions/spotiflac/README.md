# SpotiFLAC download extension

A download-source adapter for Myspoti's extension API. It carries the existing verified Tidal/Qobuz gateway protocol out of the app core. It requests an exact ISRC match, preserves original audio and rejects previews and encrypted manifests. It does not provide playback streams. Myspoti still validates the downloaded file and encrypts it locally.

Install `y-exe/myspoti` through Myspoti's standard GitHub extension installer: releases include the SDK's canonical `extension.zip`, so the extension can check the same repository for updates. Authors must increase its manifest versionCode when changing the adapter. Tidal and Qobuz verification buttons use the standard extension sign-in page; each provider needs its own session. Session data stays in private extension storage. A missing recording or an unavailable provider returns no files, letting the app try the enabled YouTube fallback.

From base/:

```
./gradlew :extensions-cli:run --args="check $PWD/../extensions/spotiflac"
./gradlew :extensions-cli:run --args="pack $PWD/../extensions/spotiflac $PWD/app/build/outputs/extensions"
```

The original project's GPL-3.0 license applies.
