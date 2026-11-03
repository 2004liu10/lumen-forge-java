package com.lumenforge.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 本地凭据加密器：使用 AES-GCM 加密账号 API Key 与网页登录凭据。
 * 主密钥从环境变量注入，随机 nonce 随密文保存，并通过 GCM 标签校验密文完整性。
 */
@Component
public class CredentialCipher {
    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String LEGACY_PREFIX = "enc:v1:";
    private static final String PREFIX = "enc:v2:";
    private static final int NONCE_SIZE = 12;
    /** v2 使用 128 位密钥，兼容旧版 Java 8 默认的 JCE 密钥长度限制。 */
    private final byte[] key;
    /** 保留旧版 256 位派生密钥，用于在支持无限强度策略的 JVM 上读取 v1 密文。 */
    private final byte[] legacyKey;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${app.security.credential-key:}") String masterKey) {
        try {
            if (masterKey == null || masterKey.trim().isEmpty()) {
                this.key = null;
                this.legacyKey = null;
            } else {
                byte[] derived = MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8));
                this.key = Arrays.copyOf(derived, 16);
                this.legacyKey = derived;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize credential encryption", exception);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return "";
        ensureKey();
        try {
            byte[] nonce = new byte[NONCE_SIZE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt account credential", exception);
        }
    }

    /** 仅供健康检查展示配置状态，不暴露或记录实际主密钥。 */
    public boolean isConfigured() {
        return key != null;
    }

    /**
     * 密文 → 明文。
     *
     * <p>与 Python 版 {@code app/secret.py:64 open_secret} 语义一致：不是密文就原样返回；密文解不开
     * （换了机器 / 换了 APP_CREDENTIAL_KEY / 从别的版本迁过来的数据）时**不抛异常**，而是记一条告警并
     * 返回原始存储值。这样账号列表页至少还能打开、用户能看见哪一条坏了并重新录入，
     * 而不是整个接口 500。真正拿这个值去调上游 API 时，上游会返回鉴权失败，比本地 500 更好排查。
     */
    public String decrypt(String value) {
        if (value == null || value.isEmpty()) return "";
        boolean isLegacy = value.startsWith(LEGACY_PREFIX);
        if (!isLegacy && !value.startsWith(PREFIX)) {
            // 明文落盘（Python 版纯服务模式的正常形态），原样返回。
            return value;
        }
        if (key == null) return value;
        String prefix = isLegacy ? LEGACY_PREFIX : PREFIX;
        try {
            byte[] packed = Base64.getDecoder().decode(value.substring(prefix.length()));
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] nonce = new byte[NONCE_SIZE];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] decryptionKey = isLegacy ? legacyKey : key;
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(decryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            // 对齐 Python app/secret.py:74：换机器/换 APP_CREDENTIAL_KEY 都会走到这里，
            // 保留原值让上层报错时能看见存的是什么。只记一行，不打堆栈——
            // 这是可预期的容错分支，完整堆栈会把真正的故障淹掉。
            log.warn("Unable to decrypt stored credential (wrong APP_CREDENTIAL_KEY or data from another install); "
                    + "keeping the stored value as-is: {}", exception.toString());
            return value;
        }
    }

    /** 严格版解密：解不开就抛异常。用于必须拿到明文才能继续的路径。 */
    public String decryptStrict(String value) {
        String plain = decrypt(value);
        if (plain != null && (plain.startsWith(PREFIX) || plain.startsWith(LEGACY_PREFIX))) {
            throw new IllegalStateException("Unable to decrypt account credential; check APP_CREDENTIAL_KEY");
        }
        return plain;
    }

    private void ensureKey() {
        if (key == null) throw new CredentialKeyNotConfiguredException();
    }
}




