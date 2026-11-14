package com.reivex.browser

import android.app.Application
import android.util.Log
import com.reivex.browser.downloads.DownloadScheduling
import com.reivex.browser.downloads.DownloadState
import com.reivex.browser.downloads.DownloadStore
import com.reivex.browser.downloads.DownloadSnapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Starts only ReiVex's browser and persistent download queue. */
class ReiVexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val downloads = DownloadStore(this@ReiVexApplication)
                downloads.ready()
                DownloadSnapshots.refresh(this@ReiVexApplication)
                if (downloads.all().any { it.state in DownloadState.pending }) {
                    DownloadScheduling.schedule(this@ReiVexApplication, userInitiated = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ReiVex", "Download queue recovery was deferred", error)
            }
        }
    }
}
