package com.gudesk.common.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoUtilTest {

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 由固定共享密钥派生测试用 AES-256 密钥 */
    private SecretKey testKey() throws Exception {
        byte[] sharedSecret = new byte[32];
        new SecureRandom().nextBytes(sharedSecret);
        return CryptoUtil.deriveSessionKey(sharedSecret, CryptoUtil.sha256("salt".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void ecdh双方派生相同会话密钥() throws Exception {
        KeyPair viewer = CryptoUtil.generateX25519KeyPair();
        KeyPair host = CryptoUtil.generateX25519KeyPair();

        byte[] secretFromViewer = CryptoUtil.ecdh(viewer.getPrivate(), host.getPublic());
        byte[] secretFromHost = CryptoUtil.ecdh(host.getPrivate(), viewer.getPublic());

        // 共享密钥 32 字节且双方一致
        assertEquals(32, secretFromViewer.length);
        assertArrayEquals(secretFromViewer, secretFromHost);

        // 双方公钥拼接的 SHA-256 作为 salt，派生出相同的 AES-256 密钥
        byte[] salt = CryptoUtil.sha256(concat(viewer.getPublic().getEncoded(), host.getPublic().getEncoded()));
        SecretKey viewerKey = CryptoUtil.deriveSessionKey(secretFromViewer, salt);
        SecretKey hostKey = CryptoUtil.deriveSessionKey(secretFromHost, salt);
        assertEquals(32, viewerKey.getEncoded().length);
        assertArrayEquals(viewerKey.getEncoded(), hostKey.getEncoded());
    }

    @Test
    void aesGcm加解密往返() throws Exception {
        SecretKey key = testKey();
        byte[] aad = "gudesk-aad".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "GuDesk 加密往返测试 123".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = CryptoUtil.aesGcmEncrypt(key, plaintext, aad);
        // nonce(12) 前置 + 密文 + tag(16)
        assertEquals(plaintext.length + 12 + 16, ciphertext.length);
        assertNotEquals(plaintext.length, ciphertext.length);

        byte[] decrypted = CryptoUtil.aesGcmDecrypt(key, ciphertext, aad);
        assertArrayEquals(plaintext, decrypted);

        // 随机 nonce：同一明文两次加密密文不同
        byte[] ciphertext2 = CryptoUtil.aesGcmEncrypt(key, plaintext, aad);
        assertFalse(java.util.Arrays.equals(ciphertext, ciphertext2));
        assertArrayEquals(plaintext, CryptoUtil.aesGcmDecrypt(key, ciphertext2, aad));
    }

    @Test
    void 篡改AAD解密抛AEADBadTagException() throws Exception {
        SecretKey key = testKey();
        byte[] plaintext = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = CryptoUtil.aesGcmEncrypt(key, plaintext, "correct-aad".getBytes(StandardCharsets.UTF_8));

        assertThrows(AEADBadTagException.class, () ->
                CryptoUtil.aesGcmDecrypt(key, ciphertext, "tampered-aad".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 篡改密文解密抛AEADBadTagException() throws Exception {
        SecretKey key = testKey();
        byte[] plaintext = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = CryptoUtil.aesGcmEncrypt(key, plaintext, null);

        // 篡改密文区域最后一个字节（tag 内）
        ciphertext[ciphertext.length - 1] ^= 0x5A;
        byte[] tampered = ciphertext;
        assertThrows(AEADBadTagException.class, () -> CryptoUtil.aesGcmDecrypt(key, tampered, null));
    }

    @Test
    void 密码哈希与校验正反例() throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = CryptoUtil.hashPassword("GuDesk密码123", salt);

        // PBKDF2WithHmacSHA256 / 120000 迭代 / 256 位
        assertEquals(32, hash.length);

        // 正例：同一口令与盐
        assertTrue(CryptoUtil.verifyPassword("GuDesk密码123", salt, hash));
        // 反例：错误口令
        assertFalse(CryptoUtil.verifyPassword("错误口令", salt, hash));
        // 反例：不同盐
        byte[] otherSalt = new byte[16];
        new SecureRandom().nextBytes(otherSalt);
        assertFalse(CryptoUtil.verifyPassword("GuDesk密码123", otherSalt, hash));
    }

    @Test
    void 指纹格式与确定性() {
        byte[] publicKey = new byte[32];
        new SecureRandom().nextBytes(publicKey);

        String fingerprint = CryptoUtil.fingerprint(publicKey);
        // 32 字节摘要 → 32 组两位大写十六进制，冒号分隔
        assertTrue(fingerprint.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"),
                "指纹格式不合法: " + fingerprint);
        // 确定性
        assertEquals(fingerprint, CryptoUtil.fingerprint(publicKey));
        // 不同公钥指纹不同
        byte[] another = new byte[32];
        new SecureRandom().nextBytes(another);
        assertNotEquals(fingerprint, CryptoUtil.fingerprint(another));
    }
}
