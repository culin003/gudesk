package com.gudesk.host.portal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PortalTokenStore} 单测：读写往返、坏文件/缺失容错、原子覆盖、
 * 权限收紧、后端标识归一化。全部离线（@TempDir）。
 */
class PortalTokenStoreTest {

    @TempDir
    Path tmpDir;

    private Path file() {
        return tmpDir.resolve("portal_token");
    }

    @Test
    void 保存后读取_字段往返一致() throws Exception {
        PortalTokenStore store = new PortalTokenStore(file());
        store.save("kde", "restore-token-abc/def==");

        PortalTokenStore.StoredToken loaded = store.load();
        assertNotNull(loaded);
        assertEquals("kde", loaded.backendId());
        assertEquals("restore-token-abc/def==", loaded.token(), "token 含特殊字符应原样保留");
        assertTrue(loaded.issuedAtMillis() > 0);
    }

    @Test
    void 文件不存在_返回null() {
        PortalTokenStore store = new PortalTokenStore(file());
        assertNull(store.load());
    }

    @Test
    void 损坏文件_返回null容错() throws Exception {
        Files.writeString(file(), "不是四列格式\n\t\t\t\n", StandardCharsets.UTF_8);
        PortalTokenStore store = new PortalTokenStore(file());
        assertNull(store.load());
    }

    @Test
    void 旧版本或坏时间戳_跳过损坏行返回null() throws Exception {
        // 版本号非 v1：跳过
        Files.writeString(file(), "v0\tkde\t123\ttoken\n", StandardCharsets.UTF_8);
        assertNull(new PortalTokenStore(file()).load());
    }

    @Test
    void 重复保存_覆盖为最新token() throws Exception {
        PortalTokenStore store = new PortalTokenStore(file());
        store.save("kde", "first-token");
        store.save("kde", "second-token");
        assertEquals("second-token", store.load().token(), "再次保存应覆盖旧 token");
    }

    @Test
    void 保存后文件权限为rwOwner() throws Exception {
        PortalTokenStore store = new PortalTokenStore(file());
        store.save("kde", "token");
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file());
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ), "组不可读");
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ), "其他不可读");
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统：跳过权限断言
        }
    }

    @Test
    void 空token或空后端标识_拒绝保存() throws Exception {
        PortalTokenStore store = new PortalTokenStore(file());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.save("", "token"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.save("kde", "  "));
        assertNull(store.load(), "非法保存不应落盘");
    }

    @Test
    void 后端标识归一化_取桌面再回退会话类型再unknown() {
        assertEquals("kde", PortalTokenStore.currentBackendId(
                Map.of("XDG_CURRENT_DESKTOP", "KDE")));
        assertEquals("kde:wayland", PortalTokenStore.currentBackendId(
                Map.of("XDG_CURRENT_DESKTOP", "  KDE:Wayland  ")), "应小写去空白");
        // 桌面缺失回退会话类型
        assertEquals("wayland", PortalTokenStore.currentBackendId(
                Map.of("XDG_SESSION_TYPE", "wayland")));
        // 都缺失
        assertEquals("unknown", PortalTokenStore.currentBackendId(Map.of()));
    }
}
