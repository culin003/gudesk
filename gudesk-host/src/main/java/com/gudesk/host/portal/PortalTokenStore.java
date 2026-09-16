package com.gudesk.host.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Portal restore token 跨进程持久化：{@code ~/.gudesk/portal_token} 单行文件。
 *
 * <p>文件格式（TAB 分隔 4 列，token 取第 3 个 TAB 之后的剩余整行，容忍特殊字符）：
 * <pre>v1&lt;TAB&gt;后端标识&lt;TAB&gt;签发时间epoch毫秒&lt;TAB&gt;token</pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>后端标识</b>（{@link #currentBackendId}）：XDG_CURRENT_DESKTOP 归一化值，
 *       换桌面环境（KDE→GNOME 等）时 portal 后端随之更换、旧 token 必然失效，
 *       调用方比对标识跳过无谓的恢复尝试；</li>
 *   <li><b>原子写入</b>：临时文件 + ATOMIC_MOVE 替换，崩溃不产生半截文件；</li>
 *   <li><b>权限</b>：POSIX rw-------（与 password/trusted_viewers 一致）；token 不加密
 *       ——它与用户会话同信任域（能读该文件即可直接连 portal），加密无增益；</li>
 *   <li><b>容错</b>：读失败/格式坏返回 null（fail-open 到正常弹窗流程，不阻断）。</li>
 * </ul>
 */
public final class PortalTokenStore {

    private static final Logger LOG = LoggerFactory.getLogger(PortalTokenStore.class);

    /** 默认存储文件：~/.gudesk/portal_token */
    public static final Path DEFAULT_FILE = Path.of(
            System.getProperty("user.home"), ".gudesk", "portal_token");

    /** 文件格式版本（首列） */
    private static final String FORMAT_VERSION = "v1";

    /** 已存 token：后端标识 + 签发时间 + token 本体 */
    public record StoredToken(String backendId, long issuedAtMillis, String token) {
    }

    private final Path file;

    public PortalTokenStore() {
        this(DEFAULT_FILE);
    }

    /** @param file 存储文件（测试可指向临时目录） */
    public PortalTokenStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Path file() {
        return file;
    }

    /**
     * 读取已存 token。
     *
     * @return 已存 token；文件不存在/损坏/读取失败返回 null
     */
    public StoredToken load() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 4);
                if (parts.length < 4 || !FORMAT_VERSION.equals(parts[0])
                        || parts[1].isBlank() || parts[3].isBlank()) {
                    continue; // 损坏行：跳过
                }
                long issuedAt = 0;
                try {
                    issuedAt = Long.parseLong(parts[2].trim());
                } catch (NumberFormatException ignored) {
                    // 损坏时间戳：置 0（不影响 token 使用）
                }
                return new StoredToken(parts[1].trim(), issuedAt, parts[3]);
            }
            return null;
        } catch (IOException e) {
            LOG.warn("portal_token 读取失败，按无已存 token 处理（fail-open）: {}", String.valueOf(e));
            return null;
        }
    }

    /**
     * 保存 token（原子替换；权限收紧失败不阻断）。
     *
     * @param backendId 签发时 {@link #currentBackendId} 的值
     * @param token     portal 签发的 restore token（不可为空白）
     */
    public void save(String backendId, String token) throws IOException {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId 不能为空");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 不能为空");
        }
        String content = FORMAT_VERSION + "\t" + backendId + "\t"
                + System.currentTimeMillis() + "\t" + token + "\n";
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, "portal_token-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            restrictPermissions(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 当前环境的后端标识：XDG_CURRENT_DESKTOP 归一化（小写、去空白），
     * 缺失时回退 XDG_SESSION_TYPE，再缺失返回 "unknown"。
     * 仅作失效预判标识（换桌面即换 portal 后端），不追求精确探测。
     */
    public static String currentBackendId(Map<String, String> env) {
        String desktop = env.get("XDG_CURRENT_DESKTOP");
        if (desktop != null && !desktop.isBlank()) {
            return desktop.trim().toLowerCase(Locale.ROOT);
        }
        String sessionType = env.get("XDG_SESSION_TYPE");
        if (sessionType != null && !sessionType.isBlank()) {
            return sessionType.trim().toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    /** POSIX 权限 rw-------（非 POSIX 文件系统由 OS 默认策略管理；失败不阻断） */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等：不支持 POSIX 权限
        } catch (IOException e) {
            LOG.debug("portal_token 权限收紧失败（忽略）: {}", e.getMessage());
        }
    }
}
