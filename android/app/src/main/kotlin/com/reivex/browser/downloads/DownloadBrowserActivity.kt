package com.reivex.browser.downloads

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.reivex.browser.ui.theme.ReiVexComposeTheme
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** The selected URL is edited and enqueued over the web page, never via navigation. */
private data class BrowserDownloadOffer(
    val url: String,
    val headers: Map<String,String>,
    val title: String,
    val filename: String,
    val capture: PlayerMediaCandidate?,
    val hlsHint: Boolean,
    val browserLength: Long? = null,
    val providerEstimatedSize:Long?=null,
)

/** HTTP requests never include URL fragments, but player routes may store
 * quality/chunk metadata there. Match captured headers against the wire URL. */
private fun mediaRequestKey(url:String):String = runCatching {
    Uri.parse(url).buildUpon().fragment(null).build().toString()
}.getOrDefault(url.substringBefore('#'))

/** Full-screen browser. Only explicit user actions enqueue a transfer. No JS bridge or SSL bypass. */
class DownloadBrowserActivity: ComponentActivity() {
    private val tabs=mutableStateListOf<BrowserTab>()
    private var activeTabId by mutableStateOf("")
    private var incomingGeneration by mutableIntStateOf(0)
    private var requested by mutableStateOf("")
    private var sourceHeaders by mutableStateOf<Map<String,String>>(emptyMap())
    private var pendingOffer by mutableStateOf<BrowserDownloadOffer?>(null)
    private var offerError by mutableStateOf("")
    private var web: WebView?=null
    private val mediaRequests=ConcurrentHashMap<String,Pair<Long,Map<String,String>>>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); accept(intent)
        val restored=savedInstanceState?.getStringArrayList("browser.urls").orEmpty()
        if(restored.isNotEmpty())restored.forEach {tabs.add(BrowserTab(it))}
        if(tabs.isEmpty())tabs.add(BrowserTab(requested,sourceHeaders))
        activeTabId=tabs.getOrElse(savedInstanceState?.getInt("browser.selected") ?: 0){tabs.first()}.id
        setContent { ReiVexComposeTheme { BrowserShell() } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent);accept(intent)
        if(requested.isNotBlank()) {val tab=BrowserTab(requested,sourceHeaders);tabs.add(tab);activeTabId=tab.id}
    }
    private fun accept(intent: Intent) {
        // Nuvio's external-player ACTION_VIEW can also land in the browser,
        // which has a separate Android VIEW intent filter. Preserve the same
        // String[] headers here as in DownloadsActivity.
        sourceHeaders=DownloadIncomingHeaders.parse(intent)
        val raw=if(intent.action==Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else intent.dataString.orEmpty()
        val candidates=DownloadSourceSelection.choose(
            texts=listOf(raw,intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()),
            explicitUrls=listOfNotNull(intent.dataString,
                intent.getStringExtra("url"),
                intent.getStringExtra("downloadUrl"),
                intent.getStringExtra("mediaUrl"))
        )
        requested=candidates.firstOrNull()?.url.orEmpty()
        incomingGeneration++
        intent.data=null; intent.removeExtra(Intent.EXTRA_TEXT)
    }
    override fun onSaveInstanceState(outState:Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("browser.urls",ArrayList(tabs.map {if(it.home)"" else it.url}))
        outState.putInt("browser.selected",tabs.indexOfFirst {it.id==activeTabId}.coerceAtLeast(0))
    }
    @Composable private fun BrowserShell() {
        var overview by remember {mutableStateOf(false)}
        fun newTab(url:String="") {
            val tab=BrowserTab(url,if(url==requested)sourceHeaders else emptyMap())
            tabs.add(tab);activeTabId=tab.id;overview=false
        }
        LaunchedEffect(incomingGeneration) {
            overview=false
            if(requested.isNotBlank() && tabs.none {it.initialUrl==requested})newTab(requested)
        }
        if(overview) BrowserTabOverview(tabs.toList(),activeTabId,
            onSelect={activeTabId=it.id;overview=false},
            onClose={tab ->
                val index=tabs.indexOf(tab);tabs.remove(tab)
                if(tabs.isEmpty())newTab()
                else if(activeTabId==tab.id)activeTabId=tabs[(index-1).coerceAtLeast(0).coerceAtMost(tabs.lastIndex)].id
            },onNew={newTab()},onBack={overview=false})
        else tabs.firstOrNull {it.id==activeTabId}?.let {tab ->
            key(tab.id) {Browser(tab,onTabs={overview=true},onNewTab={newTab()})}
        }
    }
    @Composable private fun Browser(tab:BrowserTab,onTabs:()->Unit,onNewTab:()->Unit) {
        val requested=tab.initialUrl
        var menu by remember {mutableStateOf(false)}
        var showSettings by remember {mutableStateOf(false)}
        var protectionReady by remember {mutableStateOf(false)}
        var protectionError by remember {mutableStateOf("")}
        var rememberedMode by remember {mutableStateOf(BrowserAdBlock.mode)}
        var address by remember { mutableStateOf(if(tab.home)"" else tab.url) }
        var homeSearch by remember(tab.id) { mutableStateOf("") }
        val startPageStore = remember { BrowserStartPageStore(applicationContext) }
        var recents by remember { mutableStateOf(startPageStore.recents()) }
        var favorites by remember { mutableStateOf(startPageStore.favorites()) }
        var backAvailable by remember {mutableStateOf(false)}
        var forwardAvailable by remember {mutableStateOf(false)}
        var restoreScroll by remember {mutableStateOf(!tab.saved.isEmpty)}
        var title by remember { mutableStateOf("") }
        var progress by remember { mutableIntStateOf(0) }
        var showMedia by remember { mutableStateOf(false) }
        var submitting by remember { mutableStateOf(false) }
        var preparingSiteDownload by remember {mutableStateOf(false)}
        val downloadStore=remember {DownloadStore(applicationContext)}
        val scope=rememberCoroutineScope()
        val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
        val notificationPermission=rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        var candidates by remember { mutableStateOf<List<BrowserDownloadChoice>>(emptyList()) }
        var error by remember { mutableStateOf("") }
        val observed=remember {ConcurrentHashMap<String,PlayerMediaCandidate>()}
        val sourceOptions=remember {ConcurrentHashMap<String,BrowserMediaOption>()}
        val inspectedHls=remember {mutableSetOf<String>()}
        val hlsJobs=remember {mutableListOf<Job>()}
        val popupViews=remember {mutableListOf<WebView>()}
        // Android may terminate an unhealthy Chromium renderer. Recreate only
        // the affected live tab rather than crashing the entire browser.
        var rendererGeneration by remember(tab.id) {mutableIntStateOf(0)}
        val lostRenderers=remember(tab.id) {mutableSetOf<WebView>()}
        val fullscreen=remember {BrowserFullscreen(this@DownloadBrowserActivity)}
        var discoveryRequested by remember {mutableStateOf(false)}
        var discoveryGeneration by remember {mutableIntStateOf(0)}
        var discoveringHls by remember {mutableIntStateOf(0)}
        fun rememberMediaRequest(request:WebResourceRequest) {
            if(request.method!="GET" || request.url.scheme !in setOf("http","https"))return
            val forbidden=setOf("host","content-length","transfer-encoding","accept-encoding",
                "range","if-range","connection","keep-alive","upgrade","proxy-authorization",
                "proxy-connection","te","trailer","expect")
            val headers=LinkedHashMap<String,String>()
            var size=0
            val priority=mapOf("referer" to 0,"origin" to 1,"user-agent" to 2,
                "cookie" to 3,"authorization" to 4,"accept" to 5)
            for((key,value) in request.requestHeaders.entries.sortedBy {
                priority[it.key.lowercase(java.util.Locale.ROOT)] ?: 10
            }.map {it.key to it.value}) {
                val lower=key.lowercase(java.util.Locale.ROOT)
                if(key.length !in 1..64 || !key.all {it.isLetterOrDigit()||it=='-'} ||
                    lower in forbidden || value.length>4096 || value.any {it=='\r'||it=='\n'})continue
                val addition=key.length+value.length
                if(headers.size>=48 || size+addition>16384)continue
                headers[key]=value;size+=addition
            }
            if(mediaRequests.size>=128)
                mediaRequests.entries.minByOrNull {it.value.first}?.let {mediaRequests.remove(it.key,it.value)}
            mediaRequests[mediaRequestKey(request.url.toString())]=
                android.os.SystemClock.elapsedRealtime() to headers
        }
        fun updateCandidates() {
            val now=System.nanoTime()/1_000_000
            val confirmed=observed.values.filter {
                now-it.receivedAt in 0..20000 && PlayerMediaCandidate.eligible(it)
            }
            // Keep the existing largest-visible-player filter to avoid ad players.
            val largest=confirmed.maxOfOrNull {it.area} ?: 0.0
            val main=confirmed.filter {it.area>=largest*0.75}
            val visible=sourceOptions.values.filter {
                now-it.receivedAt in 0..60000 && BrowserMediaOption.eligible(it)
            }
            // CDN links sometimes have no declared 360p/720p/1080p label.
            // Keep legitimate discovered files selectable as "Qualidade não informada"
            // instead of silently showing an empty download sheet.
            candidates=BrowserMediaResults.combine(main,visible)
        }
        fun discoverHls() {
            if(!discoveryRequested)return
            val generation=discoveryGeneration
            sourceOptions.values.filter {it.masterUrl.isBlank() &&
                BrowserHlsDiscovery.isProbeCandidate(it)}.sortedBy {it.url}.forEach { source ->
                val key=source.frame+"::"+source.url
                if(inspectedHls.size>=8 || !inspectedHls.add(key))return@forEach
                discoveringHls++
                hlsJobs+=scope.launch {
                    try {
                        val provided=mediaRequests[mediaRequestKey(source.url)]?.second.orEmpty().toMutableMap()
                        if(provided.keys.none {it.equals("Referer",true)})provided["Referer"]=source.page
                        web?.settings?.userAgentString?.let {provided["User-Agent"]=it}
                        val headers=DownloadSessionHeaders.resolve(applicationContext,source.url,provided)
                        val variants=withTimeoutOrNull(8_000) {
                            BrowserHlsDiscovery.inspect(source,headers)
                        }.orEmpty()
                        if(generation==discoveryGeneration && sourceOptions.containsKey(key)) {
                            variants.forEach { variant ->
                                val variantKey=variant.frame+"::"+variant.url
                                if(sourceOptions.size<96 || sourceOptions.containsKey(variantKey)) {
                                    val previous=sourceOptions[variantKey]
                                    sourceOptions[variantKey]=variant.copy(
                                        estimatedBytes=previous?.estimatedBytes)
                                }
                            }
                            updateCandidates()
                        }
                    } catch(c:CancellationException) {throw c}
                    catch(_:Exception) { /* Keep other resolved sources; allow a new toolbar retry. */ }
                    finally {if(generation==discoveryGeneration)discoveringHls--}
                }
            }
        }
        val browser=remember(rendererGeneration) {
            WebView(this).apply {
                web=this
                try { BrowserAdBlock.install(applicationContext,this,scope) }
                catch(e:Exception) { android.util.Log.w("ReiVexBrowser","Filtro visual indisponível",e) }
                catch(e:LinkageError) { android.util.Log.w("ReiVexBrowser","Filtro visual não suportado",e) }
                BrowserMediaCapture.install(
                    this,
                    onMedia = { frame,item ->
                        if(item==null)observed.remove(frame)
                        else if(observed.size<20 || observed.containsKey(frame))observed[frame]=item
                    },
                    onDownload = { source,downloadTitle ->
                        if(pendingOffer==null && !submitting && !preparingSiteDownload) {
                            preparingSiteDownload=true
                            val generation=discoveryGeneration
                            scope.launch {
                            fullscreen.hide()
                            try {
                                val provided=mediaRequests[mediaRequestKey(source.url)]?.second.orEmpty().toMutableMap()
                                if(provided.keys.none {it.equals("Referer",true)})provided["Referer"]=source.page
                                provided["User-Agent"]=settings.userAgentString
                                val headers=DownloadSessionHeaders.resolve(applicationContext,source.url,provided)
                                // Prefer variants read by the page's authenticated fetch/XHR.
                                // Inspect over HTTP only when the page has not exposed them yet.
                                val captured=sourceOptions.values.firstOrNull {
                                    it.frame==source.frame && it.masterUrl==source.url && it.quality==source.quality
                                }
                                val selected=captured ?: withTimeoutOrNull(8000) {
                                    BrowserHlsDiscovery.inspect(source,headers)
                                }.orEmpty().firstOrNull {it.quality==source.quality}
                                if(generation!=discoveryGeneration)return@launch
                                if(selected!=null)offer(selected.url,settings.userAgentString,null,
                                    "application/vnd.apple.mpegurl",downloadTitle.ifBlank {title},source.page)
                                else {
                                    error="Não foi possível consultar essa qualidade. Tente novamente pelo botão de download."
                                    discoveryRequested=true;discoverHls();updateCandidates();showMedia=true
                                }
                            } catch(c:CancellationException){throw c}
                            catch(_:Exception){if(generation==discoveryGeneration)error="Não foi possível preparar o download. Tente novamente."}
                            finally {preparingSiteDownload=false}
                            }
                        }
                    },
                    onOption = { item ->
                        val key=item.frame+"::"+item.url
                        val previous=sourceOptions[key]
                        // A later scan without metadata must not erase a known resolution.
                        if(sourceOptions.size<96 || previous!=null)sourceOptions[key]=item.copy(
                            quality=if(item.quality=="Qualidade não informada" && previous!=null)
                                previous.quality else item.quality,
                            masterUrl=item.masterUrl.ifBlank {previous?.masterUrl.orEmpty()},
                            estimatedBytes=item.estimatedBytes ?: previous?.estimatedBytes,
                        )
                        discoverHls()
                        if(showMedia)updateCandidates()
                    },
                )
                // Service Worker fetches do not pass through WebViewClient on
                // every Android WebView. Observe their original request headers
                // too, while returning null so WebView performs the request.
                if(WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
                    WebViewFeature.isFeatureSupported(
                        WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
                    ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                        object:ServiceWorkerClientCompat() {
                            override fun shouldInterceptRequest(
                                request:WebResourceRequest,
                            ):WebResourceResponse? {
                                rememberMediaRequest(request)
                                return null
                            }
                        },
                    )
                }
                settings.javaScriptEnabled=true; settings.domStorageEnabled=true
                settings.allowFileAccess=false; settings.allowContentAccess=false
                // Respect the mobile viewport without zooming the whole page out when
                // a player/popover temporarily reports wider overflow content.
                settings.useWideViewPort=true; settings.loadWithOverviewMode=false
                settings.textZoom=100
                settings.setSupportZoom(true);settings.builtInZoomControls=true;settings.displayZoomControls=false
                // Some sites serve a broken embedded-WebView experience when they
                // see Android's `; wv` / `Version/4.0` markers. Keep WebView's
                // current Chromium version, but present it as the ReiVex browser.
                settings.userAgentString=settings.userAgentString
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")
                settings.setSupportMultipleWindows(true)
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
                webViewClient=object: WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest): WebResourceResponse? {
                        if(!request.isForMainFrame && BrowserAdBlock.blocked(request,tab.url))
                            return WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(byteArrayOf()))
                        rememberMediaRequest(request)
                        return null
                    }
                    override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                        if(request.url.scheme !in setOf("http","https")) { return true }
                        if(BrowserAdBlock.blocked(request,tab.url))return true
                        return false
                    }
                    override fun onPageStarted(view: WebView,url: String,favicon: Bitmap?) {
                        discoveryGeneration++;discoveryRequested=false
                        hlsJobs.forEach {it.cancel()};hlsJobs.clear();inspectedHls.clear();discoveringHls=0
                        progress=1
                        if(!tab.home)address=url;tab.url=url;observed.clear();sourceOptions.clear();mediaRequests.clear();error=""
                    }
                    override fun onPageFinished(view: WebView,url: String) {
                        if(!tab.home)address=url
                        progress=100
                        tab.url=url;title=view.title.orEmpty();tab.title=title.ifBlank {BrowserAdBlock.host(url)}
                        if (!tab.home && error.isBlank()) {
                            startPageStore.recordVisit(url, tab.title)
                            recents = startPageStore.recents()
                        }
                        backAvailable=view.canGoBack();forwardAvailable=view.canGoForward()
                        if(restoreScroll) {restoreScroll=false;view.post {view.scrollTo(tab.scrollX,tab.scrollY)}}
                    }
                    override fun onReceivedError(view:WebView,request:WebResourceRequest,failure:WebResourceError) {
                        if(request.isForMainFrame) {progress=100;error="Não foi possível carregar a página: "+failure.description}
                    }
                    override fun onRenderProcessGone(
                        view:WebView,detail:android.webkit.RenderProcessGoneDetail,
                    ):Boolean {
                        lostRenderers.add(view)
                        tab.saved=Bundle() // State from a dead renderer is unsafe to restore.
                        tab.preview=null
                        mediaRequests.clear()
                        error="A página foi interrompida. Recuperando a guia."
                        progress=0
                        rendererGeneration++
                        return true
                    }
                }
                webChromeClient=object: WebChromeClient() {
                    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                        if (!tab.home && icon != null) {
                            val pageUrl = view.url.orEmpty()
                            scope.launch(Dispatchers.IO) {
                                startPageStore.saveFavicon(pageUrl, icon)
                            }
                        }
                    }
                    override fun onShowCustomView(view:android.view.View,callback:CustomViewCallback) {fullscreen.show(view,callback)}
                    override fun onHideCustomView() {fullscreen.hide()}
                    override fun onProgressChanged(view: WebView,n: Int) { progress=n }
                    // Open a requested new window within this browser instead of silently dropping download links.
                    override fun onCreateWindow(view: WebView,isDialog: Boolean,isUserGesture: Boolean,resultMsg: android.os.Message): Boolean {
                        if(!isUserGesture) return false
                        val popup=WebView(this@DownloadBrowserActivity)
                        popupViews.add(popup)
                        fun closePopup() {
                            if(popupViews.remove(popup)) {
                                runCatching {popup.stopLoading()}
                                runCatching {popup.destroy()}
                            }
                        }
                        view.postDelayed({closePopup()},5000)
                        popup.webChromeClient=object:WebChromeClient() {
                            override fun onCloseWindow(window:WebView) {closePopup()}
                        }
                        popup.settings.javaScriptEnabled=true
                        popup.settings.domStorageEnabled=true
                        popup.settings.allowFileAccess=false;popup.settings.allowContentAccess=false
                        popup.webViewClient=object: WebViewClient() {
                            override fun onRenderProcessGone(
                                v:WebView,detail:android.webkit.RenderProcessGoneDetail,
                            ):Boolean {
                                closePopup()
                                return true
                            }
                            override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse? =
                                if(BrowserAdBlock.blocked(r,tab.url))
                                    WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(byteArrayOf())) else null
                            override fun shouldOverrideUrlLoading(v: WebView,r: WebResourceRequest): Boolean {
                                if(r.url.toString()=="about:blank")return false
                                val target=r.url.toString()
                                if(r.url.scheme in setOf("http","https") && !BrowserAdBlock.blocked(target,
                                    page=tab.url,type=io.github.edsuns.adblockclient.ResourceType.POPUP)) {
                                    // Unknown cross-site popups are usually ad redirects. Only
                                    // let a popup navigate the tab when filtering is disabled
                                    // or the destination belongs to the current site.
                                    if(BrowserAdBlock.mode=="off" ||
                                        BrowserAdBlock.related(view.url.orEmpty(),target) ||
                                        BrowserVerificationPolicy.isVerificationResource(target,tab.url)) {
                                        view.loadUrl(target,mapOf("Referer" to view.url.orEmpty()))
                                    }
                                }
                                closePopup(); return true
                            }
                        }
                        popup.setDownloadListener { url,ua,cd,mime,length ->
                            offer(url,ua,cd,mime,title,view.url.orEmpty(),browserLength=length.takeIf{it>0});
                            closePopup()
                        }
                        (resultMsg.obj as WebView.WebViewTransport).webView=popup; resultMsg.sendToTarget(); return true
                    }
                }
                setDownloadListener { url,ua,cd,mime,length ->
                    offer(url,ua,cd,mime,title,this.url.orEmpty(),browserLength=length.takeIf{it>0})
                }
            }
        }
        fun submitHomeSearch() {
            val query=homeSearch.trim()
            if(query.isBlank())return
            val destination=BrowserAddress.resolve(query)
            if(destination==null)error="Endereço inválido"
            else {
                error=""
                address=query
                homeSearch=""
                tab.home=false
                browser.onResume()
                browser.loadUrl(destination)
            }
        }
        LaunchedEffect(tab.id,rendererGeneration) {
            // Preferences and filter preparation are independent: neither failure may
            // prevent a tab from loading. The WebView always stays usable.
            try {
                val preferences=ReiVexPreferences(applicationContext)
                BrowserAdBlock.mode=withContext(Dispatchers.IO) {
                    preferences.getPreference("browser.ad_mode","balanced")
                }
            } catch(c:CancellationException) {throw c}
            catch(e:Exception) {android.util.Log.w("ReiVexBrowser","Não foi possível ler o modo de proteção",e)}
            rememberedMode=BrowserAdBlock.mode
            try {
                if(BrowserAdBlock.mode!="off")BrowserAdBlock.prepare(applicationContext)
                protectionError=""
            } catch(c:CancellationException) {throw c}
            catch(e:Exception) {
                protectionError="Bloqueador indisponível. A navegação continua."
                android.util.Log.e("ReiVexBrowser","Falha na inicialização dos filtros",e)
            } catch(e:LinkageError) {
                protectionError="Bloqueador indisponível. A navegação continua."
                android.util.Log.e("ReiVexBrowser","Biblioteca nativa do bloqueador indisponível",e)
            } finally {
                protectionReady=true
                if(tab.saved.isEmpty || runCatching {browser.restoreState(tab.saved)}.getOrNull()==null) {
                    if(tab.url.isNotBlank()) {
                        val destination=BrowserAddress.resolve(tab.url)
                        if(destination!=null) {tab.home=false;browser.loadUrl(destination)}
                        else error="Endereço inválido"
                    }
                }
            }
        }
        LaunchedEffect(showSettings) {
            if(!showSettings && rememberedMode!=BrowserAdBlock.mode) {
                rememberedMode=BrowserAdBlock.mode
                try {
                    if(BrowserAdBlock.mode!="off")BrowserAdBlock.prepare(applicationContext)
                    protectionError=""
                } catch(c:CancellationException) {throw c}
                catch(e:Exception) {
                    protectionError="Bloqueador indisponível. A navegação continua."
                    android.util.Log.e("ReiVexBrowser","Falha ao alternar modo de filtros",e)
                } catch(e:LinkageError) {
                    protectionError="Bloqueador indisponível. A navegação continua."
                    android.util.Log.e("ReiVexBrowser","Biblioteca nativa do bloqueador indisponível",e)
                } finally {
                    protectionReady=true
                    if(!tab.home && browser.url!=null)browser.reload()
                }
            }
        }
        DisposableEffect(browser) {onDispose {
            fullscreen.hide()
            val crashed=lostRenderers.remove(browser)
            if(!crashed) {
                tab.saved=Bundle()
                runCatching {browser.saveState(tab.saved)}
                tab.scrollX=browser.scrollX;tab.scrollY=browser.scrollY
            }
            popupViews.toList().forEach {
                runCatching {it.stopLoading()}
                runCatching {it.destroy()}
            };popupViews.clear()
            if(!crashed) {
                runCatching {browser.onPause()}
                runCatching {browser.stopLoading()}
            }
            (browser.parent as? android.view.ViewGroup)?.removeView(browser)
            runCatching {browser.destroy()}
            if(web===browser)web=null
            mediaRequests.clear();pendingOffer=null
        }}
        fun captureTab() {
            if(browser.width>0 && browser.height>0 && !tab.home)runCatching {
                val bitmap=Bitmap.createBitmap(240,420,Bitmap.Config.RGB_565)
                val canvas=android.graphics.Canvas(bitmap)
                canvas.scale(240f/browser.width,420f/browser.height)
                browser.draw(canvas);tab.preview=bitmap
            }
        }
        BackHandler {
            when {
                fullscreen.active -> fullscreen.hide()
                showSettings -> showSettings=false
                tab.home && browser.url!=null -> {tab.home=false;browser.onResume();address=tab.url}
                pendingOffer!=null -> { if(!submitting)pendingOffer=null }
                showMedia -> showMedia=false
                browser.canGoBack() -> browser.goBack()
                else -> finish()
            }
        }
        if(showSettings) {
            DownloadSettingsScreen {showSettings=false}
            return
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp).height(58.dp),
                    shape = CircleShape,
                    color = Color(0xFF111214),
                    border = BorderStroke(1.dp, Color(0xFF55585E)),
                ) {
                    Row(
                        Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(Color(0xFF101113), Color(0xFF151619), Color(0xFF101113))
                            )
                        ).padding(start = 13.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Language, "Endereço da web",
                            modifier = Modifier.size(24.dp), tint = Color.White)
                        Spacer(Modifier.width(13.dp))
                        BasicTextField(
                            value = address,
                            onValueChange = { address = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White, fontSize = 15.sp
                            ),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val destination = BrowserAddress.resolve(address)
                                if (destination == null) error = "Endereço inválido"
                                else {
                                    error = ""
                                    tab.home = false
                                    browser.onResume()
                                    browser.loadUrl(destination)
                                }
                            }),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (address.isBlank()) Text(
                                        "Endereço ou pesquisa na web",
                                        color = Color(0xFFCED0D4),
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                    )
                                    inner()
                                }
                            },
                        )
                        Box(Modifier.width(1.dp).height(29.dp).background(Color(0xFF55585E)))
                        IconButton(enabled=protectionReady && !preparingSiteDownload,onClick={
                            if (tab.home) {
                                startActivity(Intent(this@DownloadBrowserActivity,DownloadsActivity::class.java))
                                return@IconButton
                            }
                        discoveryGeneration++
                        hlsJobs.forEach {it.cancel()};hlsJobs.clear();inspectedHls.clear();discoveringHls=0
                        discoveryRequested=true
                        val generation=discoveryGeneration
                        discoverHls()
                        browser.evaluateJavascript("""
                            (() => {
                              window.__reiScanPlayers && window.__reiScanPlayers(true);
                              const headings=Array.from(document.querySelectorAll('h1,h2,.entry-title,.post-title')).slice(0,30);
                              const episode=/(?:epis[oó]dio|episode|ep)[\s._-]*\d+|(?:dublado|legendado)\s+\d+\s*$/i;
                              const heading=headings.find(h=>h.getClientRects().length && episode.test(h.textContent||''));
                              return heading ? heading.textContent.trim().slice(0,240) : document.title;
                            })()
                        """.trimIndent()) { raw ->
                            val detected=runCatching {JSONArray("[$raw]").optString(0)}.getOrDefault("")
                            if(detected.isNotBlank())title=detected
                            browser.postDelayed({
                                if(generation==discoveryGeneration) {
                                    discoverHls();updateCandidates();showMedia=true
                                }
                            },700)
                            // A same-origin HLS master can finish loading after
                            // the first results. Update the open list without a
                            // second user tap or starting video playback.
                            browser.postDelayed({ if(showMedia && generation==discoveryGeneration) {
                                discoverHls();updateCandidates()
                            } },2600)
                        }
                    }) { if(preparingSiteDownload)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        else Icon(Icons.Filled.Download,"Downloads e vídeos",tint=Color.White) }
                        Box {
                            IconButton(onClick={menu=true}) {
                                Icon(Icons.Filled.MoreVert,"Menu do navegador",tint=Color.White)
                            }
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                DropdownMenuItem(text={Text("Nova guia")},onClick={menu=false;captureTab();onNewTab()})
                                if (!tab.home && tab.url.startsWith("http")) {
                                    val saved = favorites.any { it.url == tab.url }
                                    DropdownMenuItem(
                                        text={Text(if(saved) "Remover dos favoritos" else "Adicionar aos favoritos")},
                                        onClick={
                                            menu=false
                                            startPageStore.toggleFavorite(
                                                BrowserStartLink(tab.url, tab.title)
                                            )
                                            favorites=startPageStore.favorites()
                                        },
                                    )
                                }
                                DropdownMenuItem(text={Text("Downloads")},onClick={menu=false;startActivity(Intent(this@DownloadBrowserActivity,DownloadsActivity::class.java))})
                                DropdownMenuItem(text={Text("Configurações")},onClick={menu=false;showSettings=true})
                                if (!tab.home) DropdownMenuItem(text={Text("Recarregar")},onClick={menu=false;error="";browser.reload()})
                            }
                        }
                    }
                }
                if(progress in 1..99 && !tab.home) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
                if(error.isNotBlank()) Text(error,Modifier.padding(8.dp),color=MaterialTheme.colorScheme.error)
                // Startup diagnostics are shown in Settings, never over the visited page.
                if(!protectionReady) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    CircularProgressIndicator()
                } else if(tab.home) BrowserStartPage(
                    search = homeSearch,
                    onSearchChange = { homeSearch = it },
                    onSearch = { submitHomeSearch() },
                    recents = recents,
                    favorites = favorites,
                    onOpen = { url ->
                        val destination = BrowserAddress.resolve(url)
                        if (destination != null) {
                            error = ""
                            homeSearch = ""
                            address = destination
                            tab.home = false
                            browser.onResume()
                            browser.loadUrl(destination)
                        }
                    },
                    onToggleFavorite = { link ->
                        startPageStore.toggleFavorite(link)
                        favorites = startPageStore.favorites()
                    },
                    onRemoveRecent = { url ->
                        startPageStore.removeRecent(url)
                        recents = startPageStore.recents()
                    },
                    onClearRecents = {
                        startPageStore.clearRecents()
                        recents = emptyList()
                    },
                    modifier = Modifier.weight(1f),
                ) else key(rendererGeneration) {
                    AndroidView(factory={browser},modifier=Modifier.weight(1f).fillMaxWidth())
                }
                if(!keyboardVisible) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceEvenly,
                        verticalAlignment=Alignment.CenterVertically,
                    ) {
                        IconButton(
                            enabled=backAvailable || (tab.home && tab.url.isNotBlank()),
                            onClick={
                                if(tab.home){tab.home=false;address=tab.url;browser.onResume()}
                                else browser.goBack()
                            },
                            modifier=Modifier.size(48.dp),
                        ){
                            Icon(
                                Icons.Filled.ArrowBack,
                                "Voltar página",
                                modifier=Modifier.size(24.dp),
                                tint=Color.White,
                            )
                        }
                        IconButton(
                            enabled=forwardAvailable && !tab.home,
                            onClick={browser.goForward()},
                            modifier=Modifier.size(48.dp),
                        ){
                            Icon(
                                Icons.Filled.ArrowForward,
                                "Avançar página",
                                modifier=Modifier.size(24.dp),
                                tint=Color.White,
                            )
                        }
                        IconButton(
                            onClick={captureTab();tab.home=true;address="";browser.onPause()},
                            modifier=Modifier.size(48.dp),
                        ){
                            Icon(
                                Icons.Outlined.Home,
                                "Início do navegador",
                                modifier=Modifier.size(24.dp),
                                tint=Color.White,
                            )
                        }
                        Surface(
                            onClick={captureTab();onTabs()},
                            modifier=Modifier.size(48.dp),
                            shape=CircleShape,
                            color=MaterialTheme.colorScheme.background,
                            contentColor=Color.White,
                        ){
                            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                                Surface(
                                    modifier=Modifier.size(24.dp),
                                    shape=RoundedCornerShape(6.dp),
                                    color=MaterialTheme.colorScheme.background,
                                    contentColor=Color.White,
                                    border=BorderStroke(1.5.dp,Color.White),
                                ){
                                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                                        Text("${tabs.size}",style=MaterialTheme.typography.labelSmall.copy(fontSize=9.sp))
                                    }
                                }
                            }
                        }
                        IconButton(
                            onClick={startActivity(Intent(this@DownloadBrowserActivity,DownloadsActivity::class.java))},
                            modifier=Modifier.size(48.dp),
                        ){
                            Icon(
                                Icons.Filled.Download,
                                "Downloads",
                                modifier=Modifier.size(24.dp),
                                tint=Color.White,
                            )
                        }
                    }
                }
            }
        }
        val offer=pendingOffer
        if(offer!=null) key(offer.url,offer.filename) {
            DownloadDialog(
                initialUrl=offer.url,
                initialHeaders=offer.headers,
                initialTitle=offer.title,
                initialName=offer.filename,
                editing=null,
                busy=submitting,
                submissionError=offerError,
                onDismiss={ if(!submitting){pendingOffer=null;offerError=""} },
                onSubmit={url,name,destination,_,_,headers,autoName,detected ->
                    if(!submitting) {
                        submitting=true
                        offerError=""
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    downloadStore.enqueue(
                                        url=url,name=name,destination=destination,
                                        expectedHash="",hashType="SHA-256",headers=headers,
                                        automaticName=autoName,
                                        playerCapture=offer.capture?.evidence()?.takeIf {
                                            it.optString("url")==url
                                        },
                                        hlsHint=offer.hlsHint && url==offer.url,
                                        detectedSize=detected?.bytes,detectedExact=detected?.exact==true,
                                    )
                                    DownloadSnapshots.refresh(applicationContext)
                                }
                                DownloadScheduling.schedule(applicationContext)
                                if(Build.VERSION.SDK_INT>=33)
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                pendingOffer=null
                                Toast.makeText(this@DownloadBrowserActivity,
                                    "Download adicionado à fila",Toast.LENGTH_SHORT).show()
                            } catch(e: Exception) {
                                offerError=e.message ?: "Não foi possível iniciar este download"
                            } finally { submitting=false }
                        }
                    }
                },
                hlsHint=offer.hlsHint,
                initialKnownSize=offer.browserLength,
                initialEstimatedSize=offer.providerEstimatedSize,
            )
        }
        if(showMedia) AlertDialog(
            onDismissRequest={showMedia=false},
            title={Text("Fontes e qualidades disponíveis")},
            text={
                if(candidates.isEmpty()) {
                    if(discoveringHls>0) Row(verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Buscando qualidades disponíveis…")
                    } else Text("Não foi possível identificar as resoluções. Toque novamente no botão de download para tentar outra consulta.")
                } else LazyColumn {
                    items(candidates,key={it.url}) { item ->
                        val supported=true // DASH VOD now uses Media3 MP4 export when supported
                        val formatLabel=when(item.format) {
                            "hls" -> "HLS"
                            "dash" -> "DASH"
                            else -> "Vídeo"
                        }
                        TextButton(
                            enabled=supported,
                            onClick={
                                showMedia=false
                                if(item.capture==null &&
                                    DownloadSourceSelection.clearlyPage(item.url)) {
                                    // Follow the page inside the authorized WebView,
                                    // then rediscover the actual media used by the player.
                                    browser.loadUrl(item.url)
                                    Toast.makeText(this@DownloadBrowserActivity,
                                        "Página intermediária: abra o player para encontrar o arquivo",
                                        Toast.LENGTH_LONG).show()
                                } else {
                                    offer(item.url,browser.settings.userAgentString,null,
                                        when(item.format) {
                                            "hls" -> "application/vnd.apple.mpegurl"
                                            "dash" -> "application/dash+xml"
                                            else -> null
                                        },
                                        title,item.page,item.capture,
                                        providerEstimatedSize=item.estimatedBytes ?: DownloadSizeIntelligence
                                            .fromSharedMetadata(item.quality)?.bytes)
                                }
                            },
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                val mediaKind=DownloadSourceSelection.kind(item.url)
                                val sourceLabel=when {
                                    item.capture!=null -> "Player confirmado"
                                    mediaKind==DownloadSourceSelection.Kind.DIRECT_FILE -> "Arquivo direto"
                                    mediaKind==DownloadSourceSelection.Kind.PAGE_OR_GATEWAY ->
                                        "Página intermediária"
                                    else -> "Fonte encontrada"
                                }
                                Text("${item.quality} • $formatLabel • $sourceLabel",
                                    maxLines=2)
                                if(!supported) Text(
                                    if(item.separateAudio) "Faixas HLS separadas: MP4 quando o dispositivo suportar"
                                    else "DASH ainda não é compatível com o downloader",
                                    style=MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton={TextButton(onClick={showMedia=false}){Text("Fechar")}},
        )
    }
    private fun offer(url: String,agent: String?,disposition: String?,mime: String?,title: String,page: String,
        capture: PlayerMediaCandidate? = null,browserLength:Long?=null,
        providerEstimatedSize:Long?=null) {
        if(!url.startsWith("https://") && !url.startsWith("http://")) return
        CookieManager.getInstance().flush()
        val headers=Bundle().apply {
            CookieManager.getInstance().getCookie(url)?.let {putString("Cookie",it)}
            if(!agent.isNullOrBlank())putString("User-Agent",agent)
            if(page.isNotBlank())putString("Referer",page)
            // Only use headers actually observed for this exact URL, never those of another CDN.
            mediaRequests[mediaRequestKey(url)]?.takeIf {android.os.SystemClock.elapsedRealtime()-it.first<120000}?.second?.forEach { (key,value) ->
                val canonical=when(key.lowercase()) {"origin"->"Origin";"referer"->"Referer";"user-agent"->"User-Agent";"authorization"->"Authorization";"cookie"->"Cookie";else->key}
                putString(canonical,value)
            }
            val sameSource=runCatching {
                DownloadHttp.sameOrigin(java.net.URL(tabs.firstOrNull {it.id==activeTabId}?.initialUrl.orEmpty()),java.net.URL(url))
            }.getOrDefault(false)
            if(sameSource)tabs.firstOrNull {it.id==activeTabId}?.headers.orEmpty().forEach {(key,value)->
                // Observed WebView request headers take precedence for their
                // exact resource; Nuvio's headers fill missing values.
                if(!keySet().any{it.equals(key,true)})putString(key,value)
            }
        }
        // Prefer the actual page (top frame) when the video URL belongs to an
        // embedded player or an opaque CDN. Only explicit episode evidence from
        // the page title/URL may supply an automatic anime filename.
        val pageTitle=DownloadIdentity.episodeTitleFromPage(title, web?.url.orEmpty())
            .ifBlank { DownloadIdentity.episodeTitleFromPage(title, page) }
        // Only media downloads may inherit a page/anime title. APKs,
        // PDFs, ZIPs and other browser downloads must retain their actual
        // filename and extension from Content-Disposition, URL or MIME.
        val isVideo=DownloadFileKind.isVideoDownload(
            url,disposition,mime,capture!=null)
        val filename=if(pageTitle.isNotBlank() && isVideo)
            DownloadIdentity.capturedFileName(pageTitle,url,mime)
        else DownloadResourceIdentity.bestName(disposition,url,mime)
            ?: URLUtil.guessFileName(url,disposition,mime)
        offerError=""
        pendingOffer=BrowserDownloadOffer(
            url=url,headers=headers.keySet().associateWith {headers.getString(it).orEmpty()},
            title=pageTitle.ifBlank { title },filename=filename,capture=capture,
            hlsHint=mime?.contains("mpegurl",ignoreCase=true)==true,
            browserLength=browserLength?.takeIf{
                val extension=url.substringBefore('?').lowercase()
                it>0 && mime?.contains("mpegurl",true)!=true &&
                    mime?.contains("dash+xml",true)!=true &&
                    !listOf(".m3u8",".mpd",".meta4",".metalink",".torrent")
                        .any{suffix -> extension.endsWith(suffix)}
            },
            providerEstimatedSize=providerEstimatedSize?.takeIf {it>0},
        )
    }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() {
        if(WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST))
            runCatching {ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(null)}
        mediaRequests.clear()
        super.onDestroy()
    }
    companion object {
        fun isVideoLink(url: String): Boolean {
            val path=Uri.parse(url).path.orEmpty().lowercase()
            return listOf(".mp4",".mkv",".webm",".m4v",".mov",".avi",".ts",".m2ts",".flv",".wmv",".3gp",".m3u8",".mpd").any {path.endsWith(it)}
        }
    }
}
