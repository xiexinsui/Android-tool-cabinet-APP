package com.decibelmeter.app;

import com.hoho.android.usbserial.driver.UsbSerialPort;

/**
 * 一次串口下载会话的全部流程编排（界面上的每个动作最终都落到这里）。
 *
 * 关键点：AN3155 的全局擦除收到 ACK 就表示 Bootloader 已回到命令等待状态，
 * 擦除本身不会复位芯片，所以擦除后直接写即可，不必再复位或重进 Bootloader
 * （手动模式复位还有掉出 Bootloader、从刚擦空的 Flash 跑飞的风险）。
 * 只有解写保护 (0x73) 会改选项字节并让芯片自己复位，之后才必须重新同步。
 * connected 标志用来避免重复复位。
 */
final class IspSession {

    /** 下载完成后的跳转地址：从 Flash 起始处运行（带自定义 Bootloader 的固件另填 BIN 起始地址）。 */
    static final long RUN_ADDRESS = 0x08000000L;
    /** 容量寄存器合理上限（KB）：超过就当读错，避免按假值分配超大内存。 */
    private static final int MAX_REASONABLE_FLASH_KB = 8192;

    /** 识别到的芯片信息，界面「芯片信息」区显示的就是它。 */
    static final class ChipInfo {
        String name = "";
        String series = "";
        int pid;
        int bootloaderVersion;
        int flashSizeKb;
        int pageSize;
        int sramKb;
        long flashSizeReg;
        long optionBytesAddr = 0x1FFFF800L;
        int rdpRaw;
        /** 0=无保护 1=Level1 2=Level2。 */
        int rdpLevel;
        boolean hasProtection;

        boolean isKnown() {
            return pid != 0;
        }
    }

    private final IspLog log;
    private final IspProtocol isp;
    private final BootLoaderControl blControl;
    private final FlashProgrammer programmer;

    final ChipInfo chip = new ChipInfo();
    private int mode = BootLoaderControl.MODE_MANUAL;
    private int stepDelayMs = 100;
    private int baudRate = 115200;
    private boolean bootloaderConnected;

    private FirmwareImage firmware;

    IspSession(UsbSerialPort port, IspLog log) {
        this.log = log;
        this.isp = new IspProtocol(port, log);
        this.blControl = new BootLoaderControl(port, log);
        this.programmer = new FlashProgrammer(isp, log);
    }

    void configure(int bootMode, int stepDelayMs, int baudRate) {
        this.mode = Math.max(0, Math.min(BootLoaderControl.MODE_COUNT - 1, bootMode));
        this.stepDelayMs = stepDelayMs;
        this.baudRate = baudRate;
    }

    void setFirmware(FirmwareImage image) {
        this.firmware = image;
    }

    /** Bootloader 报告的命令表，界面「芯片信息」里列出它。 */
    int[] supportedCommands() {
        return isp.commands;
    }

    /**
     * 由 GET_VERSION 返回的第一个选项字节推断读保护等级。
     * Bootloader 不直接给「RDP 等级」，只能按约定值猜：
     * 0xAA/0xA5/0xFF（以及 F 系列未保护时的 0x00）= 无保护；
     * 0xCC/0x33 = Level2（不可逆，解除会全片擦除）；其余非约定值按 Level1 处理。
     */
    private void applyReadProtection(int raw) {
        chip.rdpRaw = raw;
        if (raw == 0x00 || raw == 0xA5 || raw == 0xAA || raw == 0xFF) {
            chip.rdpLevel = 0;
            chip.hasProtection = false;
            log.log(IspLog.LEVEL_INFO, "保护状态: 无保护 (option=0x"
                    + String.format(java.util.Locale.US, "%02X", raw) + ")");
        } else if (raw == 0xCC || raw == 0x33) {
            chip.rdpLevel = 2;
            chip.hasProtection = true;
            log.log(IspLog.LEVEL_WARN, "保护状态: 疑似 Level2 (0x"
                    + String.format(java.util.Locale.US, "%02X", raw) + ")");
        } else {
            chip.rdpLevel = 1;
            chip.hasProtection = true;
            log.log(IspLog.LEVEL_WARN, "保护状态: 有保护 (0x"
                    + String.format(java.util.Locale.US, "%02X", raw) + "，按 Level1 处理)");
        }
    }

    private boolean commandSupported(int command) {
        return isp.supports(command);
    }

    /**
     * 容量寄存器读到的值先做合理性检查：芯片没进 Bootloader、地址读错或线路干扰都可能
     * 得到 0xFFFF 之类的假值，若直接按它分配内存（65535 KB = 64MB）会卡死甚至 OOM，
     * 超过 8192 KB 一律当作读取失败。
     */
    private int sanitizeSizeKb(int measured) {
        if (measured <= 0 || measured > MAX_REASONABLE_FLASH_KB) {
            if (measured > 0) {
                log.log(IspLog.LEVEL_WARN, "容量寄存器读数异常（" + measured
                        + " KB），改用型号表中的容量");
            }
            return 0;
        }
        return measured;
    }

