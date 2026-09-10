package io.github.joyreverie.onebnu.core.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 把应用内置的图片存进系统相册。
 *
 * 直接拷贝资源文件的原始字节，不经过 Bitmap 解码再压缩，画质与内嵌的原图一致。
 * Android 10 起走 MediaStore 不需要任何权限；Android 9 及以下写公共目录需要
 * WRITE_EXTERNAL_STORAGE（清单里以 maxSdkVersion=28 限定只在这些系统上申请）。
 */
object ImageSaver {

    const val PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

    private const val ALBUM = "One BNU"

    /** 是否还需要先向用户申请存储权限（仅 Android 9 及以下可能为 true）。 */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

    /**
     * 把 JPEG 资源 [res] 存到相册的 Pictures/One BNU 下，文件名为「[baseName]_时间戳.jpg」。
     * 成功返回相册中的展示路径，便于提示用户去哪里找。
     */
    fun saveJpegResource(context: Context, @DrawableRes res: Int, baseName: String): Result<String> = runCatching {
        val bytes = context.resources.openRawResource(res).use { it.readBytes() }
        val fileName = "${baseName}_${LocalDateTime.now().format(STAMP)}.jpg"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
                if (!dir.exists() && !dir.mkdirs()) error("无法创建相册目录")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, fileName).absolutePath)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("系统相册拒绝写入")
        try {
            (resolver.openOutputStream(uri) ?: error("无法写入相册")).use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            // 写失败就把半成品条目删掉，不给相册留一张打不开的图
            resolver.delete(uri, null, null)
            throw e
        }
        "${Environment.DIRECTORY_PICTURES}/$ALBUM/$fileName"
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
}
