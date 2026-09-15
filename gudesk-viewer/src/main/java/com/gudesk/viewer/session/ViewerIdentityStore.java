package com.gudesk.viewer.session;

import com.gudesk.common.crypto.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 主控端长期身份密钥存储：X25519 密钥对持久化于 {@code ~/.gudesk/viewer_identity}
 * （与每次连接新生成的临时密钥不同，身份公钥指纹稳定，供被控端信任列表识别本机）。
 *
 * <p>文件格式（3 行，US-ASCII hex）：
 * <pre>
 * v1
 * &lt;private_pkcs8_hex&gt;
 * &lt;public_x509_hex&gt;
 * </pre>
 *
 * <p>文件不存在时自动生成并写入；损坏时覆盖重生成（旧身份作废，
 * 被控端信任列表按新指纹重新确认）。
 */
public final class ViewerIdentityStore {

    private static final Logger LOG = LoggerFactory.getLogger(ViewerIdentityStore.class);

    /** 存储格式版本行 */
    static final String FORMAT_VERSION = "v1";

    /** 默认存储文件：~/.gudesk/viewer_identity */
    public static final Path DEFAULT_FILE = Path.of(
            System.getProperty("user.home"), ".gudesk", "viewer_identity");

    private static final HexFormat HEX = HexFormat.of();

    private ViewerIdentityStore() {
    }

    /**
     * 加载默认存储文件；不存在（或损坏）时生成新 X25519 身份密钥对写入。
     */
    public static KeyPair loadOrGenerate() throws IOException {
        return loadOrGenerate(DEFAULT_FILE);
    }

    /**
     * 加载指定文件；不存在（或损坏）时生成新密钥对写入。
     */
    public static KeyPair loadOrGenerate(Path file) throws IOException {
        if (Files.exists(file)) {
            KeyPair loaded = read(file);
            if (loaded != null) {
                return loaded;
            }
            LOG.warn("身份密钥文件损坏，重新生成（旧指纹作废）: {}", file);
        }
        KeyPair generated;
        try {
            generated = CryptoUtil.generateX25519KeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("生成身份密钥对失败", e);
        }
        write(file, generated);
        return generated;
    }

    // ------------------------------------------------------------------

    /** 读取并解析；格式非法返回 null */
    private static KeyPair read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
        if (lines.size() < 3 || !FORMAT_VERSION.equals(lines.get(0).trim())) {
            return null;
        }
        try {
            byte[] privateBytes = HEX.parseHex(lines.get(1).trim());
            byte[] publicBytes = HEX.parseHex(lines.get(2).trim());
            KeyFactory factory = KeyFactory.getInstance("X25519");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicBytes));
            return new KeyPair(publicKey, privateKey);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
    }

    /** 写入文件（权限 600） */
    private static void write(Path file, KeyPair keyPair) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = List.of(FORMAT_VERSION,
                HEX.formatHex(keyPair.getPrivate().getEncoded()),
                HEX.formatHex(keyPair.getPublic().getEncoded()));
        Files.write(file, lines, StandardCharsets.US_ASCII);
        restrictPermissions(file);
        LOG.info("主控端身份密钥已生成: {}（指纹见连接输出）", file);
    }

    /** POSIX 权限 rw-------（非 POSIX 文件系统由 OS 默认策略管理） */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等：不支持 POSIX 权限
        } catch (IOException e) {
            // 权限收紧失败不阻断
        }
    }
}
