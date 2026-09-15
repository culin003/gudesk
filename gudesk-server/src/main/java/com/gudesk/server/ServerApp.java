package com.gudesk.server;

import com.gudesk.common.proto.GuDeskProto.ConnectAccept;
import com.gudesk.common.proto.GuDeskProto.ConnectRequest;
import com.gudesk.common.proto.GuDeskProto.Heartbeat;
import com.gudesk.common.proto.GuDeskProto.HeartbeatAck;
import com.gudesk.common.proto.GuDeskProto.PunchCandidate;
import com.gudesk.common.proto.GuDeskProto.RegisterRequest;
import com.gudesk.common.proto.GuDeskProto.RegisterResponse;
import com.gudesk.common.net.StunClient;
import com.gudesk.common.proto.GuDeskProto.SignalingEnvelope;
import com.gudesk.server.relay.RelayServer;
import com.gudesk.server.signaling.BlockingFrame;
import com.gudesk.server.signaling.SignalingServer;
import com.gudesk.server.stun.StunServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * GuDesk 服务器启动入口：信令服务器 + 内建 STUN + 中继服务器三件套。
 *
 * <p>默认端口：信令 TCP 48900、STUN UDP 3478、中继 TCP 48910。
 * 参数：--signaling-port/--stun-port/--relay-port 指定端口，--selftest 起三件套并本地自测。
 */
public final class ServerApp {

    private static final Logger log = LoggerFactory.getLogger(ServerApp.class);

    private static final String VERSION = "0.1.0-SNAPSHOT";

    private ServerApp() {
    }

