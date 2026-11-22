package it.jarvis.ui.apps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.jarvis.apps.AppCategory
import it.jarvis.apps.AppSearch
import it.jarvis.apps.AppsRepository
import it.jarvis.apps.InstalledApp
import it.jarvis.ui.home.HudBackground
import it.jarvis.ui.theme.StarkHud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.util.LruCache
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap

private enum class Filter(val label: String) { ALL("Tutte"), FAVORITES("Preferiti"), RECENT("Recenti") }

private val iconCache = LruCache<String, ImageBitmap>(96)

/** Pagina APP: solo le app realmente installate. Tocco = apri, pressione lunga = preferito. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(repo: AppsRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var category by remember { mutableStateOf<AppCategory?>(null) }
    var favorites by remember { mutableStateOf(repo.favorites()) }
    var recents by remember { mutableStateOf(repo.recents()) }

    LaunchedEffect(Unit) { apps = repo.loadInstalled() }

    val all = apps.orEmpty()
    val categories = all.map { it.category }.distinct().sortedBy { it.ordinal }
    val visible = remember(all, query, filter, category, favorites, recents) {
        var list = AppSearch.filter(all, query)
        list = when (filter) {
            Filter.ALL -> list
            Filter.FAVORITES -> list.filter { it.packageName in favorites }
            Filter.RECENT -> recents.mapNotNull { pkg -> list.firstOrNull { it.packageName == pkg } }
        }
        category?.let { c -> list = list.filter { it.category == c } }
        list
    }

    HudBackground(modifier) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("APPLICAZIONI", color = StarkHud.Ice, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.fillMaxWidth().clip(shape).background(StarkHud.Panel)
                    .border(1.dp, StarkHud.PanelStroke, shape).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (query.isEmpty()) Text("Cerca tra ${all.size} app", color = StarkHud.TextDim, fontSize = 14.sp)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = StarkHud.Ice, fontSize = 14.sp),
                    cursorBrush = SolidColor(StarkHud.Cyan), modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Filter.values().toList()) { f ->
                    Chip(f.label, selected = filter == f && category == null) { filter = f; category = null }
                }
                items(categories) { c ->
                    Chip(c.label, selected = category == c) { category = if (category == c) null else c; filter = Filter.ALL }
                }
            }
            Spacer(Modifier.height(10.dp))

            when {
                apps == null -> Text("Lettura delle app installate…", color = StarkHud.TextDim, fontSize = 13.sp)
                visible.isEmpty() -> Text(
                    when {
                        query.isNotEmpty() -> "Nessuna app corrisponde a \"$query\"."
                        filter == Filter.FAVORITES -> "Nessun preferito: tieni premuta un'app per aggiungerla."
                        filter == Filter.RECENT -> "Nessuna app aperta di recente da JARVIS."
                        else -> "Nessuna app trovata."
                    },
                    color = StarkHud.TextDim, fontSize = 13.sp,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(78.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(visible, key = { _, a -> a.packageName }) { index, app ->
                        AppTile(
                            app = app, index = index, repo = repo, favorite = app.packageName in favorites,
                            onOpen = {
                                if (repo.launch(app.packageName)) recents = repo.recents()
                                else Toast.makeText(context, "Impossibile aprire ${app.label}", Toast.LENGTH_SHORT).show()
                            },
                            onToggleFavorite = {
                                val now = repo.toggleFavorite(app.packageName)
                                favorites = repo.favorites()
                                Toast.makeText(context, if (now) "${app.label} aggiunta ai preferiti" else "${app.label} rimossa dai preferiti", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected) StarkHud.Cyan.copy(alpha = 0.22f) else StarkHud.Panel)
            .border(1.dp, StarkHud.Cyan.copy(alpha = if (selected) 0.9f else 0.35f), shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = StarkHud.Ice, fontSize = 12.sp) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: InstalledApp,
    index: Int,
    repo: AppsRepository,
    favorite: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index * 16L, 200L))
        enter.animateTo(1f, tween(260))
    }
    val icon by produceState<ImageBitmap?>(iconCache.get(app.packageName), app.packageName) {
        if (value == null) {
            val bmp = withContext(Dispatchers.Default) { repo.loadIcon(app.packageName) }
            if (bmp != null) { iconCache.put(app.packageName, bmp); value = bmp }
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .graphicsLayer { alpha = enter.value; scaleX = 0.85f + 0.15f * enter.value; scaleY = 0.85f + 0.15f * enter.value; translationY = (1f - enter.value) * 16f }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(StarkHud.Panel, StarkHud.NightBlue.copy(alpha = 0.7f))))
            .border(1.dp, if (favorite) StarkHud.Amber.copy(alpha = 0.8f) else StarkHud.PanelStroke, shape)
            .combinedClickable(onClick = onOpen, onLongClick = onToggleFavorite)
            .padding(8.dp)
            .semantics { contentDescription = app.label + if (favorite) ", preferita" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(42.dp)) }
        }
        Spacer(Modifier.height(4.dp))
        Text(app.label, color = StarkHud.Ice, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
