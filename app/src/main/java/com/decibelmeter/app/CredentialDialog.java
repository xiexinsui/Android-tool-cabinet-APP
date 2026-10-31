package com.decibelmeter.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 局域网登录账号对话框：用户名、域、密码、是否记住，一处实现，扫描页与远程桌面共用。
 *
 * 提交时立刻写进 {@link CredentialStore}：勾了「记住密码」才加密落盘，不勾只留在进程内存里，
 * 页面之间靠这份内存传递，绝不通过 Intent 携带明文密码。
 *
 * 加密写盘失败（Keystore 被重置、部分机型不可用）只提示不阻断：凭据已经在内存里，本次连接
 * 该能成，用户不必因为存不下来而被拦住。
 */
final class CredentialDialog {

    /** 用户点了「连接/保存」之后的事：凭据已经存好，回调只管往下走。 */
    interface Callback {
        void done(CredentialStore.Credential credential);
    }

    private CredentialDialog() {
    }

    static void show(final Activity activity, final CredentialStore store, final String hostId,
                     final String title, final int buttonRes, final Callback callback) {
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_lan_credential, null);
        final EditText etUser = (EditText) content.findViewById(R.id.et_user);
        final EditText etDomain = (EditText) content.findViewById(R.id.et_domain);
        final EditText etPassword = (EditText) content.findViewById(R.id.et_password);
        final CheckBox cbRemember = (CheckBox) content.findViewById(R.id.cb_remember);

        CredentialStore.Credential saved = store.get(hostId);
        if (saved != null) {
            etUser.setText(saved.user);
            etDomain.setText(saved.domain);
            etPassword.setText(saved.password);
            cbRemember.setChecked(store.isStored(hostId));
        }

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setPositiveButton(buttonRes, (dialog, which) -> {
                    CredentialStore.Credential credential = new CredentialStore.Credential(
                            etUser.getText().toString().trim(),
                            etDomain.getText().toString().trim(),
                            etPassword.getText().toString());
                    storeQuietly(activity, store, hostId, credential, cbRemember.isChecked());
                    if (callback != null) {
                        callback.done(credential);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void storeQuietly(Activity activity, CredentialStore store, String hostId,
                                     CredentialStore.Credential credential, boolean remember) {
        try {
            store.store(hostId, credential, remember);
        } catch (GeneralSecurityException | IOException e) {
            Toast.makeText(activity,
                    activity.getString(R.string.lan_cred_encrypt_failed, e.getMessage() == null
                            ? e.toString() : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }
}
