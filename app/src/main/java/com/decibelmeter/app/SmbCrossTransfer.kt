package com.decibelmeter.app

import java.io.IOException

/**
 * 两台机器之间的复制/移动。
 *
 * 同一台机器上的搬运有服务端通道（FSCTL_SRV_COPYCHUNK，字节不出服务器），但那扇门在两台
 * 机器之间不存在——只能把字节读到手机再写到对面，本质是一次「过手的」中转。所以这里：
 *  * 一个文件一条随机读句柄，边读边写给对面的上传通道；
 *  * 目录递归，先建结构再搬文件，中途失败已经搬好的留着（重名避让，不会盖掉任何已有文件）；
 *  * **只有整棵子树都搬完了才删源**：移动半途取消或报错时源完好无损。
 *
 * 全程跑在 [TransferHub] 的工作线程上，进度是整棵树的字节数（先量一次，元数据往返而已）。
 */
object SmbCrossTransfer {

    /** @return 搬过去的文件数 */
    @Throws(IOException::class)
    fun run(source: SmbLink, target: SmbLink, sourcePath: String, targetDir: String,
            move: Boolean, progress: SmbClient.Progress): Int {
        val directory = sourcePath.endsWith("/")
        val name = sourcePath.trimEnd('/').substringAfterLast('/')
        val total = bytes(source, sourcePath)
        val copied = if (directory) {
            val into = targetDir + name + "/"
            target.client.mkdirs(into)
            copyTree(source, target, sourcePath, into, total, progress)
        } else {
            copyOne(source, target, sourcePath, targetDir, name, total, progress)
            1
        }
        if (move) {
            source.client.delete(sourcePath)
        }
        return copied
    }

    /** 递归搬一个目录，返回搬了多少个文件。 */
    private fun copyTree(source: SmbLink, target: SmbLink, path: String, into: String,
                         total: Long, progress: SmbClient.Progress): Int {
        var done = 0
        for (item in source.client.list(path, true).items) {
            if (progress.isCancelled()) {
                throw IOException("已取消")
            }
            val child = SmbClient.child(path, item.name, item.directory)
            if (item.directory) {
                val sub = into + item.name + "/"
                runCatching { target.client.mkdirs(sub) }
                done += copyTree(source, target, child, sub, total, progress)
            } else {
                copyOne(source, target, child, into, item.name, total, progress)
                done++
            }
        }
        return done
    }

    /**
     * 搬一个文件。
     *
     * 进度是整棵树共享的一条：[total] 传的是总量，[Forward] 里累加的是「到目前为止搬了多少」，
     * 所以几百个文件的小传输也不会每条都从 0% 跳到 100%。
     */
    private fun copyOne(source: SmbLink, target: SmbLink, path: String, into: String,
                        name: String, total: Long, progress: SmbClient.Progress) {
        val range = source.openRange(path)
        try {
            val size = if (range.size >= 0) range.size else source.client.size(path)
            val forward = Forward(progress, total)
            val input = SmbRangeStream(range, 0, if (size < 0) Long.MAX_VALUE else size)
            target.client.writeFile(into, FileUtil.sanitize(name), input, size, forward)
            forward.finish(size)
        } finally {
            runCatching { range.close() }
        }
    }

    /** 一个进度转发器：把「本文件已搬」累成「整棵树已搬」。 */
    private class Forward(private val sink: SmbClient.Progress, private val total: Long) :
            SmbClient.Progress {
        private var carried = 0L
        private var done = 0L

        override fun isCancelled(): Boolean = sink.isCancelled()

        override fun onProgress(read: Long, sum: Long) {
            done = maxOf(done, read)
            sink.onProgress(carried + done, total)
        }

        /** 这个文件搬完了：把这格的量并入总量。 */
        fun finish(size: Long) {
            if (size > 0) {
                carried += size
            }
            done = 0
            sink.onProgress(carried, total)
        }
    }

    /** 整棵子树的字节数；取不到就是 -1（界面显示未知，不影响搬运）。 */
    private fun bytes(link: SmbLink, path: String): Long {
        return try {
            if (!path.endsWith("/")) {
                val size = link.client.size(path)
                return if (size > 0) size else -1L
            }
            var sum = 0L
            var known = false
            val stack = ArrayDeque<String>()
            stack.add(path)
            // 显式栈而不是递归：共享里几十层深嵌套是存在的，递归把每一层的列表都压在栈上
            while (stack.isNotEmpty()) {
                val current = stack.removeFirst()
                for (item in link.client.list(current, true).items) {
                    if (item.directory) {
                        stack.add(SmbClient.child(current, item.name, true))
                    } else if (item.size > 0) {
                        sum += item.size
                        known = true
                    }
                }
            }
            if (known) sum else -1L
        } catch (e: Exception) {
            -1L
        }
    }
}