    /**
     * 确保芯片处于 Bootloader 中：控制线握手 -> 同步 -> 读出 Bootloader 版本/命令表 ->
     * 读 PID 定型号 -> 读选项字节判断保护 -> 读容量寄存器实测 Flash 大小并二次消歧。
     */
    boolean ensureBootloader() {
        if (bootloaderConnected) {
            return true;
        }
        log.log(IspLog.LEVEL_CMD, "自动进入 Bootloader...");
        blControl.enter(mode, stepDelayMs);
        isp.purgeBuffers();
        // 上面的 DTR/RTS 动作可能把 UART 的校验位配置冲掉（部分 CH340 兼容芯片），重发一次
        isp.applyLineSettings(baudRate);

        log.log(IspLog.LEVEL_INFO, "正在连接 Bootloader...");
        boolean synced;
        if (BootLoaderControl.isManual(mode)) {
            // 手动模式下芯片何时开始发 ACK 不确定，短超时反复探测
            synced = isp.syncManual();
        } else {
            synced = isp.sync(IspProtocol.TIMEOUT_MS);
        }
        if (!synced) {
            log.log(IspLog.LEVEL_ERROR, "Bootloader 连接失败");
            return false;
        }
        if (!isp.getCommand()) {
            return false;
        }
        if (!isp.getId()) {
            return false;
        }
        chip.pid = isp.productId;
        chip.bootloaderVersion = isp.bootloaderVersion;

        ChipDatabase.Context context = new ChipDatabase.Context(
                commandSupported(IspProtocol.CMD_EXTENDED_ERASE), 0);
        ChipDatabase.Chip entry = ChipDatabase.lookup(chip.pid, context);
        if (entry != null) {
            chip.name = entry.name;
            chip.series = entry.series;
            chip.flashSizeKb = entry.flashSizeKb;
            chip.pageSize = entry.pageSize;
            chip.sramKb = entry.sramKb;
            chip.flashSizeReg = entry.flashSizeReg;
            chip.optionBytesAddr = entry.optionBytesAddr;
            log.log(IspLog.LEVEL_SUCCESS, "识别芯片: " + chip.name
                    + " (系列 " + chip.series + ")，Flash 后备: " + chip.flashSizeKb + " KB");
        } else {
            chip.name = "未知型号";
            log.log(IspLog.LEVEL_WARN, "未识别的芯片 PID: 0x"
                    + String.format(java.util.Locale.US, "%04X", chip.pid));
        }

        if (isp.getVersion()) {
            chip.bootloaderVersion = isp.bootloaderVersion;
            applyReadProtection(isp.optionByte1);
        }

        if (chip.flashSizeReg != 0) {
            int measured = sanitizeSizeKb(isp.readFlashSizeKb(chip.flashSizeReg));
            if (measured > 0) {
                chip.flashSizeKb = measured;
                ChipDatabase.Chip refined = ChipDatabase.lookup(chip.pid,
                        new ChipDatabase.Context(context.hasExtendedErase, measured));
                if (refined != null) {
                    chip.name = refined.name;
                    chip.series = refined.series;
                }
            }
        }

        bootloaderConnected = true;
        log.log(IspLog.LEVEL_SUCCESS, "Bootloader 连接成功");
        return true;
    }

    /**
     * 检查固件地址是否落在芯片 Flash 范围内。
     * 越界写入轻则 NAK 中止，重则改坏选项字节，所以宁可先拦住。
     */
    boolean validateFirmware() {
        if (firmware == null || firmware.segmentCount() == 0) {
            log.log(IspLog.LEVEL_ERROR, "请先选择固件文件");
            return false;
        }
        long min = firmware.minAddress();
        long max = firmware.maxEndAddress();
        int kb = chip.flashSizeKb > 0 ? chip.flashSizeKb : 512;
        long end = FlashProgrammer.FLASH_BASE + (long) kb * 1024L;
        if (min < FlashProgrammer.FLASH_BASE) {
            log.log(IspLog.LEVEL_ERROR, "固件起始地址 0x"
                    + String.format(java.util.Locale.US, "%08X", min) + " 低于 Flash 基址");
            return false;
        }
        if (max > end) {
            log.log(IspLog.LEVEL_ERROR, "固件超出 Flash 范围: 0x"
                    + String.format(java.util.Locale.US, "%08X", max) + " > 0x"
                    + String.format(java.util.Locale.US, "%08X", end) + " (" + kb + " KB)");
            return false;
        }
        log.log(IspLog.LEVEL_INFO, "固件地址校验通过: 0x"
                + String.format(java.util.Locale.US, "%08X", min) + "-0x"
                + String.format(java.util.Locale.US, "%08X", max) + " (Flash " + kb + " KB)");
        return true;
    }

