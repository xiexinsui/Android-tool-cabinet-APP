package com.decibelmeter.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 局域网登录凭据：用 Android Keystore 里的 AES-256/GCM 密钥加密后写 SharedPreferences。
 *
 * 密钥不可导出，只在 TEE/StrongBox 内参与运算，落盘的只有 12 字节 IV 加 GCM 密文与认证
 * 标签，因此导出或翻出 SharedPreferences 也拿不到明文密码；GCM 标签同时保证密文没被改过。
 * 未勾选「记住密码」的凭据只留在进程内存里（页面之间靠它传递，不走 Intent，避免明文出现
 * 在系统日志里），进程结束即消失。
 *
 * 工程开了 allowBackup，SharedPreferences 会随备份走、Keystore 密钥不会，所以换机恢复后
 * 旧条目必然解不开——这按「没存过」处理，用户重新登录一次即可。
 */
public final class CredentialStore {

    public static final class Credential {
        public final String user;
        public final String domain;
        public final String password;

        public Credential(String user, String domain, String password) {
            this.user = user == null ? "" : user;
            this.domain = domain == null ? "" : domain;
            this.password = password == null ? "" : password;
        }

        /** 用户名与密码都留空＝匿名（来宾）访问。 */
        public boolean isAnonymous() {
            return user.isEmpty() && password.isEmpty();
        }
    }

    private static final String PREFS = "lan_credentials";
    private static final String KEY_ALIAS = "lan-credentials-aes";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    /** 三个字段用不可打印的 (char) 1 连接，可打印字符因此都能出现在密码里。 */
    private static final char SEPARATOR = (char) 1;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final Map<String, Credential> SESSION = new ConcurrentHashMap<>();

    private final SharedPreferences prefs;

    public CredentialStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 先取本次会话内的凭据，再取 Keystore 密文；都没有返回 null。 */
    public Credential get(String hostId) {
        Credential session = SESSION.get(hostId);
        return session != null ? session : read(hostId);
    }

    public void store(String hostId, Credential credential, boolean remember)
            throws GeneralSecurityException, IOException {
        SESSION.put(hostId, credential);
        if (remember) {
            prefs.edit().putString(hostId, encrypt(serialize(credential))).apply();
        }
    }

    /** 是否持久保存过：列表长按时据此决定要不要给「删除已存凭据」。 */
    public boolean isStored(String hostId) {
        return prefs.contains(hostId);
    }

    public void forget(String hostId) {
        SESSION.remove(hostId);
        prefs.edit().remove(hostId).apply();
    }

    /**
     * 设备换 IP 时把凭据跟过去。新地址上已经有另一份凭据时不覆盖——那可能是旧住户留下的，
     * 覆盖会把它悄悄带到另一次登录里。
     */
    public void rename(String oldHost, String newHost) {
        if (oldHost == null || newHost == null || oldHost.equals(newHost)
                || get(newHost) != null) {
            return;
        }
        Credential stored = read(oldHost);
        Credential session = SESSION.remove(oldHost);
        Credential credential = stored != null ? stored : session;
        if (credential == null) {
            return;
        }
        SESSION.put(newHost, credential);
        if (stored != null) {
            try {
                prefs.edit().putString(newHost, encrypt(serialize(credential))).apply();
            } catch (GeneralSecurityException | IOException e) {
                // 加密失败时至少留在本次进程内存里，重新输入一次也能继续用
            }
            prefs.edit().remove(oldHost).apply();
        }
    }

    public Set<String> storedHosts() {
        return new TreeSet<>(prefs.getAll().keySet());
    }

    private String serialize(Credential credential) {
        return credential.user + SEPARATOR + credential.domain + SEPARATOR + credential.password;
    }

    private Credential deserialize(String plain) {
        String[] parts = plain.split(String.valueOf(SEPARATOR), 3);
        if (parts.length < 3) {
            return null;
        }
        return new Credential(parts[0], parts[1], parts[2]);
    }

    private String encrypt(String plain) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain.getBytes(UTF_8));
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    private Credential read(String hostId) {
        String stored = prefs.getString(hostId, null);
        if (stored == null) {
            return null;
        }
        try {
            byte[] packed = Base64.decode(stored, Base64.NO_WRAP);
            if (packed.length <= IV_LENGTH) {
                throw new GeneralSecurityException("密文长度不对");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH);
            Credential credential = deserialize(new String(plain, UTF_8));
            if (credential == null) {
                throw new GeneralSecurityException("内容格式不对");
            }
            return credential;
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            // 密钥随换机丢失、被改过的密文、格式损坏都只会走到这里：当作没存过并清掉条目
            forget(hostId);
            return null;
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
