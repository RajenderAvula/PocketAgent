package com.personal.pocketagent.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

data class AnnotationStroke(val path: Path, val color: Color, val strokeWidth: Float)

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

    // Zoom & Pan transformation state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Annotation and Drawer States
    var isAnnotationMode by remember { mutableStateOf(false) }
    var penColor by remember { mutableStateOf(Color(0x80FFEB3B)) } // Translucent yellow highlighter
    val strokes = remember { mutableStateListOf<AnnotationStroke>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var drawTrigger by remember { mutableLongStateOf(0L) }
    var showTranscriptDrawer by remember { mutableStateOf(false) }

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
                // Top Header Toolbar with Zoom, Annotate, Jump-To-Line, and Close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = playerState.title.ifEmpty { "PDF Viewer" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        maxLines = 1
                    )

                    // Zoom In & Zoom Out Buttons
                    IconButton(onClick = { scale = (scale + 0.25f).coerceAtMost(3.5f) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In")
                    }
                    IconButton(onClick = {
                        scale = (scale - 0.25f).coerceAtLeast(0.75f)
                        if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                    }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out")
                    }

                    // Annotate / Highlight Toggle Button
                    IconButton(
                        onClick = { isAnnotationMode = !isAnnotationMode },
                        modifier = Modifier.background(
                            if (isAnnotationMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            CircleShape
                        )
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Draw / Highlight",
                            tint = if (isAnnotationMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }

                    // Jump to Line / Transcript Drawer Toggle
                    IconButton(
                        onClick = { showTranscriptDrawer = !showTranscriptDrawer },
                        modifier = Modifier.background(
                            if (showTranscriptDrawer) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            CircleShape
                        )
                    ) {
                        Icon(Icons.Default.FormatListNumbered, contentDescription = "Jump to Line")
                    }

                    // Close Viewer Button
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Viewer")
                    }
                }

                // Annotation Palette Bar
                if (isAnnotationMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tool:", style = MaterialTheme.typography.labelSmall)
                        Button(
                            onClick = { penColor = Color(0x80FFEB3B) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFEB3B)),
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Highlighter", color = Color.Black, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { penColor = Color(0xFFEF4444) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Red Pen", color = Color.White, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { strokes.clear() }) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear Marks", fontSize = 11.sp)
                        }
                    }
                }

                // Document Body with Zoom, Pan, Canvas Overlay, and Jump Drawer
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(isAnnotationMode) {
                            if (!isAnnotationMode) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(0.75f, 3.5f)
                                    if (scale > 1f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            }
                        }
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (pages.isEmpty()) {
                        Text(
                            text = "Could not render pages visually. Reading text mode active.",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        // Document Scroll View with graphicsLayer scaling
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        ) {
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

                        // Drawing / Annotation Canvas Layer
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(isAnnotationMode) {
                                    if (isAnnotationMode) {
                                        detectDragGestures(
                                            onDragStart = { startPos ->
                                                val p = Path().apply { moveTo(startPos.x, startPos.y) }
                                                currentPath = p
                                                strokes.add(
                                                    AnnotationStroke(
                                                        p,
                                                        penColor,
                                                        if (penColor == Color(0xFFEF4444)) 5f else 28f
                                                    )
                                                )
                                                drawTrigger++
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                currentPath?.lineTo(change.position.x, change.position.y)
                                                drawTrigger++
                                            },
                                            onDragEnd = {
                                                currentPath = null
                                                drawTrigger++
                                            },
                                            onDragCancel = {
                                                currentPath = null
                                                drawTrigger++
                                            }
                                        )
                                    }
                                }
                        ) {
                            @Suppress("UNUSED_VARIABLE")
                            val trigger = drawTrigger
                            for (stroke in strokes) {
                                drawPath(
                                    path = stroke.path,
                                    color = stroke.color,
                                    style = Stroke(
                                        width = stroke.strokeWidth,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }

                    // Transcript / Jump-to-Line Drawer
                    if (showTranscriptDrawer) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(280.dp)
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 8.dp
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                Text(
                                    text = "Tap to Move Reading Cursor:",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(8.dp)
                                )
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    itemsIndexed(playerState.lines) { idx, line ->
                                        val isCurrent = idx == playerState.currentLineIndex
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    controller.seekTo(idx)
                                                    controller.play()
                                                    showTranscriptDrawer = false
                                                },
                                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                        ) {
                                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text("${idx + 1}.", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                                Spacer(Modifier.width(6.dp))
                                                Text(line, maxLines = 2, fontSize = 11.sp, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Floating Teleprompter Bar (Current line spoken aloud)
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
                            maxLines = 3
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
