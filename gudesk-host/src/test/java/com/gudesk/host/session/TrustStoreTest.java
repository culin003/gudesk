package com.gudesk.host.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TrustStore} 单元测试：临时目录持久化、命中/移除/UTF-8 备注往返、幂等。
 */
class TrustStoreTest {

    private static final String FP_A = "AB:CD:EF:01:02:03:04:05";
    private static final String FP_B = "11:22:33:44:55:66:77:88";

    @TempDir
    Path tempDir;

    @Test
    void 信任后命中() throws IOException {
        TrustStore store = new TrustStore(tempDir.resolve("trusted_viewers"));
        assertFalse(store.isTrusted(FP_A), "未信任前不应命中");
        store.trust(FP_A, "测试主控");
        assertTrue(store.isTrusted(FP_A), "信任后应命中");
        assertFalse(store.isTrusted(FP_B), "其他指纹不应命中");
        // 大小写不敏感
        assertTrue(store.isTrusted(FP_A.toLowerCase()));
    }

    @Test
    void 持久化后新实例仍命中() throws IOException {
        Path file = tempDir.resolve("trusted_viewers");
        TrustStore writer = new TrustStore(file);
        writer.trust(FP_A, "alpha");
        TrustStore reader = new TrustStore(file);
        assertTrue(reader.isTrusted(FP_A), "新实例（重读文件）应命中");
        assertEquals(1, reader.entries().size());
        assertEquals("alpha", reader.entries().get(0).name());
        assertTrue(reader.entries().get(0).trustedAtMillis() > 0, "信任时间应记录");
    }

    @Test
    void 移除信任() throws IOException {
        TrustStore store = new TrustStore(tempDir.resolve("trusted_viewers"));
        store.trust(FP_A, "a");
        store.trust(FP_B, "b");
        assertTrue(store.remove(FP_A), "移除已信任指纹应返回 true");
        assertFalse(store.isTrusted(FP_A));
        assertTrue(store.isTrusted(FP_B), "其他条目不受影响");
        assertFalse(store.remove(FP_A), "再次移除应返回 false");
    }

    @Test
    void 中文备注UTF8往返() throws IOException {
        Path file = tempDir.resolve("trusted_viewers");
        TrustStore store = new TrustStore(file);
        store.trust(FP_A, "张三的笔记本");
        TrustStore reloaded = new TrustStore(file);
        assertEquals("张三的笔记本", reloaded.entries().get(0).name(),
                "中文备注应经 UTF-8 文件无损往返");
        // 文件本身为 UTF-8 编码
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(raw.contains("张三的笔记本"));
        assertTrue(raw.contains(FP_A));
    }

    @Test
    void 重复信任不产生重复行且更新备注() throws IOException {
        TrustStore store = new TrustStore(tempDir.resolve("trusted_viewers"));
        store.trust(FP_A, "旧备注");
        long firstTrustedAt = store.entries().get(0).trustedAtMillis();
        store.trust(FP_A, "新备注");
        assertEquals(1, store.entries().size(), "重复信任不应产生重复行");
        assertEquals("新备注", store.entries().get(0).name());
        assertEquals(firstTrustedAt, store.entries().get(0).trustedAtMillis(), "首次信任时间应保留");
    }

    @Test
    void 空白指纹安全处理() {
        TrustStore store = new TrustStore(tempDir.resolve("trusted_viewers"));
        assertFalse(store.isTrusted(null));
        assertFalse(store.isTrusted(""));
        assertFalse(store.isTrusted("   "));
        assertThrows(IllegalArgumentException.class, () -> store.trust("", "x"));
    }

    @Test
    void 损坏行被跳过() throws IOException {
        Path file = tempDir.resolve("trusted_viewers");
        Files.write(file, ("garbage line\n" + FP_A + "\t好主控\t1000\n\n").getBytes(StandardCharsets.UTF_8));
        TrustStore store = new TrustStore(file);
        assertTrue(store.isTrusted(FP_A), "合法行应可解析");
        assertEquals(1, store.entries().size(), "损坏行与空行应被跳过");
        assertEquals("好主控", store.entries().get(0).name());
        assertEquals(1000, store.entries().get(0).trustedAtMillis());
    }
}
