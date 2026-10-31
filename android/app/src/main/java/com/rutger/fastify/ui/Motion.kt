package com.rutger.fastify.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState

/**
 * Every animation in the app lasts at most [ANIM_MS] (Rutger's rule: half of the old 300 ms page
 * slide). Use [fast] for any animationSpec; never rely on Compose/Material defaults (springs of
 * 300-500 ms).
 *
 * Exception for now: Material's own motion (bottom sheets, nav bar indicator). In material3 1.4.0
 * MaterialTheme(motionScheme = ...) is internal; once 1.5 is stable, pass a MotionScheme built from
 * [fast] to the theme.
 */
const val ANIM_MS = 150

fun <T> fast(durationMillis: Int = ANIM_MS, delayMillis: Int = 0): TweenSpec<T> =
    tween(durationMillis.coerceAtMost(ANIM_MS), delayMillis, FastOutSlowInEasing)

/**
 * [LazyListState.animateScrollToItem] has no duration control: scroll with [fast] when the item
 * is on screen, else jump.
 */
suspend fun LazyListState.fastScrollToItem(index: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) scrollToItem(index)
    else animateScrollBy(item.offset.toFloat(), fast())
}
