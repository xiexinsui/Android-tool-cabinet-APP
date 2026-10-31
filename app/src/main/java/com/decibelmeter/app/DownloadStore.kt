package com.decibelmeter.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 下载落点。
 *
 * 从 Android 10 起往公共「下载」目录写不再需要存储权限（MediaStore），所以默认就落在那儿——
 * `/sdcard/Download/工具箱/`，文件管理器、相册、播放器都能直接看见，卸载应用也不会带走。
 * 之前落在 `Android/data/<包名>/files/Download/`：Android 11 起这个目录对其它应用（包括系统
 * 文件管理器与相册）是隐藏的，用户下完一部电影根本找不到，这是这次改主因。
 *
 * Android 9 及以下没有 MediaStore.Downloads 这条免权限通道（要 WRITE_EXTERNAL_STORAGE），
 * 退回应用私有目录——也就是旧行为：能用，但要靠「用其他应用打开 / 分享」递出去。
 *
 * 收尾用 [commit] / [discard] 而不是 try-with-resources，是因为 MediaStore 要分两步写：
 * 先以 IS_PENDING=1 占位写字节，写完再清标志；否则相册与文件管理器会在传输途中看到半截文件。
 */
abstract class DownloadSink {

    /** 最终文件名（重名时带 (2) 之类后缀，已经避让过）。 */
    abstract val label: String

    /** 给提示文案用的位置描述（「下载/工具箱」或「应用私有目录」）。 */
    abstract val location: String

    /**
     * 重建这个落点用的句柄：公共目录是 content URI，私有目录是 `file:` + 绝对路径。
     *
     * 进程被杀之后任务表就没了，但没写完的文件还在——把它记下来，下次启动能接着下。
     */
    abstract val token: String

    @Throws(IOException::class)
    fun open(): OutputStream = openAt(0)

    /**
     * 从 [offset] 处继续写（断点续传）。[offset] 取 [resumableBytes]，两者始终一致。
     */
    @Throws(IOException::class)
    abstract fun openAt(offset: Long): OutputStream

    /** 现在文件里已经有多少字节＝续传统起点；取不到返回 0。 */
    abstract fun resumableBytes(): Long

    /** 落盘后的字节数，取不到返回 -1。 */
    abstract fun size(): Long

    /** 交给外部应用时用的 content URI。 */
    abstract fun contentUri(context: Context): Uri

    /** 写入成功：清掉 pending 标志，让其它应用看到这个文件。 */
    abstract fun commit()

    /** 失败或取消：把半截文件删掉，别留个 0 字节的空壳。 */
    abstract fun discard()

    /** 把远端的修改时间带回来；MediaStore 那边 DATE_MODIFIED 不可写，只有私有目录这条路有效。 */
    open fun applyModified(time: Long) {}

    companion object {

        /** 公共「下载」目录下的子文件夹名；「已下载」页也按它查。 */
        const val PUBLIC_FOLDER = "工具箱"

        /**
         * 为一个新下载挑落点。名字重了会自动加 (2)、(3)……
         *
         * @throws IOException 建不出文件（空间满、provider 异常、没有可写目录）
         */
        @JvmStatic
        @Throws(IOException::class)
        fun create(context: Context, name: String): DownloadSink {
            val safe = FileUtil.sanitize(name)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createPublic(context, safe)
            } else {
                createPrivate(context, safe)
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun createPublic(context: Context, name: String): DownloadSink {
            val resolver = context.contentResolver
            val relative = Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_FOLDER
            val unique = uniqueName(resolver, relative, name)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
                put(MediaStore.MediaColumns.MIME_TYPE, FileUtil.mimeOf(unique))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException(context.getString(R.string.smb_no_download_dir))
            // MediaStore 会按 MIME 给名字补扩展名（.ini 映射成 text/plain，于是变成 .txt），
            // 它回写的 DISPLAY_NAME 才是磁盘上真正的名字，提示与打开都得用它
            val actual = displayNameOf(resolver, uri) ?: unique
            return PublicSink(context, uri, actual,
                    context.getString(R.string.smb_location_public, PUBLIC_FOLDER))
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? {
            return try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 公共目录里重名时自己编号，规则与旧实现一致（`照片.jpg` → `照片 (2).jpg`）。
         *
         * MediaStore 自己也会避让重名，但它给出的名字我们事先不知道，提示文案就写不准；
         * 各厂商的编号规则也不一样。先查一遍现有名字最稳。查不到（个别 provider 不支持
         * RELATIVE_PATH 过滤）就退回原名，让 MediaStore 自己去避让。
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        private fun uniqueName(resolver: ContentResolver, relative: String, name: String): String {
            val taken = HashSet<String>()
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?"
            try {
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection,
                        arrayOf("$relative/"), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { taken.add(it) }
                    }
                }
            } catch (e: Exception) {
                return name
            }
            if (name !in taken) {
                return name
            }
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            for (i in 2..999) {
                val candidate = "$base ($i)$ext"
                if (candidate !in taken) {
                    return candidate
                }
            }
            return "$base-${System.currentTimeMillis()}$ext"
        }

        private fun createPrivate(context: Context, name: String): DownloadSink {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IOException(context.getString(R.string.smb_no_download_dir))
            var candidate = File(dir, name)
            if (candidate.exists()) {
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                for (i in 2..999) {
                    candidate = File(dir, "$base ($i)$ext")
                    if (!candidate.exists()) {
                        break
                    }
                }
            }
            return PrivateSink(candidate, context.getString(R.string.smb_location_private))
        }

        /**
         * 按 [token] 认领一个上次没写完的落点，认不出来返回 null。
         *
         * 公共目录这条必须实查一次：用户可能在两次启动之间把它删了，拿着一条失效 URI
         * 去续写只会在最后一步报一个看不懂的错。必须在工作线程调用。
         */
        fun adopt(context: Context, token: String, label: String): DownloadSink? {
            if (token.startsWith("content:")) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return null
                }
                val uri = Uri.parse(token)
                val alive = try {
                    context.contentResolver.query(uri,
                            arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                            ?.use { it.moveToFirst() } ?: false
                } catch (e: Exception) {
                    false
                }
                if (!alive) {
                    return null
                }
                return PublicSink(context, uri, label,
                        context.getString(R.string.smb_location_public, PUBLIC_FOLDER))
            }
            if (token.startsWith("file:")) {
                val file = File(token.removePrefix("file:"))
                return PrivateSink(file, context.getString(R.string.smb_location_private))
            }
            return null
        }
    }

