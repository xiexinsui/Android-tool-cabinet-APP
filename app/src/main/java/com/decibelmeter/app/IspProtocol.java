package com.decibelmeter.app;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

/**
 * AN3155 串口 ISP 协议（STM32 内部固化 Bootloader 使用的协议）。
 *
 * 物理层：8 数据位 / 偶校验 / 1 停止位（8E1），这是 Bootloader 唯一接受的格式，
 * 校验位还会被用来区分「命令字节」和「数据字节」：
 *   偶校验正确 = 这是一个命令/地址字节；故意破坏校验（发送取反值）= 分隔标记。
 * 因此每条命令都成对发送 [命令, 命令^0xFF]，第二个字节校验必然错误，
 * Bootloader 收到它就知道「命令发完了，开始执行」。
 *
 * 应答只有两种：0x79=ACK（成功），0x1F=NAK（失败）。
 * 32 位地址一律高字节在前（大端），后面再跟 4 字节的异或校验和。
 * 写数据块长度必须是不超过 256 的 4 的倍数。
 */
final class IspProtocol {

    static final int ACK = 0x79;
    static final int NAK = 0x1F;

    static final int CMD_GET = 0x00;
    static final int CMD_GET_VERSION = 0x01;
    static final int CMD_GET_ID = 0x02;
    static final int CMD_READ_MEMORY = 0x11;
    static final int CMD_GO = 0x21;
    static final int CMD_WRITE_MEMORY = 0x31;
    static final int CMD_ERASE = 0x43;
    static final int CMD_EXTENDED_ERASE = 0x44;
    static final int CMD_WRITE_UNPROTECT = 0x73;
    static final int CMD_READOUT_UNPROTECT = 0x92;

    /** 普通命令应答等待时间；擦除是内部操作，耗时与容量成正比，要放宽。 */
    static final int TIMEOUT_MS = 2000;
    static final int ERASE_TIMEOUT_MS = 15000;
    static final int EXTENDED_ERASE_TIMEOUT_MS = 30000;

    /** 手动 BOOT0 模式下芯片随时可能进入 Bootloader，用短超时高频探测。 */
    static final int SYNC_RETRY_COUNT = 20;
    static final int SYNC_RETRY_INTERVAL_MS = 500;
    static final int SYNC_MANUAL_TIMEOUT_MS = 300;

    /** 单个写入块上限：协议里长度字段是 1 字节（N=长度-1），最大 256。 */
    static final int MAX_BLOCK = 256;

    private final UsbSerialPort port;
    private final IspLog log;

    /** GET 命令结果：Bootloader 版本 + 它支持的命令列表。 */
    int bootloaderVersion;
    int[] commands = new int[0];
    /** GET_VERSION 结果：两个选项字节，其中第 1 个用于推断读保护等级。 */
    int optionByte1;
    int optionByte2;
    /** GET_ID 结果：16 位产品编号。 */
    int productId;

    IspProtocol(UsbSerialPort port, IspLog log) {
        this.port = port;
        this.log = log;
    }

    /** 一条命令是否被芯片支持——决定是否走扩展擦除、能否发 GO。 */
    boolean supports(int command) {
        for (int c : commands) {
            if (c == command) {
                return true;
            }
        }
        return false;
    }

    /**
     * 十六进制转储。
     *
     * 只展开前若干个字节：写入/校验时每个数据块都是 256 字节，
     * 全部展开会让一行日志上千字符，界面刷新代价极高（下载几十页就会明显卡顿），
     * 而且排障时真正需要看的是「哪条命令、多少字节、结果如何」而不是整块数据内容。
     */
    private static final int HEX_DUMP_LIMIT = 12;
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private static String hexDump(byte[] data, int length) {
        int shown = Math.min(length, HEX_DUMP_LIMIT);
        StringBuilder sb = new StringBuilder(shown * 3 + 24);
        for (int i = 0; i < shown; i++) {
            int value = data[i] & 0xFF;
            sb.append(HEX_DIGITS[value >> 4]).append(HEX_DIGITS[value & 0xF]).append(' ');
        }
        if (length > shown) {
            sb.append("…(共 ").append(length).append(" 字节)");
        }
        return sb.toString().trim();
    }

