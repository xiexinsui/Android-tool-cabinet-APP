package com.decibelmeter.app

import java.util.Locale

/**
 * 共享里的文件按「能不能在应用内预览、用什么预览」分类。
 *
 * 判据只用扩展名，和 [FileUtil.mimeOf] 一样不做 magic number 探测：局域网共享里的文件
 * 名字基本可信，而探测要在列目录时额外读每个文件的前几个字节——为了极少数改错后缀的文件
 * 让整个目录列表慢一倍不值得。真打不开时预览页会明确说「这个格式本机解不了」并给出
 * 「下载后用其他应用打开」的出口。
 */
enum class SmbKind {
    IMAGE, VIDEO, AUDIO, PDF, TEXT, ARCHIVE, OTHER;

    /** 能在应用内看（OTHER 之外都能）。 */
    val previewable: Boolean
        get() = this != OTHER && this != ARCHIVE

    companion object {
        private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
                "avif", "jfif", "dng")
        private val VIDEOS = setOf("mp4", "m4v", "mkv", "webm", "mov", "ts", "flv", "avi", "wmv",
                "3gp", "ogv")
        private val AUDIOS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus",
                "wma", "aiff")
        private val TEXTS = setOf("txt", "log", "md", "markdown", "csv", "tsv", "json", "xml",
                "yaml", "yml", "ini", "conf", "cfg", "rc", "properties", "hosts",
                "c", "h", "cpp", "hpp", "java", "kt", "py", "js", "ts", "sh", "bat", "cmd",
                "sql", "html", "htm", "css", "hex", "srec", "s19", "list", "srt", "ass")

        /** 按扩展名分类；[FileUtil.mimeOf] 负责给外部应用看的 MIME，两者互不替代。 */
        @JvmStatic
        fun of(name: String): SmbKind {
            val dot = name.lastIndexOf('.')
            val ext = (if (dot < 0) "" else name.substring(dot + 1)).lowercase(Locale.US)
            return when {
                ext == "pdf" -> PDF
                ext == "zip" -> ARCHIVE
                ext in IMAGES -> IMAGE
                ext in VIDEOS -> VIDEO
                ext in AUDIOS -> AUDIO
                ext in TEXTS -> TEXT
                else -> OTHER
            }
        }
    }
}
