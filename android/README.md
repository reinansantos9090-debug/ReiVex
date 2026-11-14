# ReiVex Android

The `app` module is an Android browser and download manager. `DownloadBrowserActivity` is the launcher and accepts web links; `DownloadsActivity` owns the persistent queue. The queue uses a ReiVex-specific SQLite file and app-private preferences. Its saved credentials are encrypted with a ReiVex-specific Android Keystore key.

The app ID is `com.reivex.browser`. Release signing uses only `REIVEX_ANDROID_*` environment variables and must use a newly created ReiVex key. Do not configure ReiAnix signing values in this repository.

Build locally with JDK 17, Android SDK Platform 36, and Gradle 8.11.1:

```bash
gradle -p android --no-daemon :app:assembleDebug
```

Download unit tests are available through Gradle and the optional JVM workflow; no Android emulator is required for the standard build.
