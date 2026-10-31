package com.decibelmeter.app;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSDP/UPnP 发现：电视、音箱、打印机和不少 IoT 只回 M-SEARCH，既不开 SMB 也不发 mDNS。
 *
 * 收包窗口结束后再抓设备描述 XML 里的 friendlyName / modelName。很多设备的管理页和描述
 * 都挂在 HTTP 上，抓不到名字时至少留下 ST 与 SERVER，仍能判断它是不是路由器/媒体设备。
 */
final class SsdpDiscovery {

    static final class Info {
        String name;
        String model;
        String vendor;
        String deviceType;
        String mac;
    }

    interface Sink {
        void onDevice(String ip, Info info);
    }

    private static final String MULTICAST_HOST = "239.255.255.250";
    private static final int MULTICAST_PORT = 1900;
    private static final int MAX_DESCRIPTIONS = 8;
    private static final int DESCRIPTION_TIMEOUT_MS = 700;
    private static final int MAX_DESCRIPTION_BYTES = 64 * 1024;

    private SsdpDiscovery() {
    }

    static int browse(Context context, int windowMillis, Sink sink) {
        WifiManager.MulticastLock lock = null;
        DatagramSocket socket = null;
        Map<String, Entry> entries = new LinkedHashMap<>();
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("toolkit-ssdp");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            byte[] request = ("M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: " + MULTICAST_HOST + ":" + MULTICAST_PORT + "\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: ssdp:all\r\n"
                    + "USER-AGENT: Android/1.0 UPnP/1.1 ToolKit/1.0\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName(MULTICAST_HOST);

            socket = new DatagramSocket();
            socket.setSoTimeout(400);
            socket.send(new DatagramPacket(request, request.length, group, MULTICAST_PORT));

            long deadline = System.currentTimeMillis() + windowMillis;
            long secondSendAt = System.currentTimeMillis() + Math.min(700, windowMillis / 2);
            boolean sentAgain = false;
            byte[] buffer = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                if (!sentAgain && System.currentTimeMillis() >= secondSendAt) {
                    socket.send(new DatagramPacket(request, request.length, group, MULTICAST_PORT));
                    sentAgain = true;
                }
                socket.setSoTimeout((int) Math.max(100,
                        Math.min(400, deadline - System.currentTimeMillis())));
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                String ip = response.getAddress().getHostAddress();
                String text = new String(response.getData(), 0, response.getLength(),
                        StandardCharsets.ISO_8859_1);
                Entry entry = entries.get(ip);
                if (entry == null) {
                    entry = new Entry();
                    entries.put(ip, entry);
                }
                entry.merge(text);
            }
        } catch (Exception e) {
            // 组播被路由器拦掉、MulticastLock 建不起来都归到这里；设备仍可由其它三路发现
        } finally {
            if (socket != null) {
                socket.close();
            }
        }

        // 描述文件只抓前几个：一台设备一个 HTTP 请求，抓太多会把收尾拖成十几秒
        List<Entry> descriptions = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.location != null) {
                descriptions.add(entry);
                if (descriptions.size() >= MAX_DESCRIPTIONS) {
                    break;
                }
            }
        }
        fetchDescriptions(descriptions);

        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.name == null && entry.model == null && entry.deviceType == null
                    && entry.vendor == null) {
                continue;
            }
            Info info = new Info();
            info.name = entry.name;
            info.model = entry.model;
            info.vendor = entry.vendor;
            info.deviceType = entry.deviceType;
            info.mac = entry.mac;
            sink.onDevice(item.getKey(), info);
        }
        if (lock != null) {
            try {
                lock.release();
            } catch (Exception e) {
                // 锁已经释放或 Wi-Fi 断开
            }
        }
        return entries.size();
    }

    private static void fetchDescriptions(List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, entries.size()));
        final CountDownLatch done = new CountDownLatch(entries.size());
        for (final Entry entry : entries) {
            pool.execute(() -> {
                try {
                    LanHttp.Response response = LanHttp.getUrl(entry.location,
                            DESCRIPTION_TIMEOUT_MS, MAX_DESCRIPTION_BYTES);
                    if (response.body != null && !response.body.isEmpty()) {
                        String body = response.body;
                        entry.name = firstTag(body, "friendlyName");
                        entry.model = firstTag(body, "modelName");
                        entry.vendor = firstTag(body, "manufacturer");
                        entry.mac = firstMac(body);
                        String type = firstTag(body, "deviceType");
                        if (type != null) {
                            entry.deviceType = type;
                        }
                    }
                } catch (Exception e) {
                    // 描述页拿不到就保留 ST/SERVER
                } finally {
                    done.countDown();
                }
            });
        }
        pool.shutdown();
        try {
            // 给描述抓取留一点时间，但绝不无限等；没抓完的设备仍会用 ST/SERVER 入列表
            done.await(1600, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    private static String firstMac(String xml) {
        for (String tag : new String[]{"MACAddress", "macAddress", "HardwareAddress",
                "hardwareAddress", "MAC", "mac"}) {
            String mac = LanMac.normalizeOrNullAllZero(firstTag(xml, tag));
            if (mac != null) {
                return mac;
            }
        }
        return null;
    }

    private static String firstTag(String xml, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + tag + "[^>]*>(.*?)</" + tag + ">")
                .matcher(xml);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).replaceAll("(?is)<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return value.isEmpty() ? null : LanHttp.truncate(value, 80);
    }

    private static final class Entry {
        volatile String location;
        volatile String vendor;
        volatile String deviceType;
        volatile String name;
        volatile String model;
        volatile String mac;

        void merge(String text) {
            String locationValue = header(text, "location");
            if (locationValue != null && locationValue.length() < 512 && location == null) {
                location = locationValue;
            }
            String server = header(text, "server");
            if (server != null) {
                vendor = LanHttp.truncate(server, 100);
            }
            String st = header(text, "st");
            if (st != null && st.startsWith("urn:") && deviceType == null) {
                deviceType = st;
            }
        }

        private static String header(String text, String name) {
            for (String line : text.split("\r?\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
    }
}
