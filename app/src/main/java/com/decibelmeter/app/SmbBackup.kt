package com.decibelmeter.app

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 把手机相册增量备份到共享目录。
 *
 * 定位要说清楚：这不是同步，是「单向补档」——只把共享上还没有的照片/视频推上去，绝不改、
 * 绝不删远端已有的东西。手机存储满了想清照片之前先跑一次，跑完看到「已上传 N 项」就敢删了。
 *
 * 增量的判据是**文件名**：MediaStore 里的 `IMG_20240101_120000.jpg` 这类名字由相机生成、不会
 * 变，同名即同一张。用大小当判据反而不行——上传中途断掉会留下一个字节数不对的半截文件，
 * 下次再按大小判定「已经传过」就永远补不上了；按名字判定则会重传一份 ` (2)`，多占点空间但
 * 数据是完整的。
 */
object SmbBackup {

    /** 一条待备份的手机媒体。 */
    class Photo(val uri: Uri, val name: String, val size: Long)

    /** 一次备份的结果，够拼出一句人话。 */
    data class Summary(val uploaded: Int, val skipped: Int, val failed: Int)

    fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

    fun hasPermission(context: Context): Boolean = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 手机里的照片和视频，按拍摄时间从旧到新。
     *
     * 旧的先传：中途断网或被取消时，先落地的是最早的那批，用户接着跑第二次的观感更顺。
     * 回收站里的（`IS_TRASHED`）不参与备份。
     */
    fun media(context: Context): List<Photo> {
        val result = ArrayList<Photo>()
        result.addAll(query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        result.addAll(query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
        return result
    }

    private fun query(context: Context, collection: Uri): List<Photo> {
        val projection = arrayOf(MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.IS_TRASHED + "=0 AND " +
                    MediaStore.MediaColumns.IS_PENDING + "=0"
        } else {
            null
        }
        val result = ArrayList<Photo>()
        try {
            context.contentResolver.query(collection, projection, selection, null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " ASC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val size = cursor.getLong(sizeColumn)
                    if (size <= 0) {
                        // 0 字节的条目要么是损坏要么是云占位，传过去只会得到一份空文件
                        continue
                    }
                    result.add(Photo(ContentUris.withAppendedId(collection,
                            cursor.getLong(idColumn)),
                            FileUtil.sanitize(name), size))
                }
            }
        } catch (e: Exception) {
            // 某个卷查不到（比如没插 SD 卡）不算失败，能备份的部分照备
        }
        return result
    }

    /**
     * 跑一次备份。在 [TransferHub] 的工作线程里执行。
     *
     * 只读远端一次目录（一次 RPC），之后逐项比对；传完之后不刷新列表，交给调用方决定。
     */
    @Throws(Exception::class)
    fun backup(context: Context, client: SmbClient, dir: String, photos: List<Photo>,
               progress: SmbClient.Progress): Summary {
        val existing = HashSet<String>()
        for (item in client.list(dir, true).items) {
            if (!item.directory) {
                existing.add(item.name)
            }
        }
        val todo = photos.filter { it.name !in existing }
        val total = todo.sumOf { it.size }
        var done = 0L
        var uploaded = 0
        var failed = 0
        for (photo in todo) {
            if (progress.isCancelled()) {
                break
            }
            val base = done
            val forward = object : SmbClient.Progress {
                override fun isCancelled(): Boolean = progress.isCancelled()

                override fun onProgress(read: Long, sum: Long) {
                    // 单项进度换算成整批进度：面板上那条进度条要跨几千个文件继续往前走
                    progress.onProgress(base + read, total)
                }
            }
            try {
                // 名字与大小在列 MediaStore 那一趟就已经拿到了，直接递给上传：几千个文件省下的
                // 是上万次 ContentResolver 查询，而一次查询比传完一张小照片还慢
                client.upload(context, photo.uri, photo.name, photo.size, dir, forward)
                uploaded++
            } catch (e: Exception) {
                if (progress.isCancelled()) {
                    break
                }
                failed++
            }
            // 失败也把这格的字节数记上：否则一条坏文件会让进度条永远停在同一个位置
            done = base + photo.size
        }
        return Summary(uploaded, photos.size - todo.size, failed)
    }
}
