package com.gudesk.viewer.session;

import com.gudesk.common.crypto.CryptoUtil;
import com.gudesk.common.session.SessionHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViewerIdentityStore} 单元测试：首次生成持久化、重载同一密钥（指纹稳定）、
 * 损坏文件重新生成、密钥可用于 ECDH。
 */
class ViewerIdentityStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 首次生成并持久化后重载同一密钥() throws Exception {
        Path file = tempDir.resolve("viewer_identity");
        KeyPair first = ViewerIdentityStore.loadOrGenerate(file);
        assertTrue(Files.exists(file), "首次应生成并写入文件");
        KeyPair second = ViewerIdentityStore.loadOrGenerate(file);
        assertArrayEquals(first.getPrivate().getEncoded(), second.getPrivate().getEncoded(),
                "重载私钥应一致");
        assertArrayEquals(first.getPublic().getEncoded(), second.getPublic().getEncoded(),
                "重载公钥应一致（指纹稳定）");
        assertEquals(CryptoUtil.fingerprint(first.getPublic().getEncoded()),
                CryptoUtil.fingerprint(second.getPublic().getEncoded()));
    }

    @Test
    void 不同文件生成不同密钥() throws Exception {
        KeyPair a = ViewerIdentityStore.loadOrGenerate(tempDir.resolve("identity-a"));
        KeyPair b = ViewerIdentityStore.loadOrGenerate(tempDir.resolve("identity-b"));
        assertFalse(Arrays.equals(a.getPublic().getEncoded(), b.getPublic().getEncoded()),
                "不同文件应生成不同身份密钥");
    }

    @Test
    void 损坏文件重新生成() throws Exception {
        Path file = tempDir.resolve("viewer_identity");
        Files.write(file, "garbage\nnot-hex\nlines".getBytes(StandardCharsets.US_ASCII));
        KeyPair regenerated = ViewerIdentityStore.loadOrGenerate(file);
        // 覆盖后文件可再次正常加载且与重新生成的一致
        KeyPair reloaded = ViewerIdentityStore.loadOrGenerate(file);
        assertArrayEquals(regenerated.getPublic().getEncoded(), reloaded.getPublic().getEncoded());
    }

    @Test
    void 身份密钥可用于ECDH() throws Exception {
        KeyPair identity = ViewerIdentityStore.loadOrGenerate(tempDir.resolve("viewer_identity"));
        KeyPair peer = SessionHandshake.generateKeyPair();
        byte[] shared1 = SessionHandshake.ecdh(identity.getPrivate(), peer.getPublic());
        byte[] shared2 = SessionHandshake.ecdh(peer.getPrivate(), identity.getPublic());
        assertEquals(32, shared1.length, "X25519 共享密钥应为 32 字节");
        assertArrayEquals(shared1, shared2, "双方 ECDH 结果应一致");
    }
}
