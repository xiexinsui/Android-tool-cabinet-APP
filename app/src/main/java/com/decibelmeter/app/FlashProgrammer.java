package com.decibelmeter.app;

/**
 * Flash 烧写流程：擦除 -> 分页写入 -> 校验。
 *
 * 擦除采用「全片擦除」而不是按页/按扇区擦：
 * - 标准擦除 0x43 用参数 0xFF,0x00 表示「全部」；
 * - 新系列（F2/F4/F7/L4…）还支持扩展擦除 0x44（参数 0xFF,0xFF,0x00），
 *   大容量芯片用它更快，所以优先试 0x44，NAK 或超时再退回 0x43。
 * 全片擦除会清掉原有固件，这也是「先备份/读回再下载」的原因。
 *
 * 写入按 256 字节一块（协议单块上限），最后一块按 4 字节对齐补 0xFF：
 * Bootloader 要求写长度必须是 4 的倍数，补 0xFF 相当于「不改变这些字节的内容」，
 * 同时避免为了对齐而把整块补满 256 字节导致写到 Flash 末尾之外。
 */
final class FlashProgrammer {

    /** 每次 WRITE_MEMORY 的字节数，也是进度统计的「页」单位。 */
    private static final int PAGE_SIZE = IspProtocol.MAX_BLOCK;
    /** STM32 Flash 起始地址，擦除验证从这里取样。 */
    static final long FLASH_BASE = 0x08000000L;

    private final IspProtocol isp;
    private final IspLog log;

    FlashProgrammer(IspProtocol isp, IspLog log) {
        this.isp = isp;
        this.log = log;
    }

    /**
     * 全片擦除。返回 false 表示两种擦除命令都失败（通常是读/写保护未解除）。
     * 擦除验证只作提示：部分芯片擦除后前几字节会保留旧数据，不作为失败。
     */
    boolean erase() {
        log.progress(0, IspLog.STATUS_ERASING, null);
        log.log(IspLog.LEVEL_INFO, "开始擦除芯片...");
        boolean erased = false;

        if (isp.supports(IspProtocol.CMD_EXTENDED_ERASE)) {
            log.log(IspLog.LEVEL_INFO, "尝试扩展擦除 (0x44)...");
            if (log.isCancelled()) {
                return false;
            }
            if (isp.extendedErase()) {
                erased = true;
            } else {
                log.log(IspLog.LEVEL_WARN, "扩展擦除失败");
            }
        }
        if (!erased && isp.supports(IspProtocol.CMD_ERASE)) {
            if (log.isCancelled()) {
                return false;
            }
            if (isp.erase()) {
                erased = true;
            } else {
                log.log(IspLog.LEVEL_WARN, "标准擦除失败");
            }
        }
        if (!erased) {
            log.log(IspLog.LEVEL_ERROR, "错误: 芯片不支持擦除命令或无响应");
            if (isp.commands.length > 0) {
                log.log(IspLog.LEVEL_INFO, "尝试 WRITE_UNPROTECT (0x73)...");
                isp.writeUnprotect();
            }
            log.progress(0, IspLog.STATUS_FAILED, null);
            return false;
        }

        log.progress(50, IspLog.STATUS_VERIFY_ERASE, null);
        log.log(IspLog.LEVEL_INFO, "擦除验证中...");
        if (isp.verifyErased(FLASH_BASE, PAGE_SIZE)) {
            log.progress(100, IspLog.STATUS_DONE, null);
            log.log(IspLog.LEVEL_SUCCESS, "擦除成功");
            return true;
        }
        log.log(IspLog.LEVEL_WARN, "警告: 擦除验证未通过");
        log.progress(100, IspLog.STATUS_DONE, null);
        return true;
    }

