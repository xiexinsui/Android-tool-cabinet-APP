package com.decibelmeter.app;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

/**
 * 用串口的 DTR / RTS 两条控制线控制芯片，让它进入 / 退出系统 Bootloader。
 *
 * 原理：STM32 复位瞬间采样 BOOT0 引脚——BOOT0=1 且 BOOT1=0 时从内部 Bootloader 启动，
 * BOOT0=0 时从 Flash 启动。开发板常用转接板的 DTR、RTS 两脚分别接到 BOOT0 和 NRST（复位），
 * 于是上位机只需按顺序翻转这两条线，就能远程完成「拉高 BOOT0 → 复位 → 释放复位」。
 *
 * DTR/RTS 有正相/反相两种接法（中间是否经过三极管反相），BOOT0 也可能接在 RTS 上，
 * 所以这里提供 17 种模式（0=不使用控制线，全靠手动按键）覆盖常见接线，
 * 具体用哪一种由界面上的下拉框选择。
 *
 * 电平约定：setDTR(true) 表示「该控制线置有效电平」，与界面文案 HIGH 对应。
 */
final class BootLoaderControl {

    /** 模式总数（0..MODE_COUNT-1），与资源数组 isp_boot_modes 一一对应。 */
    static final int MODE_COUNT = 17;
    /** 模式 0 = 不用 DTR/RTS，需要手动让芯片进入 Bootloader。 */
    static final int MODE_MANUAL = 0;

    private final UsbSerialPort port;
    private final IspLog log;

    BootLoaderControl(UsbSerialPort port, IspLog log) {
        this.port = port;
        this.log = log;
    }

    static boolean isManual(int mode) {
        return mode == MODE_MANUAL;
    }

    /** 供调试面板单独控制某条线（配合模式 13..16 观察芯片反应）。 */
    void setDtr(boolean level) {
        try {
            port.setDTR(level);
        } catch (IOException | UnsupportedOperationException e) {
            log.log(IspLog.LEVEL_ERROR, "设置 DTR 失败: " + e.getMessage());
        }
        log.log(IspLog.LEVEL_TRACE, "DTR -> " + (level ? "HIGH" : "LOW"));
    }

    void setRts(boolean level) {
        try {
            port.setRTS(level);
        } catch (IOException | UnsupportedOperationException e) {
            log.log(IspLog.LEVEL_ERROR, "设置 RTS 失败: " + e.getMessage());
        }
        log.log(IspLog.LEVEL_TRACE, "RTS -> " + (level ? "HIGH" : "LOW"));
    }

