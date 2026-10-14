package io.github.zhuquan7237.musechat.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.zhuquan7237.musechat.data.ImageEntity
import java.io.File

fun imageMime(path: String) = when (File(path).extension) { "jpg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }
fun shareImage(context: Context, image: ImageEntity) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(image.path))
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = imageMime(image.path); putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "分享图片"))
}
fun exportImage(context: Context, image: ImageEntity) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "MuseChat_" + File(image.path).name)
        put(MediaStore.Images.Media.MIME_TYPE, imageMime(image.path))
        if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MuseChat"); put(MediaStore.Images.Media.IS_PENDING, 1) }
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册文件")
    try {
        context.contentResolver.openOutputStream(uri)?.use { output -> File(image.path).inputStream().use { it.copyTo(output) } } ?: error("相册写入失败")
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
}
