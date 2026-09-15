package com.gudesk.common.crypto;

import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.google.protobuf.InvalidProtocolBufferException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * 会话对称加密器：封装一对方向的收发加密，负责 {@link SessionMessage} 与密文的互转。
 *
 * <p>初始化（{@link #init(byte[], byte[], boolean)}）后即可 encrypt/decrypt：
 * <ul>
 *   <li>send 方向使用 viewer→host 派生密钥，receive 方向使用 host→viewer 派生密钥，
 *       主控端（viewer）与被控端（host）以相反方向组合，保证双向密钥隔离；</li>
 *   <li>AAD 使用固定前缀 "gudesk-session"，防跨协议/跨用途重放；</li>
 *   <li>本类只持有密钥，无会话状态，线程安全。</li>
 * </ul>
 *
 * <p>salt 推荐使用双方公钥按固定顺序拼接后的 SHA-256（两端拼接顺序一致即可）。
 */
public final class SessionCipher {

    /** 会话加密固定 AAD 前缀 */
    public static final byte[] SESSION_AAD = "gudesk-session".getBytes(StandardCharsets.US_ASCII);

    /** 主控→被控方向的 HKDF info */
    private static final byte[] INFO_VIEWER_TO_HOST = "gudesk/session/viewer-to-host".getBytes(StandardCharsets.UTF_8);
    /** 被控→主控方向的 HKDF info */
    private static final byte[] INFO_HOST_TO_VIEWER = "gudesk/session/host-to-viewer".getBytes(StandardCharsets.UTF_8);

    private final SecretKey sendKey;
    private final SecretKey receiveKey;

    /**
     * 以显式的收发密钥构造（密钥由调用方自行派生）。
     */
    public SessionCipher(SecretKey sendKey, SecretKey receiveKey) {
        this.sendKey = Objects.requireNonNull(sendKey, "sendKey");
        this.receiveKey = Objects.requireNonNull(receiveKey, "receiveKey");
    }

    /**
     * 由 ECDH 共享密钥派生一对方向密钥并构造加密器。
     *
     * @param sharedSecret ECDH 共享密钥（两端计算结果一致）
     * @param salt         HKDF 盐，推荐双方公钥拼接的 SHA-256
     * @param viewerSide   true=主控端视角；false=被控端视角
     */
    public static SessionCipher init(byte[] sharedSecret, byte[] salt, boolean viewerSide)
            throws GeneralSecurityException {
        SecretKey viewerToHost = CryptoUtil.deriveSessionKey(sharedSecret, salt, INFO_VIEWER_TO_HOST);
        SecretKey hostToViewer = CryptoUtil.deriveSessionKey(sharedSecret, salt, INFO_HOST_TO_VIEWER);
        return viewerSide
                ? new SessionCipher(viewerToHost, hostToViewer)
                : new SessionCipher(hostToViewer, viewerToHost);
    }

    /**
     * 加密一个会话消息，返回 AES-GCM 密文（nonce 前置）。
     */
    public byte[] encrypt(SessionMessage message) throws GeneralSecurityException {
        return CryptoUtil.aesGcmEncrypt(sendKey, message.toByteArray(), SESSION_AAD);
    }

    /**
     * 解密对端发来的密文并还原会话消息；密文被篡改时抛出
     * {@link javax.crypto.AEADBadTagException}。
     */
    public SessionMessage decrypt(byte[] ciphertext) throws GeneralSecurityException {
        byte[] plaintext = CryptoUtil.aesGcmDecrypt(receiveKey, ciphertext, SESSION_AAD);
        try {
            return SessionMessage.parseFrom(plaintext);
        } catch (InvalidProtocolBufferException e) {
            // 密文已通过 GCM 认证，解析失败说明对端发送了非法会话消息（协议违规）
            throw new IllegalStateException("会话消息解析失败", e);
        }
    }
}
