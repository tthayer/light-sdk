package com.thelightphone.sample.music

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Left-to-right drag that pops the screen once the swipe passes [thresholdDp],
 * mirroring the iPod's "swipe back to the previous menu" gesture. Vertical list
 * scrolling is unaffected — this only reacts to horizontal drags.
 */
fun Modifier.swipeBackGesture(thresholdDp: Float = 56f, onBack: () -> Unit): Modifier =
    pointerInput(Unit) {
        val thresholdPx = thresholdDp.dp.toPx()
        var accumulated = 0f
        var triggered = false
        detectHorizontalDragGestures(
            onDragStart = {
                accumulated = 0f
                triggered = false
            },
            onHorizontalDrag = { _, dragAmount ->
                accumulated += dragAmount
                if (!triggered && accumulated > thresholdPx) {
                    triggered = true
                    onBack()
                }
            },
        )
    }

/**
 * Standard chrome for every music screen: themed background, a [LightTopBar]
 * with an optional back button, and swipe-back when [onBack] is provided.
 */
@Composable
fun MusicScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)
                .then(if (onBack != null) Modifier.swipeBackGesture(onBack = onBack) else Modifier),
        ) {
            LightTopBar(
                leftButton = onBack?.let {
                    LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = it)
                },
                center = LightTopBarCenter.Text(title),
            )
            content()
        }
    }
}

/** A drill-down menu row: label on the left, chevron on the right. */
@Composable
fun MenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: LightIconConfiguration? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            LightIcon(
                icon = leadingIcon,
                size = 1.75f,
                modifier = Modifier.padding(end = 0.75f.gridUnitsAsDp()),
            )
        }
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LightIcon(icon = LightIcons.ARROW_RIGHT, size = 1.5f)
    }
}

/** A song row: title, optional secondary line, and a speaker marker when playing. */
@Composable
fun TrackRow(
    title: String,
    secondary: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.6f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = title,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                LightText(
                    text = secondary,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent) {
            LightIcon(
                icon = LightIcons.SPEAKER,
                size = 1.5f,
                modifier = Modifier.padding(start = 0.75f.gridUnitsAsDp()),
            )
        }
    }
}

/** Formats milliseconds as m:ss (negative values render as 0:00). */
fun formatTime(ms: Int): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
