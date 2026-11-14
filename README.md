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


## CI APK signing

The `Build ReiVex Android APK` workflow always builds and verifies an APK. Its artifact name identifies the signing mode:

- **ReiVex-Preview-APK**: PR builds use a temporary debug key on the release variant. If the `main` branch has no ReiVex release-signing values at all, it builds the **debug** variant instead. These APKs are for testing only: keys may change between runs, so installation over a previous build is not guaranteed.
- **ReiVex-Release-APK**: only on `main`/manual runs when all ReiVex release-signing values exist. It verifies the signing certificate SHA-256 before uploading the artifact.
- Partially configured signing is an error rather than silently producing a debug APK. A wrong certificate fingerprint is also an error.

To enable a stable release APK, configure these secrets in **ReiVex → Settings → Secrets and variables → Actions → Repository secrets**:

1. `REIVEX_ANDROID_KEYSTORE_B64` — base64 of a dedicated ReiVex release keystore file.
2. `REIVEX_ANDROID_KEYSTORE_PASSWORD` — keystore password.
3. `REIVEX_ANDROID_KEY_PASSWORD` — key password.
4. `REIVEX_ANDROID_KEY_ALIAS` — alias in that keystore.

Set the repository **variable** `REIVEX_ANDROID_EXPECTED_CERT_SHA256` to the SHA-256 certificate fingerprint for the same alias. Protect and back up the keystore and passwords; the original signing key is needed for future updates. Do not commit secrets or private keystores. ReiVex must use its own signing configuration, independent of ReiAnix.

## License

ReiVex uses the Apache License 2.0. See [LICENSE](LICENSE).
