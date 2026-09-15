package com.gudesk.host.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HostPasswordStore} 单元测试：哈希存储、生成、加载、验证与损坏恢复。
 */
class HostPasswordStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 设置并验证密码() throws IOException {
        Path file = tempDir.resolve("password");
        HostPasswordStore.PasswordRecord record = HostPasswordStore.setPassword(file, "123789");
        assertTrue(HostPasswordStore.verify("123789", record));
        assertFalse(HostPasswordStore.verify("123780", record));
        assertFalse(HostPasswordStore.verify("", record));
        assertFalse(HostPasswordStore.verify(null, record));
        assertTrue(Files.exists(file), "密码文件应已写入");
    }

    @Test
    void 首次加载自动生成六位数字密码() throws IOException {
        Path file = tempDir.resolve("password");
        HostPasswordStore.LoadResult result = HostPasswordStore.loadOrGenerate(file);
        assertTrue(result.newlyGenerated(), "首次加载应生成新密码");
        String plain = result.generatedPlainPassword();
        assertNotNull(plain);
        assertTrue(plain.matches("[0-9]{6}"), "随机密码应为 6 位数字: " + plain);
        assertTrue(HostPasswordStore.verify(plain, result.record()), "生成密码应能通过验证");

        // 磁盘仅存哈希（v1 + salt_hex + hash_hex），不存明文
        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        assertEquals("v1", lines.get(0).trim());
        assertFalse(lines.contains(plain), "文件不得包含明文密码");
    }

    @Test
    void 再次加载返回已存记录() throws IOException {
        Path file = tempDir.resolve("password");
        HostPasswordStore.LoadResult first = HostPasswordStore.loadOrGenerate(file);
        HostPasswordStore.LoadResult second = HostPasswordStore.loadOrGenerate(file);
        assertFalse(second.newlyGenerated(), "文件已存在不应再生成");
        assertNull(second.generatedPlainPassword());
        assertArrayEquals(first.record().salt(), second.record().salt(), "盐应不变");
        assertArrayEquals(first.record().hash(), second.record().hash(), "哈希应不变");
    }

    @Test
    void 修改密码后旧密码失效() throws IOException {
        Path file = tempDir.resolve("password");
        HostPasswordStore.PasswordRecord old = HostPasswordStore.setPassword(file, "111111");
        HostPasswordStore.PasswordRecord updated = HostPasswordStore.setPassword(file, "222222");
        assertFalse(HostPasswordStore.verify("111111", updated), "旧密码应失效");
        assertTrue(HostPasswordStore.verify("222222", updated));
        // 旧 record 独立于新存储（内存记录），文件已被覆盖
        assertFalse(Files.readAllLines(file).isEmpty());
        // 旧内存记录自身仍自洽
        assertTrue(HostPasswordStore.verify("111111", old));
    }

    @Test
    void 损坏文件重新生成() throws IOException {
        Path file = tempDir.resolve("password");
        Files.write(file, List.of("garbage", "not-hex"));
        HostPasswordStore.LoadResult result = HostPasswordStore.loadOrGenerate(file);
        assertTrue(result.newlyGenerated(), "损坏文件应重新生成");
        assertNotNull(result.generatedPlainPassword());
        assertTrue(HostPasswordStore.verify(result.generatedPlainPassword(), result.record()));
    }
}
