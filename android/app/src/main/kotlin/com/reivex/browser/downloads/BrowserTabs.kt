package com.reivex.browser.downloads

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Only the selected tab owns a live WebView; inactive tabs retain history and a small RAM-only preview. */
internal class BrowserTab(val initialUrl:String="",val headers:Map<String,String> = emptyMap(),val id:String=java.util.UUID.randomUUID().toString()) {
    var title by mutableStateOf("Nova guia")
    var url by mutableStateOf(initialUrl)
    var home by mutableStateOf(initialUrl.isBlank())
    var preview by mutableStateOf<Bitmap?>(null)
    var saved=Bundle()
    var scrollX=0;var scrollY=0
}

@Composable internal fun BrowserTabOverview(tabs:List<BrowserTab>,selected:String,onSelect:(BrowserTab)->Unit,
    onClose:(BrowserTab)->Unit,onNew:()->Unit,onBack:()->Unit) {
    androidx.activity.compose.BackHandler(onBack=onBack)
    val dismissThreshold=with(LocalDensity.current) { 52.dp.toPx() }
    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack){Icon(Icons.Filled.ArrowBack,"Voltar")}
                Text("Guias (${tabs.size})",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton(onClick=onNew,modifier=Modifier.size(56.dp)){
                    Icon(Icons.Filled.Add,"Nova guia",modifier=Modifier.size(28.dp))
                }
            }
            LazyVerticalGrid(columns=GridCells.Adaptive(minSize=144.dp),contentPadding=PaddingValues(12.dp),
                horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(tabs,key={it.id}) {tab ->
                    var dragOffset by remember(tab.id) { mutableFloatStateOf(0f) }
                    Card(
                        onClick={onSelect(tab)},
                        modifier=Modifier.fillMaxWidth().aspectRatio(0.78f)
                            .offset { IntOffset(dragOffset.roundToInt(),0) }
                            .pointerInput(tab.id) {
                                var dragDistance=0f
                                detectHorizontalDragGestures(
                                    onHorizontalDrag={_,amount ->
                                        dragDistance+=amount
                                        dragOffset=dragDistance
                                    },
                                    onDragEnd={
                                        val shouldClose=abs(dragDistance)>=dismissThreshold
                                        dragOffset=0f
                                        dragDistance=0f
                                        if(shouldClose)onClose(tab)
                                    },
                                    onDragCancel={
                                        dragOffset=0f
                                        dragDistance=0f
                                    },
                                )
                            },
                        shape=RoundedCornerShape(14.dp),
                        border=BorderStroke(
                            if(tab.id==selected)1.5.dp else 1.dp,
                            if(tab.id==selected)MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        colors=CardDefaults.cardColors(
                            containerColor=if(tab.id==selected)MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(Modifier.fillMaxSize().padding(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().height(28.dp),
                                verticalAlignment=Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if(tab.home)Icons.Outlined.Home else Icons.Outlined.Language,
                                    contentDescription=null,
                                    modifier=Modifier.size(16.dp),
                                    tint=if(tab.id==selected)MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    tab.title,
                                    Modifier.weight(1f),
                                    style=MaterialTheme.typography.labelSmall.copy(fontSize=12.sp),
                                    maxLines=1,
                                    overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment=Alignment.Center,
                            ) {
                                val bitmap=tab.preview
                                if(bitmap!=null && !tab.home)Image(
                                    bitmap.asImageBitmap(),
                                    tab.title,
                                    Modifier.fillMaxSize(),
                                    contentScale=ContentScale.Crop,
                                ) else Icon(
                                    Icons.Outlined.Home,
                                    contentDescription=null,
                                    modifier=Modifier.size(32.dp),
                                    tint=MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                if(tab.home)"Início" else BrowserAdBlock.host(tab.url),
                                Modifier.fillMaxWidth().padding(top=3.dp),
                                style=MaterialTheme.typography.labelSmall.copy(fontSize=10.sp),
                                maxLines=1,
                                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