    /**
     * 写入整个镜像。
     *
     * @param perPageVerify 每写完一块立刻回读比对，能第一时间发现写歪/掉字节，代价是时间翻倍
     */
    boolean writeImage(FirmwareImage image, boolean perPageVerify) {
        int totalPages = 0;
        for (int s = 0; s < image.segmentCount(); s++) {
            int remaining = image.segment(s).data.length;
            while (remaining > 0) {
                totalPages++;
                remaining -= PAGE_SIZE;
            }
        }
        if (totalPages == 0) {
            log.log(IspLog.LEVEL_ERROR, "没有数据需要写入");
            return false;
        }

        log.log(IspLog.LEVEL_INFO, "开始写入固件，共 " + totalPages + " 块"
                + (perPageVerify ? "，逐块校验" : ""));
        log.progress(0, IspLog.STATUS_WRITING, "0/" + totalPages);

        long start = System.currentTimeMillis();
        int written = 0;
        byte[] page = new byte[PAGE_SIZE];

        for (int s = 0; s < image.segmentCount(); s++) {
            FirmwareImage.Segment segment = image.segment(s);
            int offset = 0;
            while (offset < segment.data.length) {
                if (log.isCancelled()) {
                    log.log(IspLog.LEVEL_WARN, "写入操作被用户中止");
                    return false;
                }
                int chunk = Math.min(PAGE_SIZE, segment.data.length - offset);
                // 4 字节对齐：AN3155 要求写长度是 4 的倍数
                int writeLength = (chunk + 3) & ~3;
                java.util.Arrays.fill(page, (byte) 0xFF);
                System.arraycopy(segment.data, offset, page, 0, chunk);
                long address = segment.address + offset;

                if (!isp.writeMemory(address, page, writeLength)) {
                    log.log(IspLog.LEVEL_ERROR, "写入失败: 地址 0x"
                            + String.format(java.util.Locale.US, "%08X", address));
                    return false;
                }
                written++;

                if (perPageVerify) {
                    byte[] readBack = new byte[writeLength];
                    if (!isp.readMemory(address, readBack, writeLength)) {
                        log.log(IspLog.LEVEL_ERROR, "逐块校验读取失败: 0x"
                                + String.format(java.util.Locale.US, "%08X", address));
                        return false;
                    }
                    for (int i = 0; i < writeLength; i++) {
                        if (page[i] != readBack[i]) {
                            log.log(IspLog.LEVEL_ERROR, "逐块校验错误: 地址 0x"
                                    + String.format(java.util.Locale.US, "%08X", address + i)
                                    + ", 期望 0x" + String.format(java.util.Locale.US, "%02X", page[i] & 0xFF)
                                    + ", 实际 0x" + String.format(java.util.Locale.US, "%02X", readBack[i] & 0xFF));
                            return false;
                        }
                    }
                }

                offset += chunk;
                long elapsedMs = System.currentTimeMillis() - start;
                log.progress(written * 100 / totalPages, IspLog.STATUS_WRITING,
                        written + "/" + totalPages + "  " + String.format(
                                java.util.Locale.US, "%.1fs", elapsedMs / 1000.0));
            }
        }

        log.log(IspLog.LEVEL_SUCCESS, "固件写入完成，共 " + written + " 块"
                + (perPageVerify ? "（已逐块校验）" : ""));
        log.progress(100, IspLog.STATUS_DONE, null);
        return true;
    }

    /**
     * 整体回读校验：把镜像和芯片里的内容逐字节比对，最多报告前 10 处差异。
     * 与逐块校验不同，它可以在「下载完成后」单独执行，用来复核。
     */
    boolean verifyImage(FirmwareImage image) {
        int total = (int) image.totalBytes();
        if (total == 0) {
            return false;
        }
        log.log(IspLog.LEVEL_INFO, "开始回读校验，共 " + total + " 字节...");
        log.progress(0, IspLog.STATUS_VERIFYING, null);

        int verified = 0;
        int errors = 0;
        for (int s = 0; s < image.segmentCount(); s++) {
            FirmwareImage.Segment segment = image.segment(s);
            int offset = 0;
            while (offset < segment.data.length) {
                if (log.isCancelled()) {
                    return false;
                }
                int chunk = Math.min(PAGE_SIZE, segment.data.length - offset);
                long address = segment.address + offset;
                byte[] readBack = new byte[chunk];
                if (!isp.readMemory(address, readBack, chunk)) {
                    log.log(IspLog.LEVEL_ERROR, "读取失败: 地址 0x"
                            + String.format(java.util.Locale.US, "%08X", address));
                    return false;
                }
                for (int i = 0; i < chunk; i++) {
                    if (segment.data[offset + i] != readBack[i]) {
                        errors++;
                        if (errors <= 10) {
                            log.log(IspLog.LEVEL_ERROR, "校验错误: 0x"
                                    + String.format(java.util.Locale.US, "%08X", address + i)
                                    + " 期望 0x" + String.format(java.util.Locale.US, "%02X", segment.data[offset + i] & 0xFF)
                                    + " 实际 0x" + String.format(java.util.Locale.US, "%02X", readBack[i] & 0xFF));
                        }
                    }
                }
                offset += chunk;
                verified += chunk;
                log.progress(verified * 100 / total, IspLog.STATUS_VERIFYING,
                        verified + "/" + total);
            }
        }
        if (errors > 0) {
            log.log(IspLog.LEVEL_ERROR, "校验失败，共 " + errors + " 处不匹配");
            log.progress(100, IspLog.STATUS_FAILED, null);
            return false;
        }
        log.log(IspLog.LEVEL_SUCCESS, "校验通过，数据完全一致");
        log.progress(100, IspLog.STATUS_DONE, null);
        return true;
    }

    /**
     * 把整片 Flash 读回成镜像（用于备份或查看芯片现有程序）。
     * 按 256 字节一块连续读，读到一段完整数据，导出为 Intel HEX 即可再用本工具写回去。
     */
    FirmwareImage readFlash(long startAddress, int sizeKb) {
        long total = (long) sizeKb * 1024L;
        byte[] buffer = new byte[(int) total];
        java.util.Arrays.fill(buffer, (byte) 0xFF);
        int done = 0;
        while (done < total) {
            if (log.isCancelled()) {
                log.log(IspLog.LEVEL_WARN, "读取已取消");
                return null;
            }
            int chunk = (int) Math.min(PAGE_SIZE, total - done);
            long address = startAddress + done;
            byte[] part = new byte[chunk];
            if (!isp.readMemory(address, part, chunk)) {
                log.log(IspLog.LEVEL_ERROR, "读取失败: 地址 0x"
                        + String.format(java.util.Locale.US, "%08X", address));
                return null;
            }
            System.arraycopy(part, 0, buffer, done, chunk);
            done += chunk;
            log.progress((int) (done * 100 / total), IspLog.STATUS_READING_FLASH,
                    done + "/" + total);
        }
        FirmwareImage image = new FirmwareImage();
        image.append(startAddress, buffer, buffer.length);
        return image;
    }
}
