package com.personal.pocketagent.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.pocketagent.playback.DocumentPlaybackController
import com.personal.pocketagent.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PdfFullViewerDialog(
    pdfUri: Uri,
    controller: DocumentPlaybackController,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val playerState by controller.playerState.collectAsState()
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(pdfUri) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
                if (pfd != null) {
                    val renderer = PdfRenderer(pfd)
                    val rendered = mutableListOf<Bitmap>()
                    val total = minOf(renderer.pageCount, 30)
                    for (i in 0 until total) {
                        val page = renderer.openPage(i)
                        val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        rendered.add(bmp)
                    }
                    renderer.close()
                    pfd.close()
                    pages = rendered
                }
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = playerState.title.ifEmpty { "PDF Viewer" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Viewer")
                    }
                }

                // Rendered Document Pages
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (pages.isEmpty()) {
                        Text(
                            text = "Could not render pages visually. Reading text mode active.",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(pages.size) { index ->
                                Image(
                                    bitmap = pages[index].asImageBitmap(),
                                    contentDescription = "Page ${index + 1}",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom Floating Teleprompter Bar
                Surface(
                    color = Color(0xF01E293B),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "READING ALOUD (Line ${playerState.currentLineIndex + 1}/${playerState.totalLines})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = playerState.currentLineText.ifEmpty { "(End of document)" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { controller.previousLine() }) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Line", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    if (playerState.state == PlaybackState.PLAYING) controller.pause()
                                    else controller.play()
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                            ) {
                                Icon(
                                    imageVector = if (playerState.state == PlaybackState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = { controller.nextLine() }) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next Line", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