    private void logSend(byte[] data, int length, String describe) {
        log.log(IspLog.LEVEL_CMD, ">> " + describe + ": " + hexDump(data, length));
    }

    /** 发送固定长度数据；USB 写失败（拔线等）时记录并返回 false。 */
    private boolean send(byte[] data, int length, String describe) {
        logSend(data, length, describe);
        try {
            port.write(data, TIMEOUT_MS);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            log.log(IspLog.LEVEL_ERROR, "发送失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 接收缓冲。
     *
     * 两个坑叠在一起：
     * 1) CH340 的 bulk-IN 每包 32 字节。驱动把 read() 的请求长度直接当作
     *    bulkTransfer 的传输长度，若请求长度是 32 的整数倍（比如 512），
     *    而对方发来的数据也正好是整数包（如 256 字节 = 8 个满包、没有短包收尾），
     *    USB 传输就永远等不到「结束」，超时后内核把已收到的数据整批丢弃——
     *    表现就是单字节 ACK 每次都能收到，而 ReadMemory 的 256 字节一个都收不到。
     *    所以请求长度故意取 511（非 32 的倍数），保证任何一次传输都以短包结束。
     * 2) 必须一次多读些字节再自己取用：用 1 字节数组去读会把整包取走只留 1 字节。
     */
    private final byte[] rx = new byte[512];
    private static final int RX_REQUEST = 511;
    private int rxCount;
    private int rxPos;

    /** 取 1 个已缓冲的字节；缓冲空时向 USB 读一批（最多等 timeoutMs）。无数据返回 -1。 */
    private int pollByte(int timeoutMs) throws IOException {
        if (rxPos >= rxCount) {
            rxCount = 0;
            rxPos = 0;
            int got = port.read(rx, RX_REQUEST, timeoutMs);
            if (got <= 0) {
                return -1;
            }
            rxCount = Math.min(got, rx.length);
        }
        return rx[rxPos++] & 0xFF;
    }

    /**
     * 读 1 个字节，最多等 timeoutMs。
     * 采用 250ms 分片轮询：既能及时响应「中止」按钮，也不会因单次长超时卡住界面。
     */
    private int readByte(int timeoutMs) {
        int elapsed = 0;
        while (elapsed < timeoutMs) {
            if (log.isCancelled()) {
                log.log(IspLog.LEVEL_WARN, "操作已取消");
                return -1;
            }
            int chunk = Math.min(250, timeoutMs - elapsed);
            int value;
            try {
                value = pollByte(chunk);
            } catch (IOException | UnsupportedOperationException e) {
                log.log(IspLog.LEVEL_ERROR, "读取失败: " + e.getMessage());
                return -1;
            }
            if (value >= 0) {
                String name = value == ACK ? "ACK" : value == NAK ? "NACK" : "未知";
                log.log(IspLog.LEVEL_CMD, "<< [" + name + "] "
                        + String.format(java.util.Locale.US, "%02X", value));
                return value;
            }
            elapsed += chunk;
        }
        return -1;
    }

    /** 等待 ACK；超时或收到 NAK 都算失败。 */
    private boolean waitAck(int timeoutMs) {
        int value = readByte(timeoutMs);
        if (value < 0) {
            log.log(IspLog.LEVEL_ERROR, "超时: 未收到响应");
            return false;
        }
        return value == ACK;
    }

    /** 精确读取 length 字节（用于读取响应数据体）。超时/取消都会明确报出已收多少。 */
    private boolean readExact(byte[] out, int length, int timeoutMs) {
        int done = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (done < length) {
            if (log.isCancelled()) {
                log.log(IspLog.LEVEL_WARN, "读取已取消（收到 " + done + "/" + length + " 字节）");
                return false;
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                log.log(IspLog.LEVEL_ERROR, "读取超时: 期望 " + length
                        + " 字节，只收到 " + done + " 字节");
                return false;
            }
            int value;
            try {
                value = pollByte((int) Math.min(250, left));
            } catch (IOException | UnsupportedOperationException e) {
                log.log(IspLog.LEVEL_ERROR, "读取失败: " + e.getMessage());
                return false;
            }
            if (value >= 0) {
                out[done++] = (byte) value;
            }
        }
        log.log(IspLog.LEVEL_CMD, "<< " + hexDump(out, length));
        return true;
    }

    /** 清空输入缓冲，丢弃上一阶段残留的字节，避免错位。 */
    void clearInput() {
        rxCount = 0;
        rxPos = 0;
        for (int i = 0; i < 40; i++) {
            try {
                if (pollByte(30) < 0) {
                    return;
                }
            } catch (Exception e) {
                return;
            }
        }
    }

    /** 丢弃未读入数据 + 未发出数据（驱动不支持时退化为只清输入）。 */
    void purgeBuffers() {
        try {
            port.purgeHwBuffers(true, true);
        } catch (Exception ignored) {
            // 多数驱动没有实现硬件 FIFO 清空，忽略即可
        }
        clearInput();
    }

    /**
     * 原样发送若干字节（调试面板用：可以手动拼一帧命令看芯片反应）。
     */
    boolean sendRaw(byte[] data, int length) {
        log.log(IspLog.LEVEL_CMD, ">> 手动发送 " + length + " 字节: " + hexDump(data, length));
        try {
            port.write(data, TIMEOUT_MS);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            log.log(IspLog.LEVEL_ERROR, "发送失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 读取并记录对方回应（调试面板用）。
     * 收到数据后若下一个分片为空，认为回应已结束；最长等 timeoutMs。
     */
    void readAndLogResponse(int timeoutMs) {
        byte[] collected = new byte[MAX_BLOCK];
        int total = 0;
        int elapsed = 0;
        while (total < collected.length && elapsed < timeoutMs) {
            int value;
            try {
                value = pollByte(Math.min(250, timeoutMs - elapsed));
            } catch (IOException | UnsupportedOperationException e) {
                log.log(IspLog.LEVEL_ERROR, "读取失败: " + e.getMessage());
                return;
            }
            if (value >= 0) {
                collected[total++] = (byte) value;
                continue;
            }
            if (total > 0) {
                break;
            }
            elapsed += 250;
        }
        if (total == 0) {
            log.log(IspLog.LEVEL_CMD, "<< （无回应）");
        } else {
            log.log(IspLog.LEVEL_CMD, "<< " + hexDump(collected, total));
        }
    }

    /**
     * 波特率同步：发送 0x7F（非法命令），Bootloader 收到后会回 ACK，
     * 同时它也完成了波特率自动识别。收到 ACK 即说明双方波特率一致。
     */
    boolean sync(int timeoutMs) {
        clearInput();
        byte[] sync = {0x7F};
        log.log(IspLog.LEVEL_TRACE, "发送 0x7F 进行波特率同步...");
        if (!send(sync, 1, "波特率同步")) {
            return false;
        }
        if (!waitAck(timeoutMs)) {
            log.log(IspLog.LEVEL_ERROR, "同步失败: 未收到 ACK");
            return false;
        }
        log.log(IspLog.LEVEL_TRACE, "Bootloader 同步成功");
        return true;
    }

    /** 手动模式（芯片随时复位进 BL）下的同步：短超时反复探测，最多 20 次。 */
    boolean syncManual() {
        if (sync(SYNC_MANUAL_TIMEOUT_MS)) {
            return true;
        }
        for (int i = 1; i < SYNC_RETRY_COUNT; i++) {
            if (log.isCancelled()) {
                return false;
            }
            sleep(SYNC_RETRY_INTERVAL_MS);
            if (sync(SYNC_MANUAL_TIMEOUT_MS)) {
                return true;
            }
        }
        return false;
    }

    /** [命令, 命令^0xFF] + 等 ACK。 */
    private boolean sendCommand(int command, String describe) {
        byte[] packet = {(byte) command, (byte) (command ^ 0xFF)};
        if (!send(packet, 2, describe)) {
            return false;
        }
        return waitAck(TIMEOUT_MS);
    }

    /** 4 字节大端地址 + 1 字节异或校验和 + 等 ACK。 */
    private boolean sendAddress(long address) {
        byte[] packet = new byte[5];
        packet[0] = (byte) ((address >> 24) & 0xFF);
        packet[1] = (byte) ((address >> 16) & 0xFF);
        packet[2] = (byte) ((address >> 8) & 0xFF);
        packet[3] = (byte) (address & 0xFF);
        packet[4] = (byte) (packet[0] ^ packet[1] ^ packet[2] ^ packet[3]);
        if (!send(packet, 5, "地址 0x"
                + String.format(java.util.Locale.US, "%08X", address))) {
            return false;
        }
        return waitAck(TIMEOUT_MS);
    }

    /**
     * GET(0x00)：返回 Bootloader 版本和支持的命令表。
     *
     * 实测响应：ACK，N（命令条数），版本 1 字节 + N 字节命令号，最后再一个 ACK。
     * 例如 F429 回 `79 0B 22 00 01 02 11 21 31 43 63 73 82 92 79`：
     * N=0x0B=11 条命令，版本 0x22，命令表 00/01/02/11/21/31/43/63/73/82/92。
     * 注意结尾的 ACK 在数据之后单独到达，不能当成命令表的一部分。
     */
    boolean getCommand() {
        log.log(IspLog.LEVEL_TRACE, "获取 Bootloader 版本和支持的命令...");
        if (!sendCommand(CMD_GET, "GET")) {
            return false;
        }
        byte[] n = new byte[1];
        if (!readExact(n, 1, TIMEOUT_MS)) {
            return false;
        }
        int count = n[0] & 0xFF;
        byte[] body = new byte[count + 1];
        if (!readExact(body, count + 1, TIMEOUT_MS)) {
            log.log(IspLog.LEVEL_ERROR, "GET 响应数据不完整");
            return false;
        }
        bootloaderVersion = body[0] & 0xFF;
        int[] list = new int[count];
        for (int i = 0; i < count; i++) {
            list[i] = body[1 + i] & 0xFF;
        }
        commands = list;
        if (!waitAck(TIMEOUT_MS)) {
            log.log(IspLog.LEVEL_ERROR, "GET 响应结尾缺少 ACK");
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (int c : commands) {
            sb.append(String.format(java.util.Locale.US, "%02X ", c));
        }
        log.log(IspLog.LEVEL_SUCCESS, "Bootloader 版本 0x"
                + String.format(java.util.Locale.US, "%02X", bootloaderVersion)
                + "，支持 " + commands.length + " 条命令: " + sb.toString().trim());
        return true;
    }

    /** GET_ID(0x02)：响应 N=1、PID 高字节、PID 低字节、ACK。 */
    boolean getId() {
        log.log(IspLog.LEVEL_TRACE, "获取芯片 ID...");
        if (!sendCommand(CMD_GET_ID, "GET_ID")) {
            return false;
        }
        byte[] head = new byte[1];
        if (!readExact(head, 1, TIMEOUT_MS)) {
            return false;
        }
        byte[] pid = new byte[2];
        if (!readExact(pid, 2, TIMEOUT_MS)) {
            log.log(IspLog.LEVEL_ERROR, "GET_ID 响应不完整");
            return false;
        }
        productId = (pid[0] & 0xFF) << 8 | (pid[1] & 0xFF);
        if (!waitAck(TIMEOUT_MS)) {
            return false;
        }
        log.log(IspLog.LEVEL_SUCCESS, "芯片 PID: 0x"
                + String.format(java.util.Locale.US, "%04X", productId));
        return true;
    }

    /** GET_VERSION(0x01)：响应 3 字节（版本 + 两个选项字节）+ ACK。 */
    boolean getVersion() {
        log.log(IspLog.LEVEL_TRACE, "获取版本信息...");
        if (!sendCommand(CMD_GET_VERSION, "GET_VERSION")) {
            return false;
        }
        byte[] data = new byte[3];
        if (!readExact(data, 3, TIMEOUT_MS)) {
            log.log(IspLog.LEVEL_ERROR, "GET_VERSION 响应不完整");
            return false;
        }
        bootloaderVersion = data[0] & 0xFF;
        optionByte1 = data[1] & 0xFF;
        optionByte2 = data[2] & 0xFF;
        if (!waitAck(TIMEOUT_MS)) {
            return false;
        }
        log.log(IspLog.LEVEL_TRACE, "版本: 0x" + String.format(java.util.Locale.US, "%02X", bootloaderVersion)
                + ", 选项字节: 0x" + String.format(java.util.Locale.US, "%02X 0x%02X", optionByte1, optionByte2));
        return true;
    }

    /** ERASE(0x43) + 参数 0xFF,0x00 = 全片擦除。 */
    boolean erase() {
        log.log(IspLog.LEVEL_INFO, "执行标准全局擦除...");
        if (!sendCommand(CMD_ERASE, "ERASE")) {
            return false;
        }
        byte[] param = {(byte) 0xFF, 0x00};
        if (!send(param, 2, "全局擦除参数")) {
            return false;
        }
        if (!waitAck(ERASE_TIMEOUT_MS)) {
            log.log(IspLog.LEVEL_ERROR, "擦除操作失败或超时");
            return false;
        }
        log.log(IspLog.LEVEL_SUCCESS, "全局擦除完成");
        return true;
    }

    /** EXTENDED_ERASE(0x44) + 参数 0xFF,0xFF,0x00 = 全片擦除（F2/F4/L4+ 等新系列）。 */
    boolean extendedErase() {
        log.log(IspLog.LEVEL_INFO, "执行扩展全局擦除...");
        if (!sendCommand(CMD_EXTENDED_ERASE, "EXTENDED_ERASE")) {
            return false;
        }
        byte[] param = {(byte) 0xFF, (byte) 0xFF, 0x00};
        if (!send(param, 3, "扩展全局擦除参数")) {
            return false;
        }
        if (!waitAck(EXTENDED_ERASE_TIMEOUT_MS)) {
            log.log(IspLog.LEVEL_ERROR, "扩展擦除操作失败或超时");
            return false;
        }
        log.log(IspLog.LEVEL_SUCCESS, "扩展全局擦除完成");
        return true;
    }

    /**
     * WRITE_MEMORY(0x31)：地址后跟 [N=长度-1, 数据..., 校验和]，
     * 校验和 = N 与所有数据字节的逐位异或。
     */
    boolean writeMemory(long address, byte[] data, int length) {
        if (length <= 0 || length > MAX_BLOCK || (length & 3) != 0) {
            log.log(IspLog.LEVEL_ERROR, "写入数据长度无效: " + length + " 字节（需为 4 的倍数且不超过 256）");
            return false;
        }
        if (!sendCommand(CMD_WRITE_MEMORY, "WRITE_MEMORY")) {
            return false;
        }
        if (!sendAddress(address)) {
            return false;
        }
        byte[] packet = new byte[length + 2];
        int n = length - 1;
        int checksum = n;
        packet[0] = (byte) n;
        System.arraycopy(data, 0, packet, 1, length);
        for (int i = 0; i < length; i++) {
            checksum ^= data[i] & 0xFF;
        }
        packet[length + 1] = (byte) checksum;
        return send(packet, length + 2, "N=" + n + " 数据" + length + "字节") && waitAck(TIMEOUT_MS);
    }

    /**
     * READ_MEMORY(0x11)：地址后跟 [N=长度-1, N^0xFF]，收到 ACK 之后直接连续吐 length 字节，
     * 结尾没有校验和。
     */
    boolean readMemory(long address, byte[] out, int length) {
        if (length <= 0 || length > MAX_BLOCK) {
            log.log(IspLog.LEVEL_ERROR, "读取长度无效: " + length);
            return false;
        }
        if (!sendCommand(CMD_READ_MEMORY, "READ_MEMORY")) {
            return false;
        }
        if (!sendAddress(address)) {
            return false;
        }
        int n = length - 1;
        byte[] packet = {(byte) n, (byte) (n ^ 0xFF)};
        if (!send(packet, 2, "字节数")) {
            return false;
        }
        if (!waitAck(TIMEOUT_MS)) {
            return false;
        }
        return readExact(out, length, TIMEOUT_MS);
    }

    /** GO(0x21)：发完地址后芯片立即跳转执行，不再回 ACK，所以这里不能等应答。 */
    boolean go(long address) {
        log.log(IspLog.LEVEL_TRACE, "跳转到地址 0x"
                + String.format(java.util.Locale.US, "%08X", address) + " 执行...");
        if (!sendCommand(CMD_GO, "GO")) {
            return false;
        }
        boolean ok = sendAddressNoAck(address);
        log.log(IspLog.LEVEL_TRACE, "跳转命令已发送");
        return ok;
    }

    /** GO 专用：地址发出去即可，Bootloader 已经跳走，等 ACK 只会超时。 */
    private boolean sendAddressNoAck(long address) {
        byte[] packet = new byte[5];
        packet[0] = (byte) ((address >> 24) & 0xFF);
        packet[1] = (byte) ((address >> 16) & 0xFF);
        packet[2] = (byte) ((address >> 8) & 0xFF);
        packet[3] = (byte) (address & 0xFF);
        packet[4] = (byte) (packet[0] ^ packet[1] ^ packet[2] ^ packet[3]);
        return send(packet, 5, "地址 0x"
                + String.format(java.util.Locale.US, "%08X", address));
    }

    /** WRITE_UNPROTECT(0x73)：解除写保护（Flash 被保护时擦除会 NAK）。 */
    boolean writeUnprotect() {
        log.log(IspLog.LEVEL_INFO, "解除写保护...");
        if (!sendCommand(CMD_WRITE_UNPROTECT, "WRITE_UNPROTECT")) {
            return false;
        }
        log.log(IspLog.LEVEL_INFO, "写保护已解除");
        return true;
    }

    /** READOUT_UNPROTECT(0x92)：解除读保护，会连带擦除整片 Flash。 */
    boolean readoutUnprotect() {
        log.log(IspLog.LEVEL_WARN, "解除读保护 (0x92) — 会触发全片 Flash 擦除!");
        if (!sendCommand(CMD_READOUT_UNPROTECT, "READOUT_UNPROTECT")) {
            return false;
        }
        log.log(IspLog.LEVEL_SUCCESS, "读保护解除命令已发送");
        return true;
    }

    /**
     * 读 Flash 容量寄存器（低 16 位有效，小端存放），返回 KB 数；0 表示失败。
     * 例：STM32F429 的寄存器在 0x1FFF7A22，读 2 字节得到 2048。
     */
    int readFlashSizeKb(long registerAddress) {
        log.log(IspLog.LEVEL_TRACE, "读取 Flash 大小寄存器 0x"
                + String.format(java.util.Locale.US, "%08X", registerAddress) + "...");
        byte[] out = new byte[MAX_BLOCK];
        if (!readMemory(registerAddress, out, 2)) {
            return 0;
        }
        int kb = (out[0] & 0xFF) | (out[1] & 0xFF) << 8;
        log.log(IspLog.LEVEL_SUCCESS, "芯片实际 Flash 大小: " + kb + " KB");
        return kb;
    }

    /** 擦除后抽一段读回，应全为 0xFF。 */
    boolean verifyErased(long address, int size) {
        int length = Math.min(Math.max(size, 1), MAX_BLOCK);
        byte[] data = new byte[length];
        if (!readMemory(address, data, length)) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (data[i] != (byte) 0xFF) {
                log.log(IspLog.LEVEL_ERROR, "擦除验证失败: 地址 0x"
                        + String.format(java.util.Locale.US, "%08X", address + i)
                        + " 处值为 0x" + String.format(java.util.Locale.US, "%02X", data[i] & 0xFF));
                return false;
            }
        }
        log.log(IspLog.LEVEL_SUCCESS, "擦除验证通过: 0x"
                + String.format(java.util.Locale.US, "%08X", address) + " 处 " + length + " 字节全为 0xFF");
        return true;
    }

    /**
     * 重新按 8E1 配置串口。
     *
     * CH340 的 DTR/RTS 控制写（0xa4）在部分兼容芯片上会连带影响 UART 寄存器，
     * 而 STM32 Bootloader 只认偶校验：校验位一旦丢成 8N1，
     * 「1 的个数为偶数」的字节（如 0x00、0xFF）会被芯片判为校验错误直接丢弃，
     * 表现就是同步能通（0x7F 有 7 个 1）但 GET 毫无回应。
     * 所以每次进入 Bootloader 之前都重发一次线路参数。
     */
    boolean applyLineSettings(int baudRate) {
        try {
            port.setParameters(baudRate, UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN);
            log.log(IspLog.LEVEL_TRACE, "线路参数已重设: " + baudRate + " 8E1");
            return true;
        } catch (Exception e) {
            log.log(IspLog.LEVEL_ERROR, "设置线路参数失败: " + e.getMessage());
            return false;
        }
    }

    /** 线路自检结果：协议正常 / 校验位没生效 / 完全没回应 */
    static final int LINETEST_OK = 0;
    static final int LINETEST_NO_PARITY = 1;
    static final int LINETEST_NO_REPLY = 2;

    /**
     * 判断 CH340 是否真的在发偶校验。
     *
     * 原理：STM32 在 8E1 下按「8 个数据位 + 第 9 位」的总 1 个数判偶偶。
     * 若适配器实际是 8N1，第 9 位恒为停止位的 1，于是只有「数据位里 1 的个数为奇数」的
     * 字节能被当成正确校验收下来。四条探测正好覆盖两种情况：
     *   7F        7 个 1（奇）——8N1 下也能通过，用作线路通不通的基准
     *   00 FF     0 / 8 个 1（偶）——只有真偶校验才能通过
     *   01 FE     1 / 7 个 1（奇）——两种情况都能通过
     *   02 FD     1 / 7 个 1（奇）——同上
     * 因此「7F/01/02 有回应而 00 没回应」即可断定校验位没生效。
     */
    int lineParitySelfTest() {
        int[][] probes = {{0x7F}, {0x00, 0xFF}, {0x01, 0xFE}, {0x02, 0xFD}};
        String[] names = {"7F 同步", "00 FF GET", "01 FE GET_VERSION", "02 FD GET_ID"};
        boolean[] replied = new boolean[probes.length];

        for (int i = 0; i < probes.length; i++) {
            clearInput();
            byte[] data = new byte[probes[i].length];
            for (int j = 0; j < data.length; j++) {
                data[j] = (byte) probes[i][j];
            }
            try {
                port.write(data, TIMEOUT_MS);
            } catch (Exception e) {
                log.log(IspLog.LEVEL_ERROR, "自检发送失败 " + names[i] + ": " + e.getMessage());
                return LINETEST_NO_REPLY;
            }
            int got = collectBytes(400);
            replied[i] = got > 0;
            log.log(IspLog.LEVEL_CMD, "自检 " + names[i] + " → "
                    + (got > 0 ? "有回应（" + got + " 字节）" : "无回应"));
        }

        if (!replied[0] && !replied[1] && !replied[2] && !replied[3]) {
            return LINETEST_NO_REPLY;
        }
        // 奇数个 1 的字节通、偶数个 1 的字节不通 => 校验位没生效
        if (replied[0] && replied[2] && !replied[1]) {
            return LINETEST_NO_PARITY;
        }
        return replied[1] ? LINETEST_OK : LINETEST_NO_REPLY;
    }

    /** 在 timeoutMs 内收集对方回应的字节数（只关心有没有，不解析内容）。 */
    private int collectBytes(int timeoutMs) {
        int total = 0;
        int elapsed = 0;
        while (elapsed < timeoutMs) {
            int value;
            try {
                value = pollByte(Math.min(100, timeoutMs - elapsed));
            } catch (Exception e) {
                return total;
            }
            if (value >= 0) {
                total++;
            } else {
                elapsed += 100;
            }
        }
        return total;
    }

    static void sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
