package com.gudesk.common.net;

import com.gudesk.common.proto.GuDeskProto.PunchCandidate;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * P2P 连接候选收集与编码：本机网络接口地址（含回环，本机联调用）+ STUN 公网映射，
 * 候选字符串格式 {@code scheme/ip:port}（如 {@code udp/192.168.1.5:39000}）。
 *
 * <p>MVP 仅 IPv4。
 */
public final class TransportCandidates {

    /** UDP（打洞）候选前缀 */
    public static final String SCHEME_UDP = "udp";
    /** TCP（直连）候选前缀 */
    public static final String SCHEME_TCP = "tcp";
    /** STUN 查询默认超时（毫秒） */
    public static final int STUN_TIMEOUT_MS = 2_000;

    private TransportCandidates() {
    }

    /**
     * 本机所有可用 IPv4 地址（回环 + 非回环、非点对点接口）配指定端口。
     * 含 127.0.0.1 以支持同机联调。
     */
    public static List<InetSocketAddress> localAddresses(int port) {
        List<InetSocketAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isPointToPoint()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        result.add(new InetSocketAddress(addr, port));
                    }
                }
            }
        } catch (SocketException ignored) {
            // 枚举失败回退仅回环
        }
        result.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        return result;
    }

    /**
     * 对指定 socket 做 STUN Binding 查询公网映射（UDP 打洞公网候选）。
     *
     * @return 映射地址；服务器不可达/超时返回 null（公网候选缺失不致命）
     */
    public static InetSocketAddress stunLookup(DatagramSocket socket, InetSocketAddress stunServer) {
        try {
            return StunClient.sendBindingRequest(stunServer, socket, STUN_TIMEOUT_MS);
        } catch (IOException e) {
            return null;
        }
    }

    /** 地址 → 候选字符串（{@code scheme/ip:port}） */
    public static String encode(String scheme, InetSocketAddress address) {
        return scheme + "/" + address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    /** 地址列表 → 去重候选字符串列表 */
    public static List<String> encodeAll(String scheme, List<InetSocketAddress> addresses) {
        List<String> result = new ArrayList<>();
        for (InetSocketAddress address : addresses) {
            String candidate = encode(scheme, address);
            if (!result.contains(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * 候选字符串 → 地址；scheme 不匹配或格式非法返回 null。
     */
    public static InetSocketAddress decode(String candidate, String scheme) {
        if (candidate == null || !candidate.startsWith(scheme + "/")) {
            return null;
        }
        String rest = candidate.substring(scheme.length() + 1);
        int colon = rest.lastIndexOf(':');
        if (colon <= 0 || colon == rest.length() - 1) {
            return null;
        }
        try {
            String host = rest.substring(0, colon);
            int port = Integer.parseInt(rest.substring(colon + 1));
            if (port <= 0 || port > 65535 || !host.matches("[A-Za-z0-9.\\-]+")) {
                return null;
            }
            return new InetSocketAddress(host, port);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从候选列表中解析出指定 scheme 的全部地址（忽略非法项） */
    public static List<InetSocketAddress> decodeAll(Iterable<String> candidates, String scheme) {
        List<InetSocketAddress> result = new ArrayList<>();
        for (String candidate : candidates) {
            InetSocketAddress address = decode(candidate, scheme);
            if (address != null && !result.contains(address)) {
                result.add(address);
            }
        }
        return result;
    }

    /** 地址列表 → 去重候选消息列表（信令 ConnectRequest/Accept.candidates 用） */
    public static List<PunchCandidate> encodeAllCandidates(String scheme,
                                                           List<InetSocketAddress> addresses) {
        List<PunchCandidate> result = new ArrayList<>();
        for (String candidate : encodeAll(scheme, addresses)) {
            result.add(PunchCandidate.newBuilder().setAddress(candidate).build());
        }
        return result;
    }

    /** 候选消息列表中解析出指定 scheme 的全部地址（忽略非法项） */
    public static List<InetSocketAddress> decodeAllCandidates(List<PunchCandidate> candidates,
                                                              String scheme) {
        List<InetSocketAddress> result = new ArrayList<>();
        for (PunchCandidate candidate : candidates) {
            InetSocketAddress address = decode(candidate.getAddress(), scheme);
            if (address != null && !result.contains(address)) {
                result.add(address);
            }
        }
        return result;
    }
}
