package com.gudesk.common.session;

import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionHandshake} 单测：公钥编解码往返、密码证明计算/校验（正/反例）、
 * 会话密钥盐一致性与双端密码互通性。
 */
class SessionHandshakeTest {

    @Test
    void 公钥编码解析往返() throws Exception {
        KeyPair pair = SessionHandshake.generateKeyPair();
        byte[] encoded = SessionHandshake.encodePublicKey(pair.getPublic());
        assertNotNull(encoded);
        PublicKey parsed = SessionHandshake.parsePublicKey(encoded);
        assertArrayEquals(encoded, SessionHandshake.encodePublicKey(parsed));
    }

    @Test
    void 密码证明验证正确密码() throws Exception {
        KeyPair viewerKeys = SessionHandshake.generateKeyPair();
        byte[] viewerPub = SessionHandshake.encodePublicKey(viewerKeys.getPublic());
        String proof = SessionHandshake.passwordProof("483920", viewerPub);
        assertEquals(64, proof.length());
        assertTrue(SessionHandshake.verifyPasswordProof(proof, "483920", viewerPub));
    }

    @Test
    void 密码证明验证错误密码() throws Exception {
        KeyPair viewerKeys = SessionHandshake.generateKeyPair();
        byte[] viewerPub = SessionHandshake.encodePublicKey(viewerKeys.getPublic());
        String proof = SessionHandshake.passwordProof("483920", viewerPub);
        assertFalse(SessionHandshake.verifyPasswordProof(proof, "483921", viewerPub));
        assertFalse(SessionHandshake.verifyPasswordProof("", "483920", viewerPub));
        assertFalse(SessionHandshake.verifyPasswordProof(null, "483920", viewerPub));
    }

    @Test
    void 证明绑定主控端临时公钥() throws Exception {
        // 换一对临时公钥（模拟重放者）后，同一密码算出的证明无法通过针对原公钥的校验
        KeyPair original = SessionHandshake.generateKeyPair();
        KeyPair replay = SessionHandshake.generateKeyPair();
        byte[] originalPub = SessionHandshake.encodePublicKey(original.getPublic());
        String proof = SessionHandshake.passwordProof("483920", SessionHandshake.encodePublicKey(replay.getPublic()));
        assertFalse(SessionHandshake.verifyPasswordProof(proof, "483920", originalPub));
    }

    @Test
    void 双端派生一致的方向隔离会话密钥() throws Exception {
        KeyPair viewerKeys = SessionHandshake.generateKeyPair();
        KeyPair hostKeys = SessionHandshake.generateKeyPair();
        byte[] viewerPub = SessionHandshake.encodePublicKey(viewerKeys.getPublic());
        byte[] hostPub = SessionHandshake.encodePublicKey(hostKeys.getPublic());

        byte[] sharedViewer = SessionHandshake.ecdh(viewerKeys.getPrivate(),
                SessionHandshake.parsePublicKey(hostPub));
        byte[] sharedHost = SessionHandshake.ecdh(hostKeys.getPrivate(),
                SessionHandshake.parsePublicKey(viewerPub));
        assertArrayEquals(sharedViewer, sharedHost);

        // 盐计算与拼接顺序无关（固定主控端公钥在前），双端一致
        assertArrayEquals(SessionHandshake.cipherSalt(viewerPub, hostPub),
                SessionHandshake.cipherSalt(viewerPub, hostPub));

        SessionCipher viewerCipher = SessionCipher.init(sharedViewer,
                SessionHandshake.cipherSalt(viewerPub, hostPub), true);
        SessionCipher hostCipher = SessionCipher.init(sharedHost,
                SessionHandshake.cipherSalt(viewerPub, hostPub), false);

        // 主控→被控方向加解密互通
        SessionMessage fromViewer = SessionMessage.newBuilder()
                .setNegotiate(com.gudesk.common.proto.GuDeskProto.SessionNegotiate.newBuilder()
                        .setEphemeralPublicKey(com.google.protobuf.ByteString.copyFrom(viewerPub))
                        .setPasswordProof("deadbeef"))
                .build();
        assertEquals(fromViewer, hostCipher.decrypt(viewerCipher.encrypt(fromViewer)));

        // 被控→主控方向加解密互通
        SessionMessage fromHost = SessionMessage.newBuilder()
                .setNegotiateAck(com.gudesk.common.proto.GuDeskProto.SessionNegotiateAck.newBuilder()
                        .setEphemeralPublicKey(com.google.protobuf.ByteString.copyFrom(hostPub))
                        .setAuthorized(true))
                .build();
        assertEquals(fromHost, viewerCipher.decrypt(hostCipher.encrypt(fromHost)));

        // 方向密钥隔离：主控端无法用自己的收钥（hostToViewer）解自己发出的密文
        byte[] viewerOutbound = viewerCipher.encrypt(fromViewer);
        assertThrows(GeneralSecurityException.class, () -> viewerCipher.decrypt(viewerOutbound));
    }
}
