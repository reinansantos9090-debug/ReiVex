# ReiVex

ReiVex is an Android browser with an integrated download manager. It is a separate app from ReiAnix: it has its own Android application ID (`com.reivex.browser`), private download database, preferences, and release signing configuration.

## Browser

- Full-screen browser with multiple tabs, tab overview, and URL entry.
- Balanced and advanced ad filtering, with verification and anti-bot pages allowed to load.
- Smart download offers for media detected by the browser, with link, filename, size, and session information available before starting a transfer.
- HTTP/HTTPS links can open directly in ReiVex. Shared links from other apps can be sent to the browser or download queue.

## Downloads

- Persistent queue with pause, resume, retry, and restart.
- Direct files, HLS, and DASH support, plus configurable Wi-Fi use, concurrent transfers, connection parts, and buffer size.
- Per-link cookies and authentication details are stored encrypted with an Android Keystore key.
- Downloads are saved to `Download/ReiVex` by default and can use an Android folder grant when selected.

ReiVex does not include the ReiAnix library, scanner, AniList account, or local anime catalog. Downloaded media is kept in ReiVex's own queue and Android's Downloads storage.

## Build

The standalone Android project is in `android/`. It uses JDK 17, Android SDK Platform 36, and Gradle 8.11.1. Build a debug APK with:

```bash
gradle -p android --no-daemon :app:assembleDebug
```

Release signing is configured separately for ReiVex. The `REIVEX_ANDROID_*` secrets and expected certificate fingerprint belong to this repository and must be set from a ReiVex-owned signing key. ReiAnix signing secrets and key material are not used.

## License

ReiVex uses the Apache License 2.0. See [LICENSE](LICENSE).