    public static void main(String[] args) {
        int signalingPort = SignalingServer.DEFAULT_PORT;
        int stunPort = StunServer.DEFAULT_PORT;
        int relayPort = RelayServer.DEFAULT_PORT;
        boolean selftest = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--signaling-port" -> signalingPort = Integer.parseInt(args[++i]);
                case "--stun-port" -> stunPort = Integer.parseInt(args[++i]);
                case "--relay-port" -> relayPort = Integer.parseInt(args[++i]);
                case "--selftest" -> selftest = true;
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    System.err.println("用法: java -jar gudesk-server.jar [--signaling-port N] [--stun-port N] [--relay-port N] [--selftest]");
                    System.exit(2);
                }
            }
        }

        if (selftest) {
            System.exit(runSelftest() ? 0 : 1);
        }

        System.out.printf("GuDesk Server v%s, Java: %s, OS: %s%n",
                VERSION, System.getProperty("java.version"), System.getProperty("os.name"));

        try (SignalingServer signaling = new SignalingServer(signalingPort);
             StunServer stun = new StunServer(stunPort);
             RelayServer relay = new RelayServer(relayPort)) {
            signaling.start();
            stun.start();
            relay.start();

            System.out.println("GuDesk 服务器已启动：");
            System.out.printf("  信令服务器: TCP %s:%d%n", "0.0.0.0", signaling.getPort());
            System.out.printf("  STUN 服务器: UDP %s:%d%n", "0.0.0.0", stun.getPort());
            System.out.printf("  中继服务器: TCP %s:%d%n", "0.0.0.0", relay.getPort());
            System.out.printf("  本机 IP: %s%n", String.join(", ", localIpv4Addresses()));
            System.out.println("按 Ctrl+C 停止");

            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown));
            shutdown.await();
        } catch (Exception e) {
            log.error("服务器启动失败", e);
            System.exit(1);
        }
    }

    /** 枚举本机非回环 IPv4 地址（找不到时回退 127.0.0.1）。 */
    private static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        result.add(addr.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
            // 枚举失败回退
        }
        if (result.isEmpty()) {
            result.add("127.0.0.1");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // selftest：loopback 起三件套并逐项自测
    // ------------------------------------------------------------------
    private static boolean runSelftest() {
        System.out.println("== GuDesk Server selftest ==");
        try (SignalingServer signaling = new SignalingServer(0);
             StunServer stun = new StunServer(0);
             RelayServer relay = new RelayServer(0)) {
            signaling.start();
            stun.start();
            relay.start();
            System.out.printf("[启动] 信令 TCP %d / STUN UDP %d / 中继 TCP %d%n",
                    signaling.getPort(), stun.getPort(), relay.getPort());

            testStun(stun.getPort());
            String relayToken = testSignaling(signaling.getPort());
            testRelay(relay.getPort(), relayToken);

            System.out.println("SELFTEST OK");
            return true;
        } catch (Exception e) {
            System.err.println("SELFTEST FAILED: " + e);
            e.printStackTrace(System.err);
            return false;
        }
    }

    /** STUN 自测：binding 请求得到 127.0.0.1:本地端口 的映射。 */
    private static void testStun(int port) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetSocketAddress mapped = StunClient.sendBindingRequest(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), socket, 3000);
            if (!mapped.getAddress().equals(InetAddress.getLoopbackAddress())
                    || mapped.getPort() != socket.getLocalPort()) {
                throw new IOException("STUN 映射地址不符: " + mapped + "，期望 127.0.0.1:" + socket.getLocalPort());
            }
            System.out.printf("[STUN] OK: %s%n", mapped.getAddress().getHostAddress() + ":" + mapped.getPort());
        }
    }

    /** 信令自测：注册/心跳/连接请求路由全路径（接受），返回注入的 relay_token。 */
    private static String testSignaling(int port) throws IOException {
        try (Socket hostSocket = new Socket("127.0.0.1", port);
             Socket viewerSocket = new Socket("127.0.0.1", port)) {
            hostSocket.setTcpNoDelay(true);
            viewerSocket.setTcpNoDelay(true);
            hostSocket.setSoTimeout(5000);
            viewerSocket.setSoTimeout(5000);
            SignalingClient host = new SignalingClient(hostSocket);
            SignalingClient viewer = new SignalingClient(viewerSocket);

            // 注册（双方均注册，服务器始终分配新 ID；主控端 id 留空复用同一机制）
            host.write(SignalingEnvelope.newBuilder()
                    .setRegisterRequest(RegisterRequest.newBuilder()
                            .setPublicKey(com.google.protobuf.ByteString.copyFromUtf8("host-pk-selftest")))
                    .build());
            viewer.write(SignalingEnvelope.newBuilder()
                    .setRegisterRequest(RegisterRequest.newBuilder())
                    .build());
            RegisterResponse hostResp = host.read().getRegisterResponse();
            RegisterResponse viewerResp = viewer.read().getRegisterResponse();
            String hostId = hostResp.getAssignedId();
            String viewerId = viewerResp.getAssignedId();

            // 心跳
            host.write(SignalingEnvelope.newBuilder()
                    .setHeartbeat(Heartbeat.newBuilder().setTimestamp(System.currentTimeMillis()))
                    .build());
            host.readHeartbeatAck();

            // 连接请求：viewer -> host
            viewer.write(SignalingEnvelope.newBuilder()
                    .setConnectRequest(ConnectRequest.newBuilder()
                            .setTargetId(hostId)
                            .setViewerPublicKey(com.google.protobuf.ByteString.copyFromUtf8("viewer-pk-selftest"))
                            .addCandidates(PunchCandidate.newBuilder().setAddress("127.0.0.1:19999")))
                    .build());
            SignalingEnvelope forward = host.read();
            if (forward.getPayloadCase() != SignalingEnvelope.PayloadCase.CONNECT_FORWARD
                    || !forward.getConnectForward().getFromId().equals(viewerId)) {
                throw new IOException("ConnectForward 不符: " + forward.getPayloadCase());
            }

            // 被控端接受 → 服务器注入 relay_token 转发回 viewer
            host.write(SignalingEnvelope.newBuilder()
                    .setConnectAccept(ConnectAccept.newBuilder()
                            .setHostPublicKey(com.google.protobuf.ByteString.copyFromUtf8("host-pk-selftest")))
                    .build());
            SignalingEnvelope accept = viewer.read();
            String relayToken = accept.getConnectAccept().getRelayToken();
            if (relayToken.isEmpty()) {
                throw new IOException("relay_token 未注入");
            }
            System.out.printf("[信令] OK: host=%s viewer=%s relay_token=%s%n", hostId, viewerId, relayToken);
            return relayToken;
        }
    }

    /** 中继自测：token 配对 + 双向透传。 */
    private static void testRelay(int port, String token) throws IOException {
        try (Socket socketA = new Socket("127.0.0.1", port);
             Socket socketB = new Socket("127.0.0.1", port)) {
            socketA.setTcpNoDelay(true);
            socketB.setTcpNoDelay(true);
            OutputStream outA = socketA.getOutputStream();
            OutputStream outB = socketB.getOutputStream();
            // 首行 token 配对
            outA.write((token + "\n").getBytes(StandardCharsets.UTF_8));
            outA.flush();
            outA.write("relay-ping-123".getBytes(StandardCharsets.UTF_8)); // 提前数据（B 未到）
            outA.flush();
            outB.write((token + "\n").getBytes(StandardCharsets.UTF_8));
            outB.flush();
            outB.write("relay-pong-456".getBytes(StandardCharsets.UTF_8));
            outB.flush();

            String aToB = readLineBytes(socketB.getInputStream(), "relay-ping-123".length());
            String bToA = readLineBytes(socketA.getInputStream(), "relay-pong-456".length());
            if (!"relay-ping-123".equals(aToB) || !"relay-pong-456".equals(bToA)) {
                throw new IOException("中继透传数据不符: A->B=" + aToB + ", B->A=" + bToA);
            }
            System.out.println("[中继] OK: 双向透传一致");
        }
    }

    /** 从输入流精确读 n 个字节并以 UTF-8 解码。 */
    private static String readLineBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("连接提前关闭");
            }
            off += r;
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("读取超时");
            }
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    /** selftest 用的极简阻塞信令客户端（soTimeout 由构造前设置）。 */
    private record SignalingClient(Socket socket) {

        void write(SignalingEnvelope envelope) throws IOException {
            BlockingFrame.writeEnvelope(socket.getOutputStream(), envelope);
            socket.getOutputStream().flush();
        }

        SignalingEnvelope read() throws IOException {
            SignalingEnvelope envelope = BlockingFrame.readEnvelope(socket.getInputStream());
            if (envelope == null) {
                throw new IOException("连接已关闭");
            }
            return envelope;
        }

        HeartbeatAck readHeartbeatAck() throws IOException {
            SignalingEnvelope envelope = read();
            if (envelope.getPayloadCase() != SignalingEnvelope.PayloadCase.HEARTBEAT_ACK) {
                throw new IOException("期望 HeartbeatAck，实际 " + envelope.getPayloadCase());
            }
            return envelope.getHeartbeatAck();
        }
    }
}
