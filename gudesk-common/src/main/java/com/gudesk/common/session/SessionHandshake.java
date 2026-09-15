package com.gudesk.common.session;

import com.gudesk.common.crypto.CryptoUtil;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/**
 * 会话协商握手工具：X25519 临时密钥对、ECDH 共享密钥、会话密钥盐与密码证明的静态计算。
 *
 * <p>直连会话握手流程（协商消息明文传输，协商完成后全部流量切换为 AES-GCM 密文）：
 * <ol>
 *   <li>主控端生成 X25519 临时密钥对，计算 {@link #passwordProof(String, byte[])}
 *       （盐 = 主控端公钥 SHA-256 前 16 字节），发送
 *       SessionNegotiate{ephemeral_public_key, password_proof}；</li>
 *   <li>被控端解析公钥、ECDH 得共享密钥，{@link #verifyPasswordProof} 比对密码证明，
 *       通过后走授权确认；</li>
 *   <li>被控端回 SessionNegotiateAck{ephemeral_public_key(被控端), authorized}；</li>
 *   <li>双方各自以 {@link #cipherSalt(byte[], byte[])} 派生会话密钥盐
 *       （拼接顺序固定为主控端公钥在前），经 {@link com.gudesk.common.crypto.SessionCipher#init}
 *       得到方向隔离的收发密钥。</li>
 * </ol>
 */
public final class SessionHandshake {

    /** 密码证明盐长度（字节）：主控端公钥 SHA-256 摘要前 16 字节 */
    public static final int PROOF_SALT_LENGTH = 16;

    private SessionHandshake() {
    }

    /**
     * 生成 X25519 临时密钥对。
     */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        return CryptoUtil.generateX25519KeyPair();
    }

    /**
     * 解析对端公钥（X.509 SubjectPublicKeyInfo 编码，即 {@link #encodePublicKey} 的输出）。
     */
    public static PublicKey parsePublicKey(byte[] encoded) throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("X25519");
        return factory.generatePublic(new X509EncodedKeySpec(encoded));
    }

    /**
     * 公钥编码（X.509 SubjectPublicKeyInfo），用于线上传输与盐计算。
     */
    public static byte[] encodePublicKey(PublicKey publicKey) {
        return publicKey.getEncoded();
    }

    /**
     * ECDH 共享密钥（32 字节）。
     */
    public static byte[] ecdh(PrivateKey ownPrivate, PublicKey peerPublic)
            throws GeneralSecurityException {
        return CryptoUtil.ecdh(ownPrivate, peerPublic);
    }

    /**
     * 会话密钥盐：SHA-256(主控端公钥 || 被控端公钥)。
     * 两端必须使用相同拼接顺序（主控端公钥在前）。
     */
    public static byte[] cipherSalt(byte[] viewerPublicKey, byte[] hostPublicKey) {
        byte[] concat = new byte[viewerPublicKey.length + hostPublicKey.length];
        System.arraycopy(viewerPublicKey, 0, concat, 0, viewerPublicKey.length);
        System.arraycopy(hostPublicKey, 0, concat, viewerPublicKey.length, hostPublicKey.length);
        return CryptoUtil.sha256(concat);
    }

    /**
     * 密码证明：hex(PBKDF2(password, salt = SHA-256(主控端公钥) 前 16 字节))，64 个十六进制字符。
     *
     * <p>证明与主控端本次临时公钥绑定：更换临时公钥重放需要重新知道密码才能构造。
     */
    public static String passwordProof(String password, byte[] viewerPublicKey)
            throws GeneralSecurityException {
        byte[] salt = new byte[PROOF_SALT_LENGTH];
        System.arraycopy(CryptoUtil.sha256(viewerPublicKey), 0, salt, 0, PROOF_SALT_LENGTH);
        return HexFormat.of().formatHex(CryptoUtil.hashPassword(password, salt));
    }

    /**
     * 验证密码证明（常量时间比较）：被控端用相同输入计算后与对端证明比对。
     */
    public static boolean verifyPasswordProof(String proof, String password, byte[] viewerPublicKey)
            throws GeneralSecurityException {
        if (proof == null || proof.isBlank()) {
            return false;
        }
        String expected = passwordProof(password, viewerPublicKey);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                proof.trim().getBytes(StandardCharsets.US_ASCII));
    }
}
