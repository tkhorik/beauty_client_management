package com.beauty.app.ui.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.VisitAttachmentDto
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.EmeraldStatus
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextMuted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded attachment images, kept in memory only.
 *
 * Attachment bytes sit behind an authenticated endpoint, so every thumbnail is
 * a network round trip. Without a cache, scrolling a visit history back and
 * forth downloads the same photos again each time a row re-enters the screen.
 * Keyed by organization as well as id because the request that produced the
 * bytes was authorized for that organization; nothing is written to disk.
 */
private object AttachmentBitmapCache : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private suspend fun loadAttachmentBitmap(
    repository: BeautyRepository,
    organizationId: String,
    attachmentId: String
): ImageBitmap? {
    val key = "$organizationId/$attachmentId"
    AttachmentBitmapCache.get(key)?.let { return it.asImageBitmap() }
    return try {
        val bytes = repository.downloadAttachment(organizationId, attachmentId)
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            ?: return null
        AttachmentBitmapCache.put(key, bitmap)
        bitmap.asImageBitmap()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private sealed interface ImageLoad {
    object Loading : ImageLoad
    object Failed : ImageLoad
    data class Ready(val image: ImageBitmap) : ImageLoad
}

@Composable
private fun rememberAttachmentImage(
    repository: BeautyRepository,
    organizationId: String,
    attachmentId: String?
): ImageLoad {
    var state by remember(organizationId, attachmentId) { mutableStateOf<ImageLoad>(ImageLoad.Loading) }
    LaunchedEffect(organizationId, attachmentId) {
        if (attachmentId == null) { state = ImageLoad.Failed; return@LaunchedEffect }
        state = loadAttachmentBitmap(repository, organizationId, attachmentId)
            ?.let { ImageLoad.Ready(it) } ?: ImageLoad.Failed
    }
    return state
}

private fun tagColor(tag: String): Color = when (tag) {
    "BEFORE" -> RoseGoldPrimary
    "AFTER" -> EmeraldStatus
    else -> Color.White
}

/** The 100×100 tile the web client's visit timeline shows for each attachment. */
@Composable
fun AttachmentThumbnail(
    repository: BeautyRepository,
    organizationId: String,
    attachment: VisitAttachmentDto,
    onClick: () -> Unit
) {
    val load = rememberAttachmentImage(repository, organizationId, attachment.id)
    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color(0x33E5B899), RoundedCornerShape(10.dp))
            .background(Color(0xFF0F0E13))
            .clickable(onClick = onClick)
    ) {
        when (load) {
            is ImageLoad.Ready -> Image(
                load.image,
                contentDescription = attachment.caption ?: "${attachment.tag} photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            ImageLoad.Loading -> CircularProgressIndicator(
                Modifier.size(20.dp).align(Alignment.Center),
                strokeWidth = 2.dp,
                color = RoseGoldPrimary
            )
            ImageLoad.Failed -> Text("Unavailable", fontSize = 10.sp, color = TextMuted, modifier = Modifier.align(Alignment.Center))
        }
        PhotoLabel(attachment.tag, Modifier.align(Alignment.BottomStart).padding(4.dp), small = true)
    }
}

@Composable
private fun PhotoLabel(text: String, modifier: Modifier = Modifier, small: Boolean = false, tag: String = text) {
    Surface(color = Color(0xCC000000), shape = RoundedCornerShape(if (small) 6.dp else 10.dp), modifier = modifier) {
        Text(
            text,
            color = tagColor(tag),
            fontSize = if (small) 9.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = if (small) 6.dp else 10.dp, vertical = 2.dp)
        )
    }
}

/**
 * Mirrors `PhotoCompareModal.tsx`: BEFORE falls back to the first attachment,
 * AFTER to the second (or the first again), so a single photo still opens.
 */
