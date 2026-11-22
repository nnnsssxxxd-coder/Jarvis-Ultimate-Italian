package it.jarvis.ime

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.jarvis.ui.theme.StarkHud
import it.jarvis.ui.touch.HologramTouchHost
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Shift { OFF, ONCE, LOCK }

sealed interface KbStatus {
    object Idle : KbStatus
    object AiBusy : KbStatus
    object Listening : KbStatus
    data class Error(val message: String) : KbStatus
}

data class KeyboardUiState(
    val shift: Shift = Shift.OFF,
    val symbols: Boolean = false,
    val compact: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val status: KbStatus = KbStatus.Idle,
    val sensitiveField: Boolean = false,
)

sealed interface KeyAction {
    data class Type(val text: String) : KeyAction
    object Backspace : KeyAction
    object Enter : KeyAction
    object Space : KeyAction
    object ToggleShift : KeyAction
    object ToggleSymbols : KeyAction
    object ToggleCompact : KeyAction
    object Mic : KeyAction
    object AiRewrite : KeyAction
    object SwitchKeyboard : KeyAction
    data class PickSuggestion(val word: String) : KeyAction
}

private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYMBOL_ROWS = listOf("1234567890", "@#€_&-+()/", "*\"':;!?")
private val ACCENTS = listOf("à", "è", "é", "ì", "ò", "ù")

/** Grafica coerente con l'app: pannelli traslucidi, bordo ciano, bagliore al tocco (+ effetto olografico leggero). */
@Composable
fun JarvisKeyboard(state: KeyboardUiState, onAction: (KeyAction) -> Unit) {
    val keyH = if (state.compact) 38.dp else 46.dp
    HologramTouchHost(intensity = 0.55f) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(StarkHud.NightBlue, StarkHud.Void)))
                .navigationBarsPadding()
                .padding(horizontal = 3.dp, vertical = 4.dp),
        ) {
            TopBar(state, onAction)
            if (!state.compact && !state.symbols) {
                Row(Modifier.fillMaxWidth()) { ACCENTS.forEach { Key(it, 1f, keyH * 0.8f) { onAction(KeyAction.Type(it)) } } }
            }
            val rows = if (state.symbols) SYMBOL_ROWS else LETTER_ROWS
            rows.forEachIndexed { idx, chars ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (idx == 2) {
                        if (!state.symbols) {
                            Key(
                                label = if (state.shift == Shift.LOCK) "⇪" else "⇧", weight = 1.4f, height = keyH,
                                accent = state.shift != Shift.OFF,
                            ) { onAction(KeyAction.ToggleShift) }
                        }
                    }
                    chars.forEach { ch ->
                        val shown = if (!state.symbols && state.shift != Shift.OFF) ch.uppercaseChar().toString() else ch.toString()
                        Key(shown, 1f, keyH) { onAction(KeyAction.Type(shown)) }
                    }
                    if (idx == 2) Key("⌫", 1.4f, keyH, repeat = true) { onAction(KeyAction.Backspace) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Key(if (state.symbols) "ABC" else "123", 1.5f, keyH) { onAction(KeyAction.ToggleSymbols) }
                Key(",", 1f, keyH) { onAction(KeyAction.Type(",")) }
                Key("JARVIS", 5f, keyH) { onAction(KeyAction.Space) }
                Key(".", 1f, keyH) { onAction(KeyAction.Type(".")) }
                Key("⏎", 1.5f, keyH, accent = true) { onAction(KeyAction.Enter) }
            }
        }
    }
}

@Composable
private fun TopBar(state: KeyboardUiState, onAction: (KeyAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            when (val s = state.status) {
                is KbStatus.AiBusy -> Text("JARVIS sta scrivendo…", color = StarkHud.Amber, fontSize = 13.sp)
                is KbStatus.Listening -> Text("● In ascolto — parla ora", color = StarkHud.Alert, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                is KbStatus.Error -> Text(s.message, color = StarkHud.Alert, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                is KbStatus.Idle -> Row {
                    state.suggestions.forEach { w ->
                        Key(w, 1f, 34.dp) { onAction(KeyAction.PickSuggestion(w)) }
                    }
                }
            }
        }
        if (!state.sensitiveField) {
            SmallKey("AI") { onAction(KeyAction.AiRewrite) }
            SmallKey(if (state.status is KbStatus.Listening) "■" else "MIC") { onAction(KeyAction.Mic) }
        }
        SmallKey(if (state.compact) "▲" else "▼") { onAction(KeyAction.ToggleCompact) }
        SmallKey("⌨") { onAction(KeyAction.SwitchKeyboard) }
    }
}

@Composable
private fun SmallKey(label: String, onClick: () -> Unit) {
    KeyBody(label, Modifier.padding(horizontal = 2.dp).width(44.dp).height(34.dp), accent = false, repeat = false, onClick = onClick)
}

@Composable
private fun RowScope.Key(
    label: String,
    weight: Float,
    height: Dp,
    accent: Boolean = false,
    repeat: Boolean = false,
    onClick: () -> Unit,
) {
    KeyBody(label, Modifier.weight(weight).padding(2.dp).height(height), accent, repeat, onClick)
}

/** Tasto: agisce alla pressione (più reattivo), con bagliore, vibrazione e, per ⌫, ripetizione se tenuto premuto. */
@Composable
private fun KeyBody(label: String, modifier: Modifier, accent: Boolean, repeat: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    pressed -> StarkHud.Cyan.copy(alpha = 0.40f)
                    accent -> StarkHud.Electric.copy(alpha = 0.30f)
                    else -> StarkHud.Panel
                },
            )
            .border(1.dp, StarkHud.Cyan.copy(alpha = if (pressed) 0.95f else 0.35f), shape)
            .pointerInput(onClick) {
                detectTapGestures(onPress = {
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                    if (repeat) {
                        coroutineScope {
                            val job = launch { delay(380); while (true) { onClick(); delay(45) } }
                            tryAwaitRelease()
                            job.cancel()
                        }
                    } else {
                        tryAwaitRelease()
                    }
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = StarkHud.Ice, fontSize = if (label.length > 3) 12.sp else 17.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}