    /**
     * 完整下载流程：连接 -> 校验地址 -> （必要时解写保护）-> 全片擦除 ->
     * 分页写入 -> （可选）跳转运行。擦除后不再重进 Bootloader。
     */
    boolean download(boolean verifyEachPage, boolean runAfterDownload) {
        if (!ensureBootloader()) {
            return false;
        }
        if (!validateFirmware()) {
            return false;
        }

        if (chip.rdpLevel != 0) {
            log.log(IspLog.LEVEL_WARN, "检测到读保护，将先解除写保护再继续");
            if (!isp.writeUnprotect()) {
                log.log(IspLog.LEVEL_ERROR, "解除写保护失败");
                return false;
            }
            bootloaderConnected = false;
            if (!ensureBootloader()) {
                return false;
            }
        }

        if (!programmer.erase()) {
            // 自动重试一次：多数失败是写保护没解，解完再擦就能过
            log.log(IspLog.LEVEL_WARN, "擦除失败，尝试解除写保护后自动重试一次...");
            if (isp.writeUnprotect()) {
                bootloaderConnected = false;
                if (!ensureBootloader()) {
                    return false;
                }
                if (!programmer.erase()) {
                    return false;
                }
            } else {
                return false;
            }
        }

        // 擦除 ACK 后 Bootloader 仍在命令状态，直接接着写；复位重进反而可能从空 Flash 跑飞
        long writeStart = System.currentTimeMillis();
        if (!programmer.writeImage(firmware, verifyEachPage)) {
            return false;
        }
        long ms = System.currentTimeMillis() - writeStart;
        int seconds = (int) (ms / 1000);
        int tenths = (int) ((ms % 1000) / 100);
        if (seconds >= 60) {
            log.log(IspLog.LEVEL_SUCCESS, "固件写入完成，下载用时 " + (seconds / 60) + " 分 "
                    + (seconds % 60) + "." + tenths + " 秒");
        } else {
            log.log(IspLog.LEVEL_SUCCESS, "固件写入完成，下载用时 " + seconds + "." + tenths + " 秒");
        }

        if (runAfterDownload) {
            if (!exitAndRun()) {
                return false;
            }
        }
        log.log(IspLog.LEVEL_SUCCESS, "所有操作已完成");
        return true;
    }

    /** 单独回读校验（下载后可再复核一次）。 */
    boolean verifyDownloadedFirmware() {
        if (!ensureBootloader()) {
            return false;
        }
        if (!validateFirmware()) {
            return false;
        }
        return programmer.verifyImage(firmware);
    }

    /** 只擦除，不写入（清空芯片用）。 */
    boolean eraseOnly() {
        if (!ensureBootloader()) {
            return false;
        }
        return programmer.erase();
    }

    /**
     * 退出 Bootloader 让芯片跑新程序。
     * 手动模式没法控制复位线，只能发 GO 命令让 Bootloader 自己跳转；
     * 控制线模式则先发 GO（若支持），再走一次「BOOT0 拉低 + 复位」的时序。
     */
    boolean exitAndRun() {
        if (BootLoaderControl.isManual(mode)) {
            if (bootloaderConnected) {
                if (!isp.go(RUN_ADDRESS)) {
                    log.log(IspLog.LEVEL_ERROR, "GO 0x08000000 失败");
                    return false;
                }
            }
        } else {
            if (bootloaderConnected && commandSupported(IspProtocol.CMD_GO)) {
                isp.go(RUN_ADDRESS);
            }
            blControl.exit(mode, stepDelayMs);
            isp.clearInput();
        }
        bootloaderConnected = false;
        log.log(IspLog.LEVEL_SUCCESS, "已退出 Bootloader，芯片从 Flash 启动");
        return true;
    }

    /** 读回整片 Flash（容量未知时按 512KB 处理），返回可直接导出 HEX 的镜像。 */
    FirmwareImage readWholeFlash() {
        if (!ensureBootloader()) {
            return null;
        }
        int kb = chip.flashSizeKb > 0 ? chip.flashSizeKb : 512;
        log.log(IspLog.LEVEL_INFO, "开始读取 Flash: 0x"
                + String.format(java.util.Locale.US, "%08X", FlashProgrammer.FLASH_BASE)
                + "，" + kb + " KB...");
        FirmwareImage image = programmer.readFlash(FlashProgrammer.FLASH_BASE, kb);
        if (image != null) {
            log.log(IspLog.LEVEL_SUCCESS, "Flash 读取完成: " + kb + " KB");
        }
        return image;
    }

    /** 按指定地址段读取（十六进制查看器/局部核对用）。 */
    byte[] readMemory(long address, int length) {
        if (!ensureBootloader()) {
            return null;
        }
        byte[] out = new byte[length];
        return isp.readMemory(address, out, length) ? out : null;
    }

