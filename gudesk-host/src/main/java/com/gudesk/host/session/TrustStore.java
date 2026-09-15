package com.gudesk.host.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 被控端信任列表："始终信任"的主控端按长期身份公钥指纹记录，
 * 持久化于 {@code ~/.gudesk/trusted_viewers}（显式 UTF-8）。
 *
 * <p>文件格式（每行 3 列，TAB 分隔）：
 * <pre>&lt;公钥指纹&gt;\t&lt;备注名&gt;\t&lt;信任时间 epoch 毫秒&gt;</pre>
 *
 * <p>指纹由被控端对 SessionNegotiate.viewer_identity_public_key 计算
 * （SHA-256 冒号分组十六进制，见 {@code CryptoUtil#fingerprint}）；
 * 命中信任列表的主控端在密码验证通过后跳过授权确认弹窗。
 */
public final class TrustStore {

    private static final Logger LOG = LoggerFactory.getLogger(TrustStore.class);

    /** 默认存储文件：~/.gudesk/trusted_viewers */
    public static final Path DEFAULT_FILE = Path.of(
            System.getProperty("user.home"), ".gudesk", "trusted_viewers");

    /** 信任条目：指纹 + 备注名 + 信任时间 */
    public record TrustedViewer(String fingerprint, String name, long trustedAtMillis) {
    }

    private final Path file;

    public TrustStore() {
        this(DEFAULT_FILE);
    }

    /** @param file 信任列表文件（测试可指向临时目录） */
    public TrustStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Path file() {
        return file;
    }

    /**
     * 指纹是否在信任列表中（大小写不敏感；读文件失败时 fail-closed 返回 false）。
     */
    public synchronized boolean isTrusted(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        String target = fingerprint.trim();
        for (TrustedViewer entry : readEntries()) {
            if (entry.fingerprint().equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 加入/更新信任（同指纹更新备注并保留首次信任时间；跨实例持久化）。
     */
    public synchronized void trust(String fingerprint, String name) throws IOException {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("指纹不能为空");
        }
        String fp = fingerprint.trim();
        List<TrustedViewer> entries = readEntries();
        List<TrustedViewer> updated = new ArrayList<>(entries.size() + 1);
        boolean replaced = false;
        for (TrustedViewer entry : entries) {
            if (entry.fingerprint().equalsIgnoreCase(fp)) {
                updated.add(new TrustedViewer(fp, sanitizeName(name), entry.trustedAtMillis()));
                replaced = true;
            } else {
                updated.add(entry);
            }
        }
        if (!replaced) {
            updated.add(new TrustedViewer(fp, sanitizeName(name), System.currentTimeMillis()));
        }
        writeEntries(updated);
    }

    /**
     * 移除信任；返回是否确有移除。
     */
    public synchronized boolean remove(String fingerprint) throws IOException {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        String target = fingerprint.trim();
        List<TrustedViewer> entries = readEntries();
        List<TrustedViewer> updated = new ArrayList<>(entries.size());
        boolean removed = false;
        for (TrustedViewer entry : entries) {
            if (entry.fingerprint().equalsIgnoreCase(target)) {
                removed = true;
            } else {
                updated.add(entry);
            }
        }
        if (removed) {
            writeEntries(updated);
        }
        return removed;
    }

    /** 全部条目快照（文件不存在或损坏行为空/部分） */
    public synchronized List<TrustedViewer> entries() {
        return readEntries();
    }

    // ------------------------------------------------------------------

    private List<TrustedViewer> readEntries() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<TrustedViewer> entries = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length < 3 || parts[0].isBlank()) {
                    continue; // 损坏行（非 3 列格式）：跳过
                }
                String name = parts.length > 1 ? parts[1] : "";
                long trustedAt = 0;
                if (parts.length > 2) {
                    try {
                        trustedAt = Long.parseLong(parts[2].trim());
                    } catch (NumberFormatException ignored) {
                        // 损坏时间戳：置 0
                    }
                }
                entries.add(new TrustedViewer(parts[0].trim(), name, trustedAt));
            }
            return entries;
        } catch (IOException e) {
            LOG.warn("信任列表读取失败，按未信任处理（fail-closed）: {}", String.valueOf(e));
            return new ArrayList<>();
        }
    }

    private void writeEntries(List<TrustedViewer> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (TrustedViewer entry : entries) {
            sb.append(entry.fingerprint()).append('\t')
                    .append(entry.name()).append('\t')
                    .append(entry.trustedAtMillis()).append('\n');
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        restrictPermissions(file);
    }

    /** 备注名中的分隔符/换行替换为空格，防文件格式损坏 */
    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** POSIX 权限 rw-------（非 POSIX 文件系统由 OS 默认策略管理） */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等：不支持 POSIX 权限
        } catch (IOException e) {
            // 权限收紧失败不阻断（文件在用户主目录下）
        }
    }
}
