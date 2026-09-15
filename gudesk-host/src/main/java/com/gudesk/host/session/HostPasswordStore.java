package com.gudesk.host.session;

import com.gudesk.common.crypto.CryptoUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 被控端密码存储：{@code ~/.gudesk/password} 文件持久化 PBKDF2 哈希（不存明文）。
 *
 * <p>文件格式（3 行，US-ASCII）：
 * <pre>
 * v1
 * &lt;salt_hex&gt;   16 字节随机盐
 * &lt;hash_hex&gt;   PBKDF2(password, salt)，256bit
 * </pre>
 *
 * <p>验证模型（SSH 密码认证等价）：主控端在 ECDH 密钥建立后的密文通道内提交明文密码，
 * 被控端以 {@link #verify} 与本地哈希做常量时间比对——磁盘上始终只有哈希，
 * 明文仅存在于两端内存与加密线路上。
 *
 * <p>文件不存在时自动生成随机 6 位数字密码并写入（首次生成时明文返回给调用方，
 * 供打印/弹窗一次性告知用户）；{@code --set-password} 修改密码走 {@link #setPassword}。
 */
public final class HostPasswordStore {

    /** 存储格式版本行 */
    static final String FORMAT_VERSION = "v1";
    /** 随机密码长度（位数字符） */
    static final int RANDOM_PASSWORD_LENGTH = 6;
    /** PBKDF2 盐长度（字节） */
    static final int SALT_LENGTH = 16;
    /** PBKDF2 哈希长度（字节，256bit） */
    static final int HASH_LENGTH = 32;

    /** 默认存储文件：~/.gudesk/password */
    public static final Path DEFAULT_FILE = Path.of(
            System.getProperty("user.home"), ".gudesk", "password");

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 已加载的口令验证记录（盐 + PBKDF2 哈希，不含明文） */
    public record PasswordRecord(byte[] salt, byte[] hash) {
    }

    /**
     * 加载/生成结果。
     *
     * @param generatedPlainPassword 首次生成时的明文密码（仅本次进程可见，用于一次性告知）；
     *                               文件已存在时为 null
     */
    public record LoadResult(PasswordRecord record, String generatedPlainPassword) {

        public boolean newlyGenerated() {
            return generatedPlainPassword != null;
        }
    }

    private HostPasswordStore() {
    }

    /**
     * 加载默认存储文件；不存在（或损坏）时生成随机 6 位数字密码写入。
     */
    public static LoadResult loadOrGenerate() throws IOException {
        return loadOrGenerate(DEFAULT_FILE);
    }

    /**
     * 加载指定文件；不存在（或损坏）时生成随机 6 位数字密码写入。
     */
    public static LoadResult loadOrGenerate(Path file) throws IOException {
        if (Files.exists(file)) {
            PasswordRecord record = readRecord(file);
            if (record != null) {
                return new LoadResult(record, null);
            }
            // 损坏文件：覆盖重生成（旧密码已不可恢复，等同于重置）
        }
        String plain = generateRandomPassword();
        PasswordRecord record = writePassword(file, plain);
        return new LoadResult(record, plain);
    }

    /**
     * 设置/修改密码（--set-password 子命令）。
     */
    public static PasswordRecord setPassword(String plainPassword) throws IOException {
        return setPassword(DEFAULT_FILE, plainPassword);
    }

    /**
     * 写入指定文件。
     */
    public static PasswordRecord setPassword(Path file, String plainPassword) throws IOException {
        return writePassword(file, plainPassword);
    }

    /**
     * 验证明文密码（常量时间比对 PBKDF2 哈希）。
     */
    public static boolean verify(String plainPassword, PasswordRecord record) {
        if (plainPassword == null || plainPassword.isEmpty() || record == null) {
            return false;
        }
        try {
            return CryptoUtil.verifyPassword(plainPassword, record.salt(), record.hash());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** 生成随机 6 位数字密码 */
    static String generateRandomPassword() {
        StringBuilder sb = new StringBuilder(RANDOM_PASSWORD_LENGTH);
        for (int i = 0; i < RANDOM_PASSWORD_LENGTH; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** 读取并解析存储文件；格式非法返回 null */
    private static PasswordRecord readRecord(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        if (lines.size() < 3 || !FORMAT_VERSION.equals(lines.get(0).trim())) {
            return null;
        }
        try {
            byte[] salt = HEX.parseHex(lines.get(1).trim());
            byte[] hash = HEX.parseHex(lines.get(2).trim());
            if (salt.length != SALT_LENGTH || hash.length != HASH_LENGTH) {
                return null;
            }
            return new PasswordRecord(salt, hash);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 计算哈希并写入文件（权限 600） */
    private static PasswordRecord writePassword(Path file, String plainPassword) throws IOException {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        byte[] hash;
        try {
            hash = CryptoUtil.hashPassword(plainPassword, salt);
        } catch (GeneralSecurityException e) {
            throw new IOException("PBKDF2 计算失败", e);
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = List.of(FORMAT_VERSION, HEX.formatHex(salt), HEX.formatHex(hash));
        Files.write(file, lines, StandardCharsets.US_ASCII);
        restrictPermissions(file);
        return new PasswordRecord(salt, hash);
    }

    /** POSIX 权限 rw-------（非 POSIX 文件系统由 OS 默认策略管理） */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等：不支持 POSIX 权限
        } catch (IOException e) {
            // 权限收紧失败不阻断启动（文件在用户主目录下，默认权限通常已受限）
        }
    }
}
