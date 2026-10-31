package com.decibelmeter.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读文件共享出口：把应用私有目录里的文件交给别的 App（「用其他应用打开」「分享这个 .rdp」）。
 *
 * 安卓没有可以直接 new 的系统级 FileProvider，本工程又不引 AndroidX（见 gradle.properties 的
 * useAndroidX=false），所以自己实现一个最小的：URI 形如 {@code content://<包名>.files/相对路径}，
 * 相对路径以 {@code external/} 开头时落在 getExternalFilesDir，其余落在 getFilesDir。只支持
 * openFile 与 getType，不接受查询、写入与删除——这个出口存在的唯一理由是把一个已知的私有文件
 * 以只读方式交给对方进程。
 *
 * 路径解析拒绝越出这两根的写法（{@code ..}、绝对路径），否则「相对路径」就成了任意文件读取口。
 * 权限靠 Intent.FLAG_GRANT_READ_URI_PERMISSION 单次授予，provider 本身 exported=false。
 */
public final class LocalFileProvider extends ContentProvider {

    private static final String EXTERNAL_PREFIX = "external/";

    public static String authority(Context context) {
        // 清单里写的是 ${applicationId}.files，两者必须一致
        return context.getPackageName() + ".files";
    }

    /** 反推一个私有文件对应的 content URI，调用方给的文件必须在这两个根下面。 */
    public static Uri uriFor(Context context, File file) {
        String path = file.getAbsolutePath();
        String extRoot = context.getExternalFilesDir(null) == null
                ? null : context.getExternalFilesDir(null).getAbsolutePath();
        String internalRoot = context.getFilesDir().getAbsolutePath();
        if (extRoot != null && path.startsWith(extRoot + File.separator)) {
            return build(context, EXTERNAL_PREFIX + path.substring(extRoot.length() + 1));
        }
        if (path.startsWith(internalRoot + File.separator)) {
            return build(context, path.substring(internalRoot.length() + 1));
        }
        throw new IllegalArgumentException("文件不在应用私有目录里: " + path);
    }

    private static Uri build(Context context, String relative) {
        Uri.Builder builder = new Uri.Builder().scheme("content")
                .authority(authority(context));
        for (String segment : relative.split("/")) {
            if (!segment.isEmpty()) {
                builder.appendPath(segment);
            }
        }
        return builder.build();
    }

    /** 把 URI 还原成私有目录里的文件；不接受任何越界的写法。 */
    private File resolve(Uri uri) {
        String relative = uri.getPath();
        if (relative == null) {
            return null;
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        // getPath 已经把 %XX 解回来，所以 `..` 只要按字面判断就能挡住上跳
        if (relative.isEmpty() || relative.contains("..") || relative.startsWith("/")) {
            return null;
        }
        File root;
        if (relative.startsWith(EXTERNAL_PREFIX)) {
            root = getContext().getExternalFilesDir(null);
            relative = relative.substring(EXTERNAL_PREFIX.length());
            if (root == null) {
                return null;
            }
        } else {
            root = getContext().getFilesDir();
        }
        File file = new File(root, relative);
        // 双保险：解析后的绝对路径必须仍在根下面（符号链接、冗余斜杠都可能绕过字符串检查）
        try {
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return file.isFile() && file.canRead() ? file : null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null) {
            throw new FileNotFoundException("找不到对应的应用文件");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File file = resolve(uri);
        return file == null ? null : FileUtil.mimeOf(file.getName());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        throw new UnsupportedOperationException("这个 provider 只提供文件内容");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("这个 provider 只提供文件内容");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("这个 provider 只提供文件内容");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("这个 provider 只提供文件内容");
    }
}