    /** 公共「下载」目录里的文件（API 29+）。 */
    private class PublicSink(
            private val context: Context,
            private val uri: Uri,
            override val label: String,
            override val location: String,
    ) : DownloadSink() {

        override val token: String get() = uri.toString()

        override fun openAt(offset: Long): OutputStream {
            val resolver = context.contentResolver
            if (offset <= 0) {
                return resolver.openOutputStream(uri)
                        ?: throw IOException("无法写入下载目录")
            }
            // 续传：以读写方式打开同一个 pending 项，把通道位置挪到已下载处。
            //
            // pfd 必须跟着流一起活着：`FileOutputStream(pfd.fileDescriptor)` 不持有 pfd 本身，
            // GC 一收掉它就会关掉底层 fd，写到一半报「write failed: EBADF」
            // （真机上下到 1 GB 时就是这么断的）。
            val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException("无法续写下载文件")
            val out = FileOutputStream(pfd.fileDescriptor)
            out.channel.position(offset)
            return PfdStream(pfd, out)
        }

        override fun resumableBytes(): Long = size().coerceAtLeast(0)

        override fun size(): Long {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE),
                        null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        return cursor.getLong(0)
                    }
                }
            } catch (e: Exception) {
                // 问不到大小就当未知，提示里不显示字节数而已
            }
            return -1
        }

        override fun contentUri(context: Context): Uri = uri

        override fun commit() {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            runCatching { context.contentResolver.update(uri, values, null, null) }
        }

        override fun discard() {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /** 应用私有目录里的文件（API 24~28，或公共目录建不出来时的退路）。 */
    private class PrivateSink(
            private val file: File,
            override val location: String,
    ) : DownloadSink() {

        override val label: String get() = file.name

        override val token: String get() = "file:" + file.absolutePath

        override fun openAt(offset: Long): OutputStream =
                FileOutputStream(file, offset > 0)

        override fun resumableBytes(): Long = if (file.isFile) file.length() else 0

        override fun size(): Long = file.length()

        override fun contentUri(context: Context): Uri = LocalFileProvider.uriFor(context, file)

        override fun commit() {}

        override fun discard() {
            if (file.exists()) {
                file.delete()
            }
        }

        override fun applyModified(time: Long) {
            if (time > 0) {
                file.setLastModified(time)
            }
        }
    }
}

/**
 * 替 [ParcelFileDescriptor] 续命的输出流。
 *
 * `FileOutputStream(pfd.fileDescriptor)` 只拿到一个裸 fd，并不持有 pfd；一旦 pfd 被 GC
 * 回收，它的 finalize 会关掉那个 fd，正在写的流就在半路报 EBADF。这里把 pfd 变成流的成员，
 * 活到 close 为止。
 */
private class PfdStream(
        private val pfd: ParcelFileDescriptor,
        private val out: FileOutputStream,
) : OutputStream() {

    override fun write(byte: Int) = out.write(byte)

    override fun write(bytes: ByteArray) = out.write(bytes)

    override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)

    override fun flush() = out.flush()

    override fun close() {
        runCatching { out.flush() }
        runCatching { out.close() }
        runCatching { pfd.close() }
    }
}
