package com.decibelmeter.app;

/**
 * MAC 地址规范化。扫描来源至少有三种写法：NBNS 的 6 字节二进制、mDNS TXT 的
 * `AA:BB:CC:DD:EE:FF`、手动输入的 `AA-BB-...`，统一成大写冒号格式后再入库和显示。
 */
final class LanMac {

    private LanMac() {
    }

    /** 接受 AA:BB:CC:DD:EE:FF、AA-BB-...、AA.BB.... 或 12 位连续十六进制；无效返回 null。 */
    static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String hex = text.trim().replace(":", "").replace("-", "").replace(".", "");
        if (hex.length() != 12) {
            return null;
        }
        StringBuilder builder = new StringBuilder(17);
        for (int i = 0; i < 12; i++) {
            char c = hex.charAt(i);
            if (Character.digit(c, 16) < 0) {
                return null;
            }
            if (i > 0 && i % 2 == 0) {
                builder.append(':');
            }
            builder.append(Character.toUpperCase(c));
        }
        return builder.toString();
    }

    /** 全 0 的 MAC 是“没有地址”，不能当成有效结果。 */
    static String normalizeOrNullAllZero(String text) {
        String mac = normalize(text);
        return "00:00:00:00:00:00".equals(mac) ? null : mac;
    }
}
