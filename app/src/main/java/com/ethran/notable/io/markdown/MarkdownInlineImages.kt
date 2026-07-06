package com.ethran.notable.io.markdown

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.ethran.notable.io.vault.VaultImageResolver
import java.io.File

private data class ResolvedImage(val file: File?, val widthPx: Float, val heightPx: Float)

/** Builds inline text content entries for vault markdown images. */
@Composable
fun rememberMarkdownInlineImages(
    images: List<MarkdownImage>,
    vaultRoot: File?,
    noteRelativePath: String,
    horizontalPadding: Dp = 40.dp,
): Map<String, InlineTextContent> {
    if (vaultRoot == null || images.isEmpty()) return emptyMap()

    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val maxWidthPx = with(density) { (screenWidthDp - horizontalPadding).toPx() }

    val resolved = remember(images, vaultRoot, noteRelativePath, maxWidthPx) {
        images.associate { image ->
            val file = VaultImageResolver.resolve(vaultRoot, noteRelativePath, image.destination)
            val (widthPx, heightPx) = if (file != null) {
                scaledImageSize(file, image.widthHintPx, maxWidthPx)
            } else {
                maxWidthPx * 0.6f to 96f
            }
            image.inlineId to ResolvedImage(file, widthPx, heightPx)
        }
    }

    val content = mutableMapOf<String, InlineTextContent>()
    for (image in images) {
        val info = resolved[image.inlineId] ?: ResolvedImage(null, maxWidthPx * 0.6f, 96f)
        val widthSp = with(density) { info.widthPx.toSp() }
        val heightSp = with(density) { info.heightPx.toSp() }
        val widthDp = with(density) { info.widthPx.toDp() }
        val heightDp = with(density) { info.heightPx.toDp() }

        content[image.inlineId] = InlineTextContent(
            Placeholder(
                width = widthSp,
                height = heightSp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Bottom
            )
        ) {
            val file = info.file
            if (file != null) {
                Image(
                    painter = rememberAsyncImagePainter(file),
                    contentDescription = image.destination,
                    modifier = Modifier
                        .width(widthDp)
                        .height(heightDp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "🖼 ${image.destination}",
                    fontSize = 14.sp,
                    color = Color(0xFF555555)
                )
            }
        }
    }
    return content
}

private fun scaledImageSize(file: File, widthHintPx: Int?, maxWidthPx: Float): Pair<Float, Float> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    var width = opts.outWidth.toFloat()
    var height = opts.outHeight.toFloat()
    if (width <= 0f || height <= 0f) return maxWidthPx * 0.6f to 96f

    val targetWidth = when {
        widthHintPx != null && widthHintPx > 0 ->
            widthHintPx.toFloat().coerceAtMost(maxWidthPx)
        width > maxWidthPx -> maxWidthPx
        else -> width
    }
    if (width > targetWidth) {
        val scale = targetWidth / width
        width = targetWidth
        height *= scale
    }
    return width to height
}
