package com.gudesk.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

/**
 * 通用加密工具：X25519 密钥交换、HKDF-SHA256 会话密钥派生、AES-256-GCM 加解密、
 * 指纹计算与口令哈希等无状态静态方法。
 *
 * <p>典型会话建立流程：
 * <ol>
 *   <li>双方各自 {@link #generateX25519KeyPair()} 生成临时密钥对并交换公钥；</li>
 *   <li>各自 {@link #ecdh(PrivateKey, PublicKey)} 计算 32 字节共享密钥；</li>
 *   <li>salt 取双方公钥按固定顺序拼接后的 SHA-256（双方拼接顺序需一致）；</li>
 *   <li>{@link #deriveSessionKey(byte[], byte[])} HKDF 派生 AES-256 会话密钥。</li>
 * </ol>
 */
public final class CryptoUtil {

    /** AES-GCM 随机 nonce 长度（字节），加密时前置在密文之前 */
    public static final int GCM_NONCE_LENGTH = 12;
    /** AES-GCM 认证标签长度（位） */
    public static final int GCM_TAG_LENGTH_BITS = 128;
    /** 会话密钥长度（位）：AES-256 */
    public static final int SESSION_KEY_LENGTH_BITS = 256;
    /** PBKDF2 迭代次数 */
    public static final int PBKDF2_ITERATIONS = 120_000;

    /** 默认 HKDF info，用于区分派生用途 */
    private static final byte[] DEFAULT_HKDF_INFO = "gudesk/session-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = new byte[0];

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    /**
     * 生成 X25519 临时密钥对（JCA）。
     */
    public static KeyPair generateX25519KeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
        return generator.generateKeyPair();
    }

    /**
     * X25519 ECDH 密钥协商，返回 32 字节共享密钥。
     */
    public static byte[] ecdh(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    /**
     * HKDF-SHA256 派生 AES-256 会话密钥。
     *
     * @param sharedSecret ECDH 共享密钥（IKM）
     * @param salt         HKDF 盐，推荐使用双方公钥按固定顺序拼接后的 SHA-256
     */
    public static SecretKey deriveSessionKey(byte[] sharedSecret, byte[] salt) throws GeneralSecurityException {
        return deriveSessionKey(sharedSecret, salt, DEFAULT_HKDF_INFO);
    }

    /**
     * HKDF-SHA256 派生 AES-256 会话密钥（自定义 info，可区分方向/用途）。
     */
    public static SecretKey deriveSessionKey(byte[] sharedSecret, byte[] salt, byte[] info)
            throws GeneralSecurityException {
        byte[] prk = hkdfExtract(salt, sharedSecret);
        byte[] okm = hkdfExpand(prk, info, SESSION_KEY_LENGTH_BITS / 8);
        return new SecretKeySpec(okm, "AES");
    }

    /**
     * AES-256-GCM 加密：随机生成 12 字节 nonce 前置在密文之前，返回 nonce || ciphertext||tag。
     *
     * @param aad 附加认证数据，可为 null（认证但不加密）
     */
    public static byte[] aesGcmEncrypt(SecretKey key, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        updateAad(cipher, aad);
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] out = new byte[GCM_NONCE_LENGTH + ciphertext.length];
        System.arraycopy(nonce, 0, out, 0, GCM_NONCE_LENGTH);
        System.arraycopy(ciphertext, 0, out, GCM_NONCE_LENGTH, ciphertext.length);
        return out;
    }

    /**
     * AES-256-GCM 解密：入参为 {@link #aesGcmEncrypt} 输出的 nonce || ciphertext||tag。
     * 密文或 AAD 被篡改时抛出 {@link javax.crypto.AEADBadTagException}。
     */
    public static byte[] aesGcmDecrypt(SecretKey key, byte[] ciphertext, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, ciphertext, 0, GCM_NONCE_LENGTH));
        updateAad(cipher, aad);
        return cipher.doFinal(ciphertext, GCM_NONCE_LENGTH, ciphertext.length - GCM_NONCE_LENGTH);
    }

    /**
     * 计算 SHA-256 摘要。
     */
    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // JCA 规范强制要求提供 SHA-256，理论上不可达
            throw new IllegalStateException("当前 JVM 缺少 SHA-256 实现", e);
        }
    }

    /**
     * 公钥指纹：SHA-256 后按字节十六进制大写并以冒号分组（如 AB:CD:EF:...），用于信任列表展示与比对。
     */
    public static String fingerprint(byte[] publicKey) {
        byte[] digest = sha256(publicKey);
        StringBuilder sb = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hexUpper((digest[i] >> 4) & 0xF)).append(hexUpper(digest[i] & 0xF));
        }
        return sb.toString();
    }

    /**
     * 口令哈希：PBKDF2WithHmacSHA256，120000 次迭代，输出 256 位。
     */
    public static byte[] hashPassword(String password, byte[] salt) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 口令校验（常量时间比较），预期哈希来自 {@link #hashPassword(String, byte[])}。
     */
    public static boolean verifyPassword(String password, byte[] salt, byte[] expectedHash)
            throws GeneralSecurityException {
        return MessageDigest.isEqual(hashPassword(password, salt), expectedHash);
    }

    // ---------- HKDF-SHA256（RFC 5869） ----------

    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        byte[] actualSalt = (salt == null || salt.length == 0) ? new byte[32] : salt;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(actualSalt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] infoBytes = (info == null) ? EMPTY : info;
        byte[] result = new byte[length];
        byte[] t = EMPTY;
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
            mac.update(t);
            mac.update(infoBytes);
            mac.update(counter);
            t = mac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, result, pos, n);
            pos += n;
            counter++;
        }
        return result;
    }

    private static void updateAad(Cipher cipher, byte[] aad) throws GeneralSecurityException {
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
    }

    private static char hexUpper(int nibble) {
        return Character.toUpperCase(Character.forDigit(nibble, 16));
    }
}
