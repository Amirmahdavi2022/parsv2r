package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

// The connect dock. Same inputs and actions as upstream's bar (tap the text to test the
// current server, tap the button to start or stop); only the look changed.
private val dockShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val goldTop = Color(0xFFFFD27A)
private val goldBottom = Color(0xFFC8891F)
private val liveGreen = Color(0xFF3DDC84)

@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    isDarkTheme: Boolean,
    onAction: (MainAction) -> Unit
) {
    val lines = displayText.lines().filter { it.isNotBlank() }
    val title = lines.firstOrNull().orEmpty()
    val detail = lines.drop(1).joinToString("\n")
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = dockShape, clip = false)
            .clip(dockShape)
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.primary.copy(alpha = if (isDarkTheme) 0.18f else 0.25f), dockShape)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = { onAction(MainAction.TestCurrentServer) })
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = displayText },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) liveGreen else scheme.outline)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            ConnectButton(isRunning = isRunning, onClick = { onAction(MainAction.ToggleService) })
        }
    }
}

@Composable
private fun ConnectButton(isRunning: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (isRunning) {
        Brush.verticalGradient(listOf(goldTop, goldBottom))
    } else {
        Brush.verticalGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceContainerHigh))
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .shadow(elevation = if (isRunning) 10.dp else 4.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, scheme.primary.copy(alpha = if (isRunning) 0.9f else 0.6f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = if (isRunning) painterResource(R.drawable.ic_stop_24dp)
            else painterResource(R.drawable.ic_play_24dp),
            contentDescription = stringResource(
                if (isRunning) R.string.acc_stop else R.string.acc_start
            ),
            tint = if (isRunning) Color(0xFF2A1A00) else scheme.primary,
            modifier = Modifier.size(28.dp)
        )
    }
}