internal fun pickComparePair(attachments: List<VisitAttachmentDto>): Pair<VisitAttachmentDto?, VisitAttachmentDto?> {
    val before = attachments.firstOrNull { it.tag == "BEFORE" } ?: attachments.firstOrNull()
    val after = attachments.firstOrNull { it.tag == "AFTER" } ?: attachments.getOrNull(1) ?: attachments.firstOrNull()
    return before to after
}

@Composable
fun PhotoCompareDialog(
    repository: BeautyRepository,
    organizationId: String,
    attachments: List<VisitAttachmentDto>,
    onDismiss: () -> Unit
) {
    val (before, after) = remember(attachments) { pickComparePair(attachments) }
    val beforeLoad = rememberAttachmentImage(repository, organizationId, before?.id)
    val afterLoad = rememberAttachmentImage(repository, organizationId, after?.id)
    var sideBySide by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = CardSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(0.95f)
        ) {
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Before & After Comparison", color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Side-by-side & slider inspection", color = TextMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = TextMuted) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !sideBySide, onClick = { sideBySide = false }, label = { Text("Slider Split") })
                    FilterChip(selected = sideBySide, onClick = { sideBySide = true }, label = { Text("Side by Side") })
                }

                val beforeImage = (beforeLoad as? ImageLoad.Ready)?.image
                val afterImage = (afterLoad as? ImageLoad.Ready)?.image
                when {
                    beforeLoad == ImageLoad.Loading || afterLoad == ImageLoad.Loading ->
                        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = RoseGoldPrimary)
                        }
                    beforeImage == null || afterImage == null ->
                        Text("Could not load these photos. Check your connection and try again.", color = MaterialTheme.colorScheme.error)
                    sideBySide -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SidePhoto(beforeImage, "BEFORE", before?.caption ?: "Before procedure baseline photo", Modifier.weight(1f))
                        SidePhoto(afterImage, "AFTER", after?.caption ?: "Post-procedure finished result", Modifier.weight(1f))
                    }
                    else -> SliderCompare(beforeImage, afterImage)
                }
            }
        }
    }
}

@Composable
private fun SidePhoto(image: ImageBitmap, tag: String, caption: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp))) {
            Image(image, contentDescription = caption, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            PhotoLabel(tag, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Text(caption, fontSize = 12.sp)
    }
}

/**
 * Both photos are laid out at the full frame size and the BEFORE layer is
 * clipped, not resized. Shrinking the layer instead (as a width-fraction box
 * would) re-crops the BEFORE photo at every slider position, so the two images
 * stop lining up — which defeats the point of a split comparison.
 */
@Composable
private fun SliderCompare(before: ImageBitmap, after: ImageBitmap) {
    var split by rememberSaveable { mutableFloatStateOf(0.5f) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTapGestures { offset -> split = (offset.x / size.width).coerceIn(0f, 1f) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        split = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
        ) {
            Image(after, contentDescription = "After procedure", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Image(
                before,
                contentDescription = "Before procedure",
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent { clipRect(right = size.width * split) { this@drawWithContent.drawContent() } },
                contentScale = ContentScale.Crop
            )
            PhotoLabel("AFTER PROCEDURE", Modifier.align(Alignment.TopEnd).padding(12.dp), tag = "AFTER")
            if (split > 0.25f) PhotoLabel("BEFORE PROCEDURE", Modifier.align(Alignment.TopStart).padding(12.dp), tag = "BEFORE")
            val handleX = maxWidth * split
            Box(
                Modifier
                    .offset(x = handleX - 1.5.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(RoseGoldPrimary)
            )
            Box(
                Modifier
                    .offset(x = handleX - 16.dp)
                    .align(Alignment.CenterStart)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(RoseGoldPrimary),
                contentAlignment = Alignment.Center
            ) { Text("↔", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
        Slider(value = split, onValueChange = { split = it }, modifier = Modifier.fillMaxWidth())
        Text(
            "Drag across the photo to split Before & After results",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
