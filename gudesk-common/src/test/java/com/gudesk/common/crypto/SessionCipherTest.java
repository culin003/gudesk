package com.gudesk.common.crypto;

import com.gudesk.common.proto.GuDeskProto.KeyFrameRequest;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.VideoFrame;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionCipherTest {

    /** 构造主控/被控两侧加密器（盐使用双方公钥拼接的 SHA-256） */
    private SessionCipher[] viewerAndHostCiphers() throws Exception {
        KeyPair viewer = CryptoUtil.generateX25519KeyPair();
        KeyPair host = CryptoUtil.generateX25519KeyPair();

        byte[] shared1 = CryptoUtil.ecdh(viewer.getPrivate(), host.getPublic());
        byte[] shared2 = CryptoUtil.ecdh(host.getPrivate(), viewer.getPublic());
        assertArrayEquals(shared1, shared2);

        byte[] salt = CryptoUtil.sha256(concat(viewer.getPublic().getEncoded(), host.getPublic().getEncoded()));
        return new SessionCipher[]{
                SessionCipher.init(shared1, salt, true),
                SessionCipher.init(shared2, salt, false)
        };
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void 双向收发加密往返() throws Exception {
        SessionCipher[] pair = viewerAndHostCiphers();
        SessionCipher viewerCipher = pair[0];
        SessionCipher hostCipher = pair[1];

        // 主控 → 被控（输入事件类消息）
        SessionMessage toHost = SessionMessage.newBuilder()
                .setKeyframeRequest(KeyFrameRequest.getDefaultInstance())
                .build();
        assertEquals(toHost, hostCipher.decrypt(viewerCipher.encrypt(toHost)));

        // 被控 → 主控（视频帧消息）
        SessionMessage toViewer = SessionMessage.newBuilder()
                .setVideoFrame(VideoFrame.newBuilder()
                        .setH264Data(ByteString.copyFrom(new byte[]{1, 2, 3, 4}))
                        .setFrameIndex(42)
                        .setKeyframe(true)
                        .setWidth(1920)
                        .setHeight(1080)
                        .setCaptureNs(System.nanoTime()))
                .build();
        assertEquals(toViewer, viewerCipher.decrypt(hostCipher.encrypt(toViewer)));
    }

    @Test
    void 方向密钥隔离_错误方向解密失败() throws Exception {
        SessionCipher[] pair = viewerAndHostCiphers();
        SessionCipher viewerCipher = pair[0];

        // 主控发出（viewer→host 密钥加密），却尝试用主控自己的接收密钥（host→viewer）解密
        SessionMessage message = SessionMessage.newBuilder()
                .setMouseMove(com.gudesk.common.proto.GuDeskProto.MouseMoveEvent.newBuilder()
                        .setX(0.5).setY(0.5))
                .build();
        byte[] ciphertext = viewerCipher.encrypt(message);

        assertThrows(AEADBadTagException.class, () -> viewerCipher.decrypt(ciphertext));
    }

    @Test
    void 相同明文两次加密密文不同() throws Exception {
        SessionCipher[] pair = viewerAndHostCiphers();
        SessionMessage message = SessionMessage.newBuilder()
                .setSessionClose(com.gudesk.common.proto.GuDeskProto.SessionClose.newBuilder()
                        .setReason("bye"))
                .build();
        byte[] c1 = pair[0].encrypt(message);
        byte[] c2 = pair[0].encrypt(message);
        assertFalse(java.util.Arrays.equals(c1, c2), "随机 nonce 下两次密文不应相同");
        assertEquals(message, pair[1].decrypt(c1));
        assertEquals(message, pair[1].decrypt(c2));
    }
}
