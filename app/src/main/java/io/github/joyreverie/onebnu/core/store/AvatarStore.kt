package io.github.joyreverie.onebnu.core.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** 本地头像存储：只保留裁剪后的圆形 PNG，不申请任何存储权限。 */
class AvatarStore(private val context: Context) {

    fun load(profileId: String): Bitmap? = runCatching {
        fileFor(profileId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

    fun save(profileId: String, bitmap: Bitmap) {
        val target = fileFor(profileId)
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存头像" }
            }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "无法替换头像文件" }
        } finally {
            temp.delete()
        }
    }

    fun decode(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val orientation = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }

        return if (!matrix.isIdentity) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
    }

    private fun fileFor(profileId: String): File = File(
        context.filesDir,
        "avatar_${profileId.sha256()}.png",
    )

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_SOURCE_SIZE || height / sample > MAX_SOURCE_SIZE) {
            sample *= 2
        }
        return sample
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_SOURCE_SIZE = 2048
    }
}