    /** 选项字节地址：优先用型号表，未知型号退回 F1 的 0x1FFFF800。 */
    long optionBytesAddress() {
        ChipDatabase.Chip entry = ChipDatabase.lookup(chip.pid, new ChipDatabase.Context(
                commandSupported(IspProtocol.CMD_EXTENDED_ERASE), chip.flashSizeKb));
        if (entry != null && entry.optionBytesAddr != 0) {
            return entry.optionBytesAddr;
        }
        return chip.optionBytesAddr;
    }

    /** 读 16 字节选项字节（成对出现的「值 + 反值」，可判断是否被写坏）。 */
    byte[] readOptionBytes() {
        if (!ensureBootloader()) {
            return null;
        }
        long address = optionBytesAddress();
        log.log(IspLog.LEVEL_INFO, "读取选项字节 @ 0x"
                + String.format(java.util.Locale.US, "%08X", address) + " ...");
        return readMemory(address, 16);
    }

    /** 写选项字节：需要复位后才生效，写错可能把芯片锁死。 */
    boolean writeOptionBytes(byte[] data) {
        if (!ensureBootloader()) {
            return false;
        }
        long address = optionBytesAddress();
        log.log(IspLog.LEVEL_WARN, "写入选项字节 @ 0x"
                + String.format(java.util.Locale.US, "%08X", address) + "（需复位生效）");
        return isp.writeMemory(address, data, 16);
    }

    // ---- 以下是对应「调试」面板的单步动作，用来排查时序/接线问题 ----

    boolean debugSync() {
        return isp.sync(BootLoaderControl.isManual(mode)
                ? IspProtocol.SYNC_MANUAL_TIMEOUT_MS : IspProtocol.TIMEOUT_MS);
    }

    /** 线路自检：判断 CH340 有没有真的发出偶校验（见 IspProtocol#lineParitySelfTest）。 */
    int lineSelfTest() {
        return isp.lineParitySelfTest();
    }

    boolean debugGetCommand() {
        if (!isp.getCommand()) {
            return false;
        }
        chip.bootloaderVersion = isp.bootloaderVersion;
        return true;
    }

    boolean debugGetId() {
        if (!isp.getId()) {
            return false;
        }
        chip.pid = isp.productId;
        ChipDatabase.Chip entry = ChipDatabase.lookup(chip.pid, new ChipDatabase.Context(
                commandSupported(IspProtocol.CMD_EXTENDED_ERASE), chip.flashSizeKb));
        if (entry != null) {
            chip.name = entry.name;
            chip.series = entry.series;
            chip.flashSizeKb = entry.flashSizeKb;
        }
        return true;
    }

    boolean debugGetVersion() {
        if (!isp.getVersion()) {
            return false;
        }
        chip.bootloaderVersion = isp.bootloaderVersion;
        applyReadProtection(isp.optionByte1);
        return true;
    }

    boolean debugEraseStandard() {
        return isp.erase();
    }

    boolean debugEraseExtended() {
        return isp.extendedErase();
    }

    boolean debugGo() {
        return isp.go(RUN_ADDRESS);
    }

    boolean debugWriteUnprotect() {
        return isp.writeUnprotect();
    }

    /** 解除读保护：会立刻全片擦除，界面上必须先让用户确认。 */
    boolean debugReadoutUnprotect() {
        return isp.readoutUnprotect();
    }

    boolean debugReadFlashSize() {
        if (chip.flashSizeReg == 0) {
            log.log(IspLog.LEVEL_ERROR, "未知 Flash 容量寄存器地址 (PID=0x"
                    + String.format(java.util.Locale.US, "%04X", chip.pid) + ")");
            return false;
        }
        int kb = sanitizeSizeKb(isp.readFlashSizeKb(chip.flashSizeReg));
        if (kb > 0) {
            chip.flashSizeKb = kb;
            return true;
        }
        return false;
    }

    /**
     * 手动发送原始字节并显示回应，用于确认接线/波特率（例如只发 0x7F 看有没有 ACK）。
     * 参数是空格分隔的十六进制串，如 "7F"、"00 FF"。
     */
    boolean debugSendRaw(byte[] bytes, int length) {
        if (length <= 0) {
            log.log(IspLog.LEVEL_ERROR, "无效的十六进制数据");
            return false;
        }
        if (!isp.sendRaw(bytes, length)) {
            return false;
        }
        isp.readAndLogResponse(500);
        return true;
    }

    /** 手动设置 DTR/RTS 电平（配合模式 13..16 观察芯片反应）。 */
    void debugSetDtr(boolean level) {
        blControl.setDtr(level);
    }

    void debugSetRts(boolean level) {
        blControl.setRts(level);
    }
}
