package com.reivex.browser.downloads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reivex.browser.R
import org.json.JSONArray
import org.json.JSONObject

/** Local-only navigation list; there are no built-in/advertised shortcut sites. */
internal data class BrowserStartLink(val url: String, val title: String)

internal class BrowserStartPageStore(context: Context) {
    private val prefs = context.getSharedPreferences("reivex.start_page", Context.MODE_PRIVATE)

    private val iconDirectory = File(context.filesDir, "browser-favicons")

    private fun iconFile(url: String): File? = runCatching {
        val host = Uri.parse(url).host?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(host.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        File(iconDirectory, hash + ".png")
    }.getOrNull()

    /** WebView's own favicon, cached locally; no third-party favicon service. */
    fun saveFavicon(url: String, bitmap: Bitmap) {
        if (!isWebUrl(url) || bitmap.width <= 0 || bitmap.height <= 0) return
        val file = iconFile(url) ?: return
        runCatching {
            iconDirectory.mkdirs()
            val small = Bitmap.createScaledBitmap(bitmap, 72, 72, true)
            file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (small !== bitmap) small.recycle()
        }
    }

    fun favicon(url: String): Bitmap? {
        val file = iconFile(url) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun recents(): List<BrowserStartLink> = read("recents")
    fun favorites(): List<BrowserStartLink> = read("favorites")

    fun recordVisit(url: String, title: String) {
        if (!isWebUrl(url)) return
        val cleanTitle = title.trim().ifEmpty { Uri.parse(url).host.orEmpty() }.take(160)
        val entries = listOf(BrowserStartLink(url, cleanTitle)) +
            recents().filterNot { it.url == url }
        write("recents", entries.take(40))
    }

    fun toggleFavorite(link: BrowserStartLink) {
        if (!isWebUrl(link.url)) return
        val current = favorites()
        val result = if (current.any { it.url == link.url }) {
            current.filterNot { it.url == link.url }
        } else {
            listOf(link.copy(title = link.title.trim().ifEmpty { Uri.parse(link.url).host.orEmpty() })) +
                current
        }
        write("favorites", result.take(40))
    }

    fun removeRecent(url: String) = write("recents", recents().filterNot { it.url == url })
    fun clearRecents() = prefs.edit().remove("recents").apply()
    fun clearFavicons() { iconDirectory.deleteRecursively() }

    private fun isWebUrl(url: String): Boolean = runCatching {
        val uri = Uri.parse(url)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun read(key: String): List<BrowserStartLink> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            if (!isWebUrl(url)) null else BrowserStartLink(
                url = url,
                title = item.optString("title").ifBlank { Uri.parse(url).host.orEmpty() }
            )
        }
    }.getOrDefault(emptyList())

    private fun write(key: String, entries: List<BrowserStartLink>) {
        val json = JSONArray()
        entries.forEach { link ->
            json.put(JSONObject().put("url", link.url).put("title", link.title))
        }
        prefs.edit().putString(key, json.toString()).apply()
    }
}

private val startBorder = Color(0xFF55585E)
private val startGradient = Brush.horizontalGradient(
    listOf(Color(0xFF101113), Color(0xFF151619), Color(0xFF101113))
)

/** The same launcher R is used here and beside the text "ReiVex". */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_reivex_launcher_foreground),
        contentDescription = "Símbolo ReiVex",
        modifier = modifier
    )
}

