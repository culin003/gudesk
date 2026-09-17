package com.gudesk.host.capture;

import com.gudesk.common.spi.AdapterException;
import com.gudesk.host.portal.PortalControlMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PortalScreenCapturer} 可离线测试的部分：帧头解析、helper 路径解析、
 * Wayland SPI 偏好设置、控制消息 fd 交接（真实 unix socket 对，不依赖 portal 环境）。
 * 真实 portal 会话链路属联调范畴（会弹授权对话框）。
 */
class PortalScreenCapturerTest {

    @TempDir
    Path tempDir;

    private String savedSysprop;

    @BeforeEach
    void saveSysprop() {
        savedSysprop = System.getProperty("gudesk.adapter.capturer");
        System.clearProperty("gudesk.adapter.capturer");
    }

    @AfterEach
    void restoreSysprop() {
        if (savedSysprop == null) {
            System.clearProperty("gudesk.adapter.capturer");
        } else {
            System.setProperty("gudesk.adapter.capturer", savedSysprop);
        }
    }

    // ------------------------------------------------------------------
    // 帧头解析（与 portal_helper.c 的 gd_frame_header 布局对应）
    // ------------------------------------------------------------------

    @Test
    void 帧头解析_合法帧字段正确() {
        long pts = 987654321L;
        int width = 1920;
        int height = 1080;
        byte[] header = encodeFrameHeader(pts, width, height, 0x1);

        PortalScreenCapturer.FrameHeader parsed = PortalScreenCapturer.parseFrameHeader(header);

        assertEquals(pts, parsed.ptsNs());
        assertEquals(width, parsed.width());
        assertEquals(height, parsed.height());
        assertEquals(width * height * 4, parsed.payloadSize());
        assertTrue(parsed.fullFrame(), "helper 输出恒为全帧（flags bit0）");
    }

    @Test
    void 帧头解析_魔数或版本非法返回null() {
        byte[] badMagic = encodeFrameHeader(1, 4, 4, 1);
        badMagic[8] ^= 0xFF; // magic 首字节（偏移 8）
        assertNull(PortalScreenCapturer.parseFrameHeader(badMagic));

        byte[] badVersion = encodeFrameHeader(1, 4, 4, 1);
        badVersion[12] = 2; // version（偏移 12）
        assertNull(PortalScreenCapturer.parseFrameHeader(badVersion));
    }

