package com.personal.pocketagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.pocketagent.playback.DocumentPlaybackController
import com.personal.pocketagent.playback.PlaybackState

@Composable
fun DocumentPlayerCard(
    controller: DocumentPlaybackController,
    modifier: Modifier = Modifier
) {
    val playerState by controller.playerState.collectAsState()

    AnimatedVisibility(
        visible = playerState.lines.isNotEmpty(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📖 ${playerState.title.ifEmpty { "Active Document" }}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Line ${playerState.currentLineIndex + 1} of ${playerState.totalLines}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp)
                ) {
                    Text(
                        text = playerState.currentLineText.ifEmpty { "(End of document)" },
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (playerState.totalLines > 1) {
                    Slider(
                        value = playerState.currentLineIndex.toFloat(),
                        onValueChange = { targetPos ->
                            controller.seekTo(targetPos.toInt())
                        },
                        valueRange = 0f..(playerState.totalLines - 1).toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { controller.previousLine() }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Line")
                    }

                    if (playerState.state == PlaybackState.PLAYING) {
                        FilledIconButton(onClick = { controller.pause() }) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                        }
                    } else {
                        FilledIconButton(onClick = { controller.play() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                        }
                    }

                    IconButton(onClick = { controller.stop() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }

                    IconButton(onClick = { controller.nextLine() }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Line")
                    }
                }
            }
        }
    }
}