@Composable
internal fun BrowserStartPage(
    search: String,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    recents: List<BrowserStartLink>,
    favorites: List<BrowserStartLink>,
    onOpen: (String) -> Unit,
    onToggleFavorite: (BrowserStartLink) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedFavorites by remember { mutableStateOf(false) }
    val list = if (selectedFavorites) favorites else recents
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(62.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Modifier.size(52.dp))
            Spacer(Modifier.width(11.dp))
            Box(Modifier.width(1.dp).height(44.dp).background(Color(0xFF777A80)))
            Spacer(Modifier.width(12.dp))
            Text(
                "ReiVex",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                color = Color.White,
                letterSpacing = (-0.6).sp,
            )
        }
        Spacer(Modifier.height(74.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(66.dp),
            shape = CircleShape,
            color = Color(0xFF111214),
            border = BorderStroke(1.dp, startBorder),
        ) {
            Row(
                Modifier.fillMaxSize().background(startGradient)
                    .padding(start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Search, contentDescription = null,
                    modifier = Modifier.size(28.dp), tint = Color.White
                )
                Spacer(Modifier.width(17.dp))
                BasicTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = Color.White, fontSize = 17.sp,
                        fontStyle = FontStyle.Normal
                    ),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSearch() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (search.isEmpty()) Text(
                                "Pesquisa na web",
                                color = Color(0xFFCACBD0),
                                fontSize = 17.sp,
                                fontStyle = FontStyle.Italic
                            )
                            inner()
                        }
                    },
                )
                Box(Modifier.width(1.dp).height(29.dp).background(startBorder))
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = onSearch, modifier = Modifier.size(36.dp)) {
                    BrandMark(Modifier.size(32.dp))
                }
            }
        }
        Spacer(Modifier.height(58.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { selectedFavorites = false },
                shape = CircleShape,
                color = if (!selectedFavorites) Color(0xFF141517) else Color.Transparent,
                border = if (!selectedFavorites) BorderStroke(1.dp, startBorder) else null,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.History, contentDescription = null,
                        tint = Color(0xFFE1E1E4), modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Recentes", color = Color.White, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = { selectedFavorites = true },
                shape = CircleShape,
                color = if (selectedFavorites) Color(0xFF141517) else Color.Transparent,
                border = if (selectedFavorites) BorderStroke(1.dp, startBorder) else null,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.StarBorder, contentDescription = null,
                        tint = Color(0xFFE1E1E4), modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("Favoritos", color = Color.White, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF383A3D)))
        }
        Spacer(Modifier.height(14.dp))
        if (list.isEmpty()) {
            Text(
                if (selectedFavorites) "Nenhum favorito salvo."
                else "Suas páginas visitadas aparecerão aqui.",
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                fontSize = 13.sp,
                color = Color(0xFF999BA1)
            )
        } else {
            list.forEach { link ->
                val isFavorite = favorites.any { it.url == link.url }
                BrowserStartPageRow(
                    link = link,
                    isFavorite = isFavorite,
                    inFavorites = selectedFavorites,
                    onOpen = { onOpen(link.url) },
                    onToggleFavorite = { onToggleFavorite(link) },
                    onRemoveRecent = { onRemoveRecent(link.url) },
                )
                Spacer(Modifier.height(10.dp))
            }
            if (!selectedFavorites) {
                TextButton(onClick = onClearRecents) {
                    Text("Limpar recentes", color = Color(0xFFB8BBC1), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BrowserStartPageRow(
    link: BrowserStartLink,
    isFavorite: Boolean,
    inFavorites: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemoveRecent: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val favicon = remember(link.url) { BrowserStartPageStore(context).favicon(link.url) }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        color = Color(0xFF111214),
        shape = RoundedCornerShape(40.dp),
        border = BorderStroke(1.dp, Color(0xFF33363A)),
    ) {
        Row(
            Modifier.fillMaxSize().background(startGradient)
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFF222326)),
                contentAlignment = Alignment.Center,
            ) {
                if (favicon != null) {
                    Image(
                        bitmap = favicon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    Text(
                        link.title.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 21.sp, fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    link.title, color = Color.White,
                    fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    runCatching { Uri.parse(link.url).host.orEmpty() }.getOrDefault(link.url),
                    color = Color(0xFFB5B6BC), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.MoreVert, "Opções da página", tint = Color.White)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Remover dos favoritos" else "Adicionar aos favoritos") },
                        onClick = { menuOpen = false; onToggleFavorite() },
                    )
                    if (!inFavorites) {
                        DropdownMenuItem(
                            text = { Text("Remover dos recentes") },
                            onClick = { menuOpen = false; onRemoveRecent() },
                        )
                    }
                }
            }
        }
    }
}
