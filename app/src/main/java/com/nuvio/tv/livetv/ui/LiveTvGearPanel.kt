package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import kotlinx.coroutines.delay

/**
 * ⚙ on the full-screen bar: everything that isn't play, back 30 and forward 30. Record, Audio,
 * Subtitles and Favorite as tiles along the top, the rest underneath in two columns, then
 * "Customize the bar" (a page of switches, the bar changing underneath as you go) and Settings.
 * A glass panel just above the bar ([bottom] = how far up from the bottom of the screen).
 */
@Composable
internal fun GearPanel(
    bottom: Dp,
    background: String,
    isFavorite: Boolean,
    recording: Boolean,
    audioLabel: String,
    captionsOn: Boolean,
    sizeLabel: String,
    /** Every screen size (Fit, Crop…) with whether it's the one on; shown on the Screen size page. */
    sizeOptions: List<Pair<String, Boolean>>,
    onPickSize: (Int) -> Unit,
    sleepLabel: String,
    /** "Watch from the beginning" (live, catch-up channel), "Back to live" (replay) or "Go live" (paused). */
    timeAction: String?,
    hidden: Set<String>,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    onRecord: (() -> Unit)?,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onFavorite: () -> Unit,
    onSleep: () -> Unit,
    onTimeAction: () -> Unit,
    onPrevious: () -> Unit,
    onStreamInfo: () -> Unit,
    onSearch: (() -> Unit)?,
    onReload: () -> Unit,
    onHide: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var customizing by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler { if (customizing || sizing) { customizing = false; sizing = false } else onDismiss() }
    LaunchedEffect(customizing, sizing) { delay(60); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = bottom)
                .width(470.dp)
                .glass(glassColor(if (background == "standard") "darker" else background), RoundedCornerShape(20.dp))
                .padding(12.dp)
        ) {
            if (sizing) {
                // ---- Screen size: the same choices as the movie player; the picture changes as you pick
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    LiveText("‹  Screen size", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    LiveText("Applies to every channel", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
                }
                Spacer(Modifier.height(6.dp))
                var firstSet = false
                fun f(on: Boolean): Modifier = if (on && !firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                // The one that's on takes focus; the first one if none is.
                val focusIndex = sizeOptions.indexOfFirst { it.second }.coerceAtLeast(0)
                sizeOptions.withIndex().toList().chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { (i, opt) ->
                            GearRow(Modifier.weight(1f).then(f(i == focusIndex)), onClick = { onPickSize(i) }) { focused ->
                                LiveText(opt.first, size = 13.sp, weight = if (focused || opt.second) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                if (opt.second) LiveText("✓", size = 13.sp, color = gearText(focused, dim = true))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else if (!customizing) {
                // ---- the four everyday ones, as tiles
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    var firstSet = false
                    fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                    if (onRecord != null) GearTile(
                        Icons.Default.FiberManualRecord, if (recording) "Stop" else "Record", if (recording) "Recording" else null,
                        Modifier.weight(1f).then(f()), iconTint = LIVE_RED, onClick = onRecord
                    )
                    GearTile(Icons.Default.VolumeUp, "Audio", audioLabel.ifBlank { null }, Modifier.weight(1f).then(f()), onClick = onAudio)
                    GearTile(Icons.Default.ClosedCaption, "Subtitles", if (captionsOn) "On" else "Off", Modifier.weight(1f).then(f()), onClick = onSubtitles)
                    GearTile(
                        if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", if (isFavorite) "Saved" else null,
                        Modifier.weight(1f).then(f()), iconTint = if (isFavorite) Color(0xFFF5C400) else null, onClick = onFavorite
                    )
                }
                Spacer(Modifier.height(8.dp))
                // ---- the rest, two to a line
                val items = buildList<Triple<String, String?, () -> Unit>> {
                    add(Triple("Screen size", sizeLabel, { sizing = true }))
                    add(Triple("Sleep timer", sleepLabel, onSleep))
                    timeAction?.let { add(Triple(it, null, onTimeAction)) }
                    add(Triple("Previous channel", null, onPrevious))
                    add(Triple("Stream info", null, onStreamInfo))
                    onSearch?.let { add(Triple("Search", null, it)) }
                    add(Triple("Reload stream", null, onReload))
                    add(Triple("Hide channel", null, onHide))
                }
                items.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { (label, value, action) ->
                            GearRow(Modifier.weight(1f), onClick = action) { focused ->
                                LiveText(label, size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                value?.let { LiveText(it, size = 12.sp, color = gearText(focused, dim = true)) }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Box(Modifier.padding(vertical = 6.dp, horizontal = 4.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GearRow(Modifier.weight(1f), onClick = { customizing = true }) { focused ->
                        LiveText("Customize the bar", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                        LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
                    }
                    GearRow(Modifier.weight(1f), onClick = onSettings) { focused ->
                        LiveText("Settings", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                        LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
                    }
                }
            } else {
                // ---- Customize the bar: a switch for each part; the bar below changes as you go
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    LiveText("‹  Customize the bar", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    LiveText("The bar below changes as you go", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
                }
                Spacer(Modifier.height(6.dp))
                val parts = BarPart.ALL
                var firstSet = false
                fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                (parts.map { it as Any } + "reset").chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { entry ->
                            if (entry is Triple<*, *, *>) {
                                val key = entry.first as String
                                val on = key !in hidden
                                GearRow(Modifier.weight(1f).then(f()), onClick = { onToggle(key) }) { focused ->
                                    Column(Modifier.weight(1f)) {
                                        LiveText(entry.second as String, size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                                        (entry.third as String?)?.let { LiveText(it, size = 10.sp, color = gearText(focused, dim = true)) }
                                    }
                                    Switch(on, focused)
                                }
                            } else {
                                GearRow(Modifier.weight(1f), onClick = onReset) { focused ->
                                    LiveText("Reset to default", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun gearText(focused: Boolean, dim: Boolean = false): Color =
    if (focused) Color(0xFF111111).copy(alpha = if (dim) 0.65f else 1f) else Color.White.copy(alpha = if (dim) 0.6f else 0.95f)

/** One line in the gear panel: white when highlighted. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GearRow(modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content(focused) }
}

/** Record / Audio / Subtitles / Favorite: an icon, its name and what it's set to. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GearTile(
    icon: ImageVector,
    label: String,
    value: String?,
    modifier: Modifier,
    iconTint: Color? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = iconTint ?: gearText(focused), modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        LiveText(label, size = 12.sp, weight = FontWeight.SemiBold, color = gearText(focused))
        LiveText(value ?: " ", size = 10.sp, color = gearText(focused, dim = true), maxLines = 1)
    }
}

/** An on / off switch: green when on. */
@Composable
private fun Switch(on: Boolean, focused: Boolean) {
    val track = when {
        on -> Color(0xFF22C55E)
        focused -> Color(0xFF9CA3AF)
        else -> Color.White.copy(alpha = 0.22f)
    }
    Box(Modifier.width(34.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(track)) {
        Box(
            Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(2.dp)
                .size(16.dp).clip(CircleShape).background(Color.White)
        )
    }
}
