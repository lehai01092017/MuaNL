package vn.nguyenlieu.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object Photos {
    /** Cạnh dài tối đa của ảnh lưu trong CSDL (đủ nét để xem, nhẹ để gửi). */
    private const val MAX_SIDE = 900

    private val cache = object : LruCache<String, ImageBitmap>(120) {}

    /** Uri tạm để ứng dụng Camera ghi ảnh chụp vào. */
    fun newCameraUri(ctx: Context): Uri {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "capture.jpg")
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    /** Đọc ảnh từ uri, xoay đúng chiều theo EXIF, thu nhỏ, nén JPEG. */
    fun load(ctx: Context, uri: Uri): ByteArray? = try { loadInner(ctx, uri) } catch (e: Exception) { null }

    private fun loadInner(ctx: Context, uri: Uri): ByteArray? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        run {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val rotation = cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val scale = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
            if (rotation != 0f || scale < 1f) {
                val m = Matrix().apply {
                    if (scale < 1f) postScale(scale, scale)
                    if (rotation != 0f) postRotate(rotation)
                }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            return ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray()
            }
        }
    }

    /** Ảnh lấy từ file Excel: thu nhỏ và nén lại như ảnh chụp. Định dạng không đọc được (EMF…) -> null. */
    fun fromBytes(bytes: ByteArray): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) null else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            val scale = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
        }
    } catch (e: Exception) {
        null
    }

    fun decode(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    fun cached(db: Db, itemId: Long, ver: Long): ImageBitmap? {
        val key = "$itemId-$ver"
        cache.get(key)?.let { return it }
        val img = db.photo(itemId)?.let { decode(it) } ?: return null
        cache.put(key, img)
        return img
    }

    fun peek(itemId: Long, ver: Long): ImageBitmap? = cache.get("$itemId-$ver")
}

/** Ảnh thu nhỏ của sản phẩm; chưa có ảnh thì hiện chữ cái đầu. */
@Composable
fun ItemThumb(db: Db, item: Item, size: Dp = 48.dp, onClick: (() -> Unit)? = null) {
    val img by produceState(Photos.peek(item.id, item.photoVer), item.id, item.photoVer) {
        if (item.photoVer != 0L && value == null) {
            value = withContext(Dispatchers.IO) { Photos.cached(db, item.id, item.photoVer) }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    var mod = Modifier.size(size).clip(shape).background(Color(0xFFE8F5E9))
    if (onClick != null) mod = mod.clickable(onClick = onClick)
    Box(mod, contentAlignment = Alignment.Center) {
        val bmp = if (item.photoVer != 0L) img else null
        if (bmp != null) {
            Image(bmp, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(item.name.take(1).uppercase(), color = Green, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
        }
    }
}

/** Xem ảnh lớn. */
@Composable
fun PhotoViewer(image: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Image(
            image, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
                .clip(RoundedCornerShape(12.dp)).clickable(onClick = onDismiss)
        )
    }
}
