package it.jarvis.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.StateFlow

/** true finché l'app è visibile (STARTED a livello di processo). Serve a fermare animazioni e campionamenti in background. */
@Composable
fun rememberProcessStarted(): State<Boolean> {
    val lifecycle = remember { ProcessLifecycleOwner.get().lifecycle }
    val state = remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> state.value = true
                Lifecycle.Event.ON_STOP -> state.value = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

/** Come collectAsState, ma smette di raccogliere (e quindi di campionare) quando l'app non è visibile. */
@Composable
fun <T> StateFlow<T>.collectWhileStarted(): State<T> {
    val started by rememberProcessStarted()
    val state = remember(this) { mutableStateOf(value) }
    LaunchedEffect(this, started) {
        if (started) collect { state.value = it }
    }
    return state
}
