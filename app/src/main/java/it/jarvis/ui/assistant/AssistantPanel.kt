package it.jarvis.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.jarvis.assistant.Who
import it.jarvis.assistant.VoiceAssistant
import it.jarvis.ui.theme.StarkHud

/** Conversazione con Jarvis in basso: trascrizione, campo di testo, ascolto continuo e HUD. */
@Composable
fun AssistantPanel(
    assistant: VoiceAssistant,
    hudOn: Boolean,
    onToggleHud: () -> Unit,
    onToggleContinuous: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines by assistant.transcript.collectAsStateWithLifecycle()
    val partial by assistant.partial.collectAsStateWithLifecycle()
    val continuous by assistant.continuous.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }

    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(10.dp)
            .background(Color(0xE6030B18), RoundedCornerShape(14.dp))
            .border(1.dp, StarkHud.PanelStroke, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (lines.isNotEmpty() || partial.isNotBlank()) {
            LazyColumn(Modifier.heightIn(max = 170.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(lines, key = { it.id }) { l ->
                    val (tag, color) = when (l.who) {
                        Who.USER -> "TU" to StarkHud.Ice
                        Who.JARVIS -> "JARVIS" to StarkHud.Cyan
                        Who.SYSTEM -> "SISTEMA" to StarkHud.Amber
                    }
                    Text("$tag  ${l.text}", color = color, fontSize = 13.sp)
                }
                if (partial.isNotBlank()) item { Text("TU  $partial…", color = StarkHud.TextDim, fontSize = 13.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Scrivi a Jarvis o tocca il nucleo", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { assistant.submitText(text); text = "" }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = StarkHud.Ice, unfocusedTextColor = StarkHud.Ice,
                    cursorColor = StarkHud.Cyan,
                ),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (continuous) "Ascolto continuo: ON" else "Ascolto continuo: OFF", continuous) {
                onToggleContinuous()
            }
            Chip(if (hudOn) "HUD: ON" else "HUD: OFF", hudOn, onToggleHud)
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    val c = if (active) StarkHud.Cyan else StarkHud.TextDim
    Box(
        Modifier.border(1.dp, c, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = c, fontSize = 12.sp) }
}