    /**
     * 进入 Bootloader。
     *
     * @param mode  0..16
     * @param delayMs 每一步之间的间隔，让芯片完成复位并保持 BOOT0 被采样到；默认 100ms
     * @return 控制线动作是否全部执行（芯片是否真的进了 BL 要靠后续同步确认）
     */
    boolean enter(int mode, int delayMs) {
        int delay = delayMs > 0 ? delayMs : 100;
        log.log(IspLog.LEVEL_INFO, "进入 Bootloader，模式 " + mode);
        if (mode == MODE_MANUAL) {
            log.log(IspLog.LEVEL_INFO, "模式 0: 不使用 DTR/RTS，请手动让芯片进入 Bootloader");
            return true;
        }

        if (mode >= 1 && mode <= 4) {
            // RTS 接 NRST（反相）：先复位，再给 BOOT0，最后释放复位
            boolean dtrBoot0 = mode == 3 || mode == 4;
            boolean rtsSteady = mode == 2 || mode == 4;
            boolean rtsReverse = !rtsSteady;
            setRts(rtsReverse);
            IspProtocol.sleep(delay);
            setDtr(dtrBoot0);
            IspProtocol.sleep(delay);
            setRts(rtsSteady);
            IspProtocol.sleep(delay);
        } else if (mode >= 5 && mode <= 8) {
            // 反接版本：DTR 接 NRST（反相），RTS 接 BOOT0
            boolean rtsBoot0 = mode == 7 || mode == 8;
            boolean dtrSteady = mode == 6 || mode == 8;
            boolean dtrReverse = !dtrSteady;
            setDtr(dtrReverse);
            IspProtocol.sleep(delay);
            setRts(rtsBoot0);
            IspProtocol.sleep(delay);
            setDtr(dtrSteady);
            IspProtocol.sleep(delay);
        } else if (mode >= 9 && mode <= 12) {
            // 单线复位脉冲：只动一条线，BOOT0 由板上的跳线/按键决定
            switch (mode) {
                case 9:
                    setDtr(false);
                    IspProtocol.sleep(delay);
                    setDtr(true);
                    break;
                case 10:
                    setDtr(true);
                    IspProtocol.sleep(delay);
                    setDtr(false);
                    break;
                case 11:
                    setRts(false);
                    IspProtocol.sleep(delay);
                    setRts(true);
                    break;
                default:
                    setRts(true);
                    IspProtocol.sleep(delay);
                    setRts(false);
                    break;
            }
            IspProtocol.sleep(delay);
        } else {
            // 13..16：只设置 BOOT0 电平，不复位（用户随后手动按复位键）
            switch (mode) {
                case 13:
                    setDtr(false);
                    break;
                case 14:
                    setDtr(true);
                    break;
                case 15:
                    setRts(false);
                    break;
                default:
                    setRts(true);
                    break;
            }
            IspProtocol.sleep(delay);
        }

        log.log(IspLog.LEVEL_INFO, "进入 Bootloader 完成");
        return true;
    }

    /**
     * 退出 Bootloader：把 BOOT0 翻回「从 Flash 启动」的一侧，再来一次复位，
     * 芯片就正常运行刚写入的固件。
     */
    boolean exit(int mode, int delayMs) {
        int delay = delayMs > 0 ? delayMs : 100;
        log.log(IspLog.LEVEL_INFO, "退出 Bootloader，模式 " + mode);
        if (mode == MODE_MANUAL) {
            log.log(IspLog.LEVEL_INFO, "模式 0: 不使用 DTR/RTS，请手动复位芯片");
            return true;
        }

        if (mode >= 1 && mode <= 4) {
            boolean dtrBoot0 = mode == 3 || mode == 4;
            boolean rtsSteady = mode == 2 || mode == 4;
            boolean rtsReverse = !rtsSteady;
            setRts(rtsReverse);
            IspProtocol.sleep(delay);
            setDtr(!dtrBoot0);
            IspProtocol.sleep(delay);
            setRts(rtsSteady);
            IspProtocol.sleep(delay);
        } else if (mode >= 5 && mode <= 8) {
            boolean rtsBoot0 = mode == 7 || mode == 8;
            boolean dtrSteady = mode == 6 || mode == 8;
            boolean dtrReverse = !dtrSteady;
            setDtr(dtrReverse);
            IspProtocol.sleep(delay);
            setRts(!rtsBoot0);
            IspProtocol.sleep(delay);
            setDtr(dtrSteady);
            IspProtocol.sleep(delay);
        } else if (mode >= 9 && mode <= 12) {
            switch (mode) {
                case 9:
                    setDtr(true);
                    IspProtocol.sleep(delay);
                    setDtr(false);
                    break;
                case 10:
                    setDtr(false);
                    IspProtocol.sleep(delay);
                    setDtr(true);
                    break;
                case 11:
                    setRts(true);
                    IspProtocol.sleep(delay);
                    setRts(false);
                    break;
                default:
                    setRts(false);
                    IspProtocol.sleep(delay);
                    setRts(true);
                    break;
            }
            IspProtocol.sleep(delay);
        } else {
            switch (mode) {
                case 13:
                    setDtr(true);
                    break;
                case 14:
                    setDtr(false);
                    break;
                case 15:
                    setRts(true);
                    break;
                default:
                    setRts(false);
                    break;
            }
            IspProtocol.sleep(delay);
        }

        log.log(IspLog.LEVEL_INFO, "退出 Bootloader 完成");
        return true;
    }
}
