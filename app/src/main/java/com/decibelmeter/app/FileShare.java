package com.decibelmeter.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import java.io.File;

/**
 * 把应用私有目录里的文件交给别的 App：下载到手机的文件要能「用其他应用打开」，导出的 .rdp
 * 要能分享到电脑上。
 *
 * 走 {@link LocalFileProvider} 的 content URI 而不是 {@code file://}：从 API 24 起，跨进程传
 * {@code file://} 会直接抛 FileUriExposedException，而这个工程不引 AndroidX 的 FileProvider。
 * 授权只随这一次 Intent 走（FLAG_GRANT_READ_URI_PERMISSION），对方进程读完即失效。
 */
final class FileShare {

    static final int SENT = 0;
    static final int NO_APP = 1;

    private FileShare() {
    }

    /** 用别的 App 打开（VIEW）。 */
    static int openWith(Activity activity, File file) {
        return dispatch(activity, LocalFileProvider.uriFor(activity, file),
                FileUtil.mimeOf(file.getName()), Intent.ACTION_VIEW, R.string.share_chooser_open);
    }

    /** 分享出去（SEND）：存到别处、传到电脑。 */
    static int sendTo(Activity activity, File file) {
        return dispatch(activity, LocalFileProvider.uriFor(activity, file),
                FileUtil.mimeOf(file.getName()), Intent.ACTION_SEND, R.string.share_chooser_send);
    }

    /**
     * 直接打开一个 content URI（MediaStore 里的下载项本身就是可读 URI，不必再过 LocalFileProvider）。
     */
    static int openUri(Activity activity, Uri uri, String mime) {
        return dispatch(activity, uri, mime, Intent.ACTION_VIEW, R.string.share_chooser_open);
    }

    static int sendUri(Activity activity, Uri uri, String mime) {
        return dispatch(activity, uri, mime, Intent.ACTION_SEND, R.string.share_chooser_send);
    }

    private static int dispatch(Activity activity, Uri uri, String mime, String action, int titleRes) {
        Intent intent = new Intent(action);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            // 只有一个候选应用时系统选择框也还是有用的：用户可能想选「另存到文件管理」而不是直接打开
            activity.startActivity(Intent.createChooser(intent,
                    activity.getString(titleRes)));
            return SENT;
        } catch (ActivityNotFoundException e) {
            return NO_APP;
        }
    }
}
