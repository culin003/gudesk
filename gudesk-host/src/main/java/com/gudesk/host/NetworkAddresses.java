package com.gudesk.host;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 本机非回环 IPv4 地址枚举（内网 {@code ip:port} 直连展示用）。
 *
 * <p>从 {@link HostApp} 抽出，供 CLI 打印与合并 UI 首页「本机地址」显示共用。
 */
public final class NetworkAddresses {

    private NetworkAddresses() {
    }

    /** 枚举本机非回环 IPv4 地址（多网卡全列；枚举失败返回空列表） */
    public static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) {
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
        } catch (Exception ignored) {
            // 枚举失败返回空列表
        }
        return result;
    }
}
