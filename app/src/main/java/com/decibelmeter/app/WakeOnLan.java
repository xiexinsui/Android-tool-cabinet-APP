package com.decibelmeter.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Wake-on-LAN：6 字节 0xFF + 16 遍 MAC 的魔术包。
 *
 * 广播地址同时发全局 255.255.255.255 和当前网段广播：有些路由器只放行其中一种。
 * 端口 9 与 7 都发一遍，成本只有几个 UDP 包。没有特殊权限，发不出去通常是路由器/AP 隔离。
 */
final class WakeOnLan {

    private WakeOnLan() {
    }

    static boolean wake(String mac, String broadcastAddress) {
        String normalized = LanMac.normalize(mac);
        if (normalized == null) {
            return false;
        }
        byte[] macBytes = new byte[6];
        String[] parts = normalized.split(":");
        for (int i = 0; i < 6; i++) {
            macBytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int i = 0; i < 16; i++) {
            System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6);
        }

        List<String> targets = new ArrayList<>();
        targets.add("255.255.255.255");
        if (broadcastAddress != null && !broadcastAddress.isEmpty()
                && !targets.contains(broadcastAddress)) {
            targets.add(broadcastAddress);
        }
        DatagramSocket socket = null;
        boolean sent = false;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            for (String target : targets) {
                InetAddress address = InetAddress.getByName(target);
                for (int port : new int[]{9, 7}) {
                    try {
                        socket.send(new DatagramPacket(packet, packet.length, address, port));
                        sent = true;
                    } catch (IOException e) {
                        // 一个目标发不出去还有另一个
                    }
                }
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return sent;
    }
}
