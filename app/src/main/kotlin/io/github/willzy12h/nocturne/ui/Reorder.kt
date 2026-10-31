package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * The one drag style used by every reorderable list: the held row lifts slightly, follows the finger and draws
 * above its neighbours, which slide out of its way.
 */
internal fun Modifier.heldForReorder(held: Boolean, offset: () -> Float): Modifier =
    zIndex(if (held) 1f else 0f).graphicsLayer {
        translationY = if (held) offset() else 0f
        if (held) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12.dp.toPx() }
    }

/**
 * Scrolls a lazy list under the held row once it has been carried into the top or bottom edge. Holding a row that
 * already sits near an edge does not scroll: the finger has to move it towards that edge first, so a held row stays
 * put until it is moved.
 */
internal suspend fun scrollWhileHeld(listState: LazyListState, reorder: ProfileReorder, spacing: Float, threshold: Float) {
    while (reorder.dragging != null) {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == reorder.dragging }
        if (item != null) {
            val top = item.offset + reorder.offset
            val bottom = top + item.size
            val edge = info.viewportSize.height * .12f
            val step = when {
                reorder.travel < -threshold && top < info.viewportStartOffset + edge -> -12f
                reorder.travel > threshold && bottom > info.viewportEndOffset - edge -> 12f
                else -> 0f
            }
            if (step != 0f) {
                val moved = listState.scrollBy(step)
                reorder.offset += moved
                reorder.swapIfPast(listState, spacing)
            }
        }
        delay(16)
    }
}
