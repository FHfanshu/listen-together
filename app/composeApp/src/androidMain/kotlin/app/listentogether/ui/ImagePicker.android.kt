package app.listentogether.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

@Composable
actual fun rememberImagePicker(onImage: (ByteArray) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) squareJpeg(context, uri, 256)?.let(onImage)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** 先按采样率解码避免大图占内存，再居中裁成正方形、缩放并压成 JPEG（通常 20–40KB）。 */
private fun squareJpeg(context: Context, uri: Uri, size: Int): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val side = min(decoded.width, decoded.height)
    val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(cropped, min(size, side), min(size, side), true)
    ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
}.getOrNull()