    @Test
    void 帧头解析_尺寸与负载不一致返回null() {
        assertNull(PortalScreenCapturer.parseFrameHeader(encodeFrameHeader(1, 0, 4, 0)), "宽度为 0");
        // payload 与 w*h*4 不符（改写帧头末 4 字节的 payloadSize）
        byte[] bad = encodeFrameHeader(1, 4, 4, 1);
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 999);
        assertNull(PortalScreenCapturer.parseFrameHeader(bad));
    }

    /** 按协议构造 28 字节帧头（小端），payloadSize 恒按 w*h*4 计算 */
    private static byte[] encodeFrameHeader(long pts, int width, int height, int flags) {
        ByteBuffer buf = ByteBuffer.allocate(PortalScreenCapturer.FRAME_HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(pts);
        buf.putInt(PortalScreenCapturer.FRAME_MAGIC);
        buf.putShort((short) 1);   // version
        buf.putShort((short) flags);
        buf.putInt(width);
        buf.putInt(height);
        buf.putInt(width * height * 4);
        return buf.array();
    }

    // ------------------------------------------------------------------
    // helper 路径解析
    // ------------------------------------------------------------------

    @Test
    void helper路径_环境变量指定且可执行时采用() throws IOException {
        Path helper = tempDir.resolve("gudesk-portal-helper");
        Files.writeString(helper, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwxr-xr-x"));
        Map<String, String> env = Map.of("GUDESK_PORTAL_HELPER", helper.toString());

        assertEquals(helper, PortalScreenCapturer.resolveHelperPath(env));
    }

    @Test
    void helper路径_指定的文件不存在时报错() {
        Map<String, String> env = Map.of("GUDESK_PORTAL_HELPER",
                tempDir.resolve("nonexistent").toString());

        AdapterException e = assertThrows(AdapterException.class,
                () -> PortalScreenCapturer.resolveHelperPath(env));
        assertTrue(e.getMessage().contains("不存在或不可执行"), e.getMessage());
    }

    @Test
    void helper路径_非可执行文件报错() throws IOException {
        Path helper = tempDir.resolve("not-executable");
        Files.writeString(helper, "data");
        Map<String, String> env = Map.of("GUDESK_PORTAL_HELPER", helper.toString());

        assertThrows(AdapterException.class, () -> PortalScreenCapturer.resolveHelperPath(env));
    }

    // ------------------------------------------------------------------
    // 平台 SPI 选择
    // ------------------------------------------------------------------

    @Test
    void 平台选择_Wayland会话选Portal() {
        Map<String, String> env = new HashMap<>();
        env.put("XDG_SESSION_TYPE", "wayland");

        PortalScreenCapturer.selectPlatformDefault(env);

        assertEquals(PortalScreenCapturer.class.getName(),
                System.getProperty("gudesk.adapter.capturer"));
    }

    @Test
    void 平台选择_用户以环境变量指定时不干预() {
        Map<String, String> env = new HashMap<>();
        env.put("XDG_SESSION_TYPE", "wayland");
        env.put("GUDESK_ADAPTER_CAPTURER", "com.example.Custom");

        PortalScreenCapturer.selectPlatformDefault(env);

        assertNull(System.getProperty("gudesk.adapter.capturer"));
    }

    @Test
    void 平台选择_X11会话显式选Robot() {
        Map<String, String> env = new HashMap<>();
        env.put("XDG_SESSION_TYPE", "x11");
        env.put("DISPLAY", ":0");

        PortalScreenCapturer.selectPlatformDefault(env);

        assertEquals(RobotScreenCapturer.class.getName(),
                System.getProperty("gudesk.adapter.capturer"));
    }

    // ------------------------------------------------------------------
    // 控制消息 fd 交接（真实 AFUNIX socket 对，离线可测）
    // ------------------------------------------------------------------

    @Test
    void fd交接_控制消息与fd经SCM_RIGHTS送达对端() throws Exception {
        Path sockPath = tempDir.resolve("handoff.sock");
        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<byte[]> receivedCtrl = new AtomicReference<>();
        AtomicReference<Integer> receivedFdCount = new AtomicReference<>();

        try (AFUNIXServerSocket server = AFUNIXServerSocket.newInstance()) {
            server.bind(AFUNIXSocketAddress.of(sockPath));
            Thread receiver = new Thread(() -> {
                try (AFUNIXSocket socket = server.accept()) {
                    // junixsocket 接收侧默认丢弃辅助数据，须显式开启接收缓冲
                    // （生产环境的接收方为 C helper，用原生 recvmsg 无此限制）
                    socket.setAncillaryReceiveBufferSize(1024);
                    InputStream in = socket.getInputStream();
                    byte[] ctrl = new byte[PortalControlMessage.ENCODED_SIZE];
                    readFully(in, ctrl);
                    receivedCtrl.set(ctrl);
                    receivedFdCount.set(socket.getReceivedFileDescriptors().length);
                } catch (IOException e) {
                    receivedFdCount.set(-1);
                } finally {
                    accepted.countDown();
                }
            });
            receiver.setDaemon(true);
            receiver.start();

            // 用临时文件的 fd 作为被移交的 fd
            File file = Files.createFile(tempDir.resolve("fd-source")).toFile();
            try (FileInputStream fis = new FileInputStream(file)) {
                int rawFd = new org.freedesktop.dbus.transport.junixsocket.JUnixSocketSocketProvider()
                        .getFileDescriptorValue(fis.getFD())
                        .orElseThrow(() -> new IllegalStateException("无法从 FileDescriptor 取 fd 编号"));
                PortalScreenCapturer.handoffControlMessage(sockPath,
                        new PortalControlMessage(42, 0L), rawFd);
            }

            assertTrue(accepted.await(5, TimeUnit.SECONDS), "对端应在超时前完成接收");
            PortalControlMessage decoded = assertDoesNotThrow(
                    () -> PortalControlMessage.decode(receivedCtrl.get()));
            assertEquals(42, decoded.nodeId());
            assertTrue(receivedFdCount.get() >= 1,
                    "应随 SCM_RIGHTS 收到至少 1 个 fd，实际: " + receivedFdCount.get());
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("EOF 过早");
            }
            off += n;
        }
    }

    // ------------------------------------------------------------------
    // init 环境守卫
    // ------------------------------------------------------------------

    @Test
    void init_非Wayland会话抛异常() {
        // init 内部读取真实环境变量：X11/headless 环境必然触发「仅适用于 Wayland」分支；
        // Wayland 开发机（如本机 KDE）上不做断言（init 会继续走到 helper 路径检查）
        if (RobotScreenCapturer.isWaylandSession(RobotScreenCapturer.currentEnv())) {
            return;
        }
        PortalScreenCapturer capturer = new PortalScreenCapturer();
        AdapterException e = assertThrows(AdapterException.class, () -> capturer.init(
                com.gudesk.common.spi.AdapterConfig.builder().fps(30).build()));
        assertTrue(e.getMessage().contains("仅适用于 Wayland"), e.getMessage());
    }
}
