package io.github.willzy12h.nocturne.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.willzy12h.nocturne.R

/** Event-driven motion permission; there is no timer while the screen is stopped or asleep. */
@Composable
internal fun rememberScreenInteractive(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var interactive by remember(power) { mutableStateOf(power?.isInteractive != false) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            interactive = power?.isInteractive != false
        }
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                interactive = intent.action == Intent.ACTION_SCREEN_ON && power?.isInteractive != false
            }
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(screen)
        }
    }
    return resumed && interactive
}

@Composable
internal fun rememberMotionAllowed(): Boolean {
    val foreground = rememberScreenInteractive()
    val context = LocalContext.current
    fun animationScale() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var scale by remember { mutableStateOf(animationScale()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = animationScale() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(foreground) { if (foreground) scale = animationScale() }
    return foreground && scale > 0f
}

/** One short pulse when an active state becomes visible; the marker then stays still. */
@Composable
internal fun ActiveIndicator(color: Color, active: Boolean, modifier: Modifier = Modifier, sizeDp: Int = 10) {
    if (!active) { Dot(color, sizeDp, modifier); return }
    val motion = rememberMotionAllowed()
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    val activeDescription = stringResource(R.string.ux_active)
    LaunchedEffect(motion, visible) {
        scale.snapTo(1f)
        if (motion && visible) {
            scale.animateTo(1.22f, tween(600))
            scale.animateTo(1f, tween(600))
        }
    }
    Dot(color, sizeDp, Modifier
        .onGloballyPositioned { bounds ->
            val clipped = bounds.boundsInWindow()
            visible = view.isShown && clipped.width > 0f && clipped.height > 0f
        }
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .semantics { stateDescription = activeDescription }.then(modifier))
}
