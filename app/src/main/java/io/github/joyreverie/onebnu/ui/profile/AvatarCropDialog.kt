package io.github.joyreverie.onebnu.ui.profile

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AvatarCropDialog(
    source: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(source) { mutableStateOf(IntSize.Zero) }

    fun updateZoom(value: Float) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = clampOffset(offset, source, viewportSize, zoom)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "取消")
                    }
                    Text(
                        "设置头像",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            cropAvatar(source, viewportSize, zoom, offset)?.let(onConfirm)
                        },
                    ) {
                        Text("完成")
                    }
                }

                Text(
                    "拖动调整位置，双指缩放图片",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CropViewport(
                        bitmap = source,
                        zoom = zoom,
                        offset = offset,
                        onTransform = { pan, gestureZoom ->
                            val nextZoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            zoom = nextZoom
                            offset = clampOffset(offset + pan, source, viewportSize, nextZoom)
                        },
                        onSizeChanged = { viewportSize = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(maxWidth = 380.dp)
                            .aspectRatio(1f)
                            .padding(horizontal = 24.dp),
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("缩放", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = zoom,
                        onValueChange = ::updateZoom,
                        valueRange = MIN_ZOOM..MAX_ZOOM,
                    )
                }
            }
        }
    }
}

@Composable
private fun CropViewport(
    bitmap: Bitmap,
    zoom: Float,
    offset: Offset,
    onTransform: (Offset, Float) -> Unit,
    onSizeChanged: (IntSize) -> Unit,
    modifier: Modifier,
) {
    val scrim = MaterialTheme.colorScheme.scrim

    Canvas(
        modifier = modifier
            .onSizeChanged(onSizeChanged)
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    onTransform(pan, gestureZoom)
                }
            },
    ) {
        val cropDiameter = cropDiameter(size)
        val totalScale = imageScale(bitmap, cropDiameter, zoom)
        val imageWidth = bitmap.width * totalScale
        val imageHeight = bitmap.height * totalScale
        val center = Offset(size.width / 2f, size.height / 2f)
        val imageLeft = center.x + offset.x - imageWidth / 2f
        val imageTop = center.y + offset.y - imageHeight / 2f

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(
                bitmap,
                null,
                RectF(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }

        val cropBounds = Rect(
            center.x - cropDiameter / 2f,
            center.y - cropDiameter / 2f,
            center.x + cropDiameter / 2f,
            center.y + cropDiameter / 2f,
        )
        val overlay = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addOval(cropBounds)
        }
        drawPath(overlay, scrim.copy(alpha = 0.64f))
        drawCircle(
            color = Color.White,
            radius = cropDiameter / 2f,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun clampOffset(
    offset: Offset,
    bitmap: Bitmap,
    viewportSize: IntSize,
    zoom: Float,
): Offset {
    if (viewportSize.width <= 0 || viewportSize.height <= 0) return offset
    val cropDiameter = cropDiameter(viewportSize)
    val totalScale = imageScale(bitmap, cropDiameter, zoom)
    val maxX = max(0f, (bitmap.width * totalScale - cropDiameter) / 2f)
    val maxY = max(0f, (bitmap.height * totalScale - cropDiameter) / 2f)
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY),
    )
}

private fun cropAvatar(
    bitmap: Bitmap,
    viewportSize: IntSize,
    zoom: Float,
    offset: Offset,
): Bitmap? {
    if (viewportSize.width <= 0 || viewportSize.height <= 0) return null

    val cropDiameter = cropDiameter(viewportSize)
    val totalScale = imageScale(bitmap, cropDiameter, zoom)
    val centerX = viewportSize.width / 2f
    val centerY = viewportSize.height / 2f
    val imageLeft = centerX + offset.x - bitmap.width * totalScale / 2f
    val imageTop = centerY + offset.y - bitmap.height * totalScale / 2f
    val sourceSize = floor(cropDiameter / totalScale).toInt().coerceAtLeast(1)
    val sourceLeft = ((centerX - cropDiameter / 2f - imageLeft) / totalScale)
        .roundToInt()
        .coerceIn(0, bitmap.width - 1)
    val sourceTop = ((centerY - cropDiameter / 2f - imageTop) / totalScale)
        .roundToInt()
        .coerceIn(0, bitmap.height - 1)
    val actualSize = sourceSize
        .coerceAtMost(bitmap.width - sourceLeft)
        .coerceAtMost(bitmap.height - sourceTop)
        .coerceAtLeast(1)
    val square = Bitmap.createBitmap(bitmap, sourceLeft, sourceTop, actualSize, actualSize)
    val resultSize = 512
    val result = Bitmap.createBitmap(resultSize, resultSize, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawCircle(resultSize / 2f, resultSize / 2f, resultSize / 2f, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(square, null, RectF(0f, 0f, resultSize.toFloat(), resultSize.toFloat()), paint)
    paint.xfermode = null
    if (square !== bitmap) square.recycle()
    return result
}

private fun cropDiameter(size: IntSize): Float = cropDiameter(
    min(size.width, size.height).toFloat(),
)

private fun cropDiameter(size: androidx.compose.ui.geometry.Size): Float = cropDiameter(
    min(size.width, size.height),
)

private fun cropDiameter(minDimension: Float): Float = minDimension * 0.78f

private fun imageScale(bitmap: Bitmap, cropDiameter: Float, zoom: Float): Float =
    max(cropDiameter / bitmap.width, cropDiameter / bitmap.height) * zoom

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
