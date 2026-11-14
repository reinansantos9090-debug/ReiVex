# ReiVex architecture

ReiVex contains two user-facing surfaces: `DownloadBrowserActivity` and `DownloadsActivity`. The browser owns tabs and site navigation; detected media is offered to the user through the download editor. The downloads screen and background worker share a private queue, a private per-link credential profile store, and a WorkManager/Android user-initiated job scheduler.

The queue uses `reivex_downloads.db`; browser and transfer preferences use the `reivex_preferences` SharedPreferences file. Authentication values are encrypted with the ReiVex-specific Android Keystore alias `reivex.downloads`. None of these stores are shared with ReiAnix.

Only browser, download, and minimal media filename helpers are built into the app. The previous library catalog, scanner, AniList login, organizer, and integrated anime player are not included in this repository's Android source set.
