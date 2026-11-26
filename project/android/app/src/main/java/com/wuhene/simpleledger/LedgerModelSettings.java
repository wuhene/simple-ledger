package com.wuhene.simpleledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 将本机模型设置和加密后的密钥保存到应用私有存储。
 *
 * @author chaoshunh
 * @date 2026/11/26
 */
final class LedgerModelSettings {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "ledger-assistant-model-secret";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private final SharedPreferences storage;

    /**
     * 打开模型设置的本机存储。
     */
    LedgerModelSettings(Context context) {
        storage = context.getSharedPreferences("assistant_model", Context.MODE_PRIVATE);
    }

    /**
     * 恢复已保存的模型地址。
     */
    String base() {
        return storage.getString("base", "");
    }

    /**
     * 恢复已保存的模型 ID。
     */
    String model() {
        return storage.getString("model", "");
    }

    /**
     * 解密本机 API Key。
     */
    String secret() {
        String saved = storage.getString("secret", "");
        if (saved.isEmpty()) return "";
        try {
            String[] parts = saved.split("\\.", 2);
            if (parts.length != 2) return "";
            SecretKey key = key(false);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception unavailable) {
            return "";
        }
    }

    /**
     * 将完整配置一次写入本机。
     */
    void save(String base, String model, String secret) {
        String encrypted = "";
        try {
            if (!secret.isEmpty()) {
                Cipher cipher = Cipher.getInstance(CIPHER);
                cipher.init(Cipher.ENCRYPT_MODE, key(true));
                encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                        Base64.encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            }
        } catch (Exception error) {
            throw new IllegalStateException("无法加密保存 API Key，请稍后重试。", error);
        }
        if (!storage.edit().putString("base", base).putString("model", model).putString("secret", encrypted).commit()) {
            throw new IllegalStateException("无法保存模型设置，请稍后重试。");
        }
    }

    /**
     * 清空本机模型设置和加密密钥副本。
     */
    void clear() {
        if (!storage.edit().clear().commit()) throw new IllegalStateException("无法清除模型设置，请稍后重试。");
    }

    /**
     * 读取或初始化此安装独有的系统加密密钥。
     */
    private SecretKey key(boolean create) throws GeneralSecurityException, java.io.IOException {
        KeyStore keys = KeyStore.getInstance(PROVIDER);
        keys.load(null);
        SecretKey key = (SecretKey) keys.getKey(KEY_ALIAS, null);
        if (key != null || !create) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
