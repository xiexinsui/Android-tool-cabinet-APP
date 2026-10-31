package com.decibelmeter.app;

import java.util.Locale;

/**
 * 文件相关的零碎工具：大小显示、MIME 推断、文件名安全化。
 *
 * MIME 表只覆盖「下载到手机上会被别的 App 打开」的那批扩展名。认不出的一律
 * {@code application/octet-stream}：让系统按二进制处理，比给一个错的类型骗对方 App
 * 打开成乱码要好。
 */
final class FileUtil {

    private FileUtil() {
    }

    static String humanSize(long size) {
        if (size < 0) {
            return "—";
        }
        if (size < 1024) {
            return size + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = size;
        int index = -1;
        do {
            value /= 1024;
            index++;
        } while (value >= 1024 && index < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[index]);
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
        switch (ext) {
            case "txt": case "log": case "md": case "csv": return "text/plain";
            case "htm": case "html": return "text/html";
            case "xml": return "text/xml";
            case "json": return "application/json";
            case "ini": case "conf": case "rc": return "text/plain";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "svg": return "image/svg+xml";
            case "mp3": return "audio/mpeg";
            case "m4a": case "aac": return "audio/mp4a-latm";
            case "wav": return "audio/x-wav";
            case "flac": return "audio/flac";
            case "ogg": return "application/ogg";
            case "mp4": case "m4v": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "avi": return "video/x-msvideo";
            case "webm": return "video/webm";
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip": return "application/zip";
            case "rar": return "application/x-rar-compressed";
            case "7z": return "application/x-7z-compressed";
            case "gz": return "application/gzip";
            case "tar": return "application/x-tar";
            case "apk": return "application/vnd.android.package-archive";
            case "hex": case "srec": case "s19": return "text/plain";
            case "bin": case "rom": case "img": case "elf": return "application/octet-stream";
            case "rdp": return "application/rdp";
            default: return "application/octet-stream";
        }
    }

    /**
     * 把共享上的文件名变成能安全落盘的名字：分隔符与控制字符换下划线，首尾点去掉（Windows
     * 不允许以点开头的文件名在部分卷上创建）。长度也裁一刀，免得整条路径超过 255 字节。
     */
    static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            return "file";
        }
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && builder.length() < 120; i++) {
            char c = name.charAt(i);
            boolean bad = c < 0x20 || (c > 0x7E && c < 0xA0);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || bad) {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        String cleaned = builder.toString().replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "file";
        }
        // SMB 列表里的名字可能带尾部斜杠（共享根），落盘时必须去掉
        return cleaned;
    }
}
