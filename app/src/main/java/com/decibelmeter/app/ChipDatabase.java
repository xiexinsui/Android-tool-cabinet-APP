package com.decibelmeter.app;

/**
 * STM32 型号对照表。
 *
 * 背景：STM32 片内固化的系统存储器（Bootloader）只提供一个 16 位的产品编号（PID），
 * 而多个系列会共用同一个 PID（例如 0x0419 既是 STM32F429 也是别的型号，0x0415 可能是
 * F427/429 也可能是 L47）。所以只知道 PID 不足以定名，还要借助两条线索打分判断：
 * 1) Bootloader 是否支持「扩展擦除」命令 0x44 —— F2/F4 系列才支持，F0/F1/F3 不支持；
 * 2) 从 Flash 容量寄存器实际读到的容量（KB）—— 与表中最接近的基本就是它。
 *
 * 表内字段含义：
 * pid              产品编号（Bootloader 命令 0x02 返回的高字节在前的 16 位数）
 * name / series    型号名与系列名，仅用于界面显示
 * flashSizeKb      标称 Flash 容量，决定「读回 Flash」的字节数和固件地址上界
 * pageSize         页/写块大小（本工具按 256 字节分页写入，此值仅作参考显示）
 * sramKb           SRAM 容量，仅作参考显示
 * flashSizeReg     Flash 容量寄存器地址，读它 2 字节（小端）得到 KB 数；0 表示未知
 * optionBytesAddr  选项字节起始地址，读保护/写保护配置就在这里
 */
final class ChipDatabase {

    static final class Chip {
        final int pid;
        final String name;
        final String series;
        final int flashSizeKb;
        final int pageSize;
        final int sramKb;
        final long flashSizeReg;
        final long optionBytesAddr;

        Chip(int pid, String name, String series, int flashSizeKb, int pageSize, int sramKb,
             long flashSizeReg, long optionBytesAddr) {
            this.pid = pid;
            this.name = name;
            this.series = series;
            this.flashSizeKb = flashSizeKb;
            this.pageSize = pageSize;
            this.sramKb = sramKb;
            this.flashSizeReg = flashSizeReg;
            this.optionBytesAddr = optionBytesAddr;
        }
    }

    /** 消歧用的上下文：有没有扩展擦除命令 + 实测容量。 */
    static final class Context {
        final boolean hasExtendedErase;
        final int flashSizeKb; // 0 = 未知

        Context(boolean hasExtendedErase, int flashSizeKb) {
            this.hasExtendedErase = hasExtendedErase;
            this.flashSizeKb = flashSizeKb;
        }
    }

    private static final Chip[] TABLE = {
            // F0
            new Chip(0x0440, "STM32F030xC", "F0", 256, 2048, 32, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0441, "STM32F05xxx", "F0", 64, 1024, 8, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0442, "STM32F091xC", "F0", 256, 2048, 32, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0444, "STM32F030x8", "F0", 64, 1024, 8, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0445, "STM32F070x6/F030x6", "F0", 32, 1024, 6, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0448, "STM32F070xB/F072xB", "F0", 128, 2048, 16, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x044C, "STM32F03xxx", "F0", 32, 1024, 8, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x045C, "STM32F04xxx", "F0", 32, 1024, 6, 0x1FFFF7CCL, 0x1FFFF800L),
            // F1
            new Chip(0x0410, "STM32F10x MD", "F1", 128, 1024, 20, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x0412, "STM32F10x LD", "F1", 32, 1024, 10, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x0414, "STM32F10x HD", "F1", 256, 2048, 64, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x0416, "STM32F10x VL MD", "F1", 128, 1024, 24, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x0418, "STM32F10x VL LD", "F1", 32, 1024, 8, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x041B, "STM32F10x VL HD", "F1", 512, 2048, 64, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x0420, "STM32F10x VL HD", "F1", 512, 2048, 64, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x0428, "STM32F10x XL", "F1", 1024, 2048, 96, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x0430, "STM32F10x XL", "F1", 1024, 2048, 96, 0x1FFFF7E0L, 0x1FFFF800L),
            new Chip(0x0411, "STM32F10x CL", "F1", 256, 1024, 48, 0x1FFFF7E0L, 0x1FFFF800L),
            // F2
            new Chip(0x0411, "STM32F205/207/215/217", "F2", 1024, 16384, 128, 0x1FFF7A22L, 0x1FFFC000L),
            // F3
            new Chip(0x0422, "STM32F302xB(C)/303xB(C)", "F3", 256, 2048, 40, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0432, "STM32F37x/F378", "F3", 256, 2048, 32, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0438, "STM32F303x4(6/8)/334", "F3", 64, 2048, 16, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0439, "STM32F30x/F33x (64K)", "F3", 64, 2048, 16, 0x1FFFF7CCL, 0x1FFFF800L),
            new Chip(0x0446, "STM32F302x6(8)/303x6(8)", "F3", 64, 2048, 16, 0x1FFFF7CCL, 0x1FFFF800L),
            // F4
            new Chip(0x0413, "STM32F405xx/407xx", "F4", 1024, 16384, 128, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0419, "STM32F42xxx/43xxx (F429)", "F4", 2048, 16384, 256, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0415, "STM32F427/429/437/439", "F4", 2048, 16384, 256, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0417, "STM32F401xB(C)", "F4", 256, 16384, 64, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0421, "STM32F446xx", "F4", 512, 16384, 128, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0423, "STM32F401xD(E)", "F4", 512, 16384, 96, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0431, "STM32F410/411", "F4", 256, 16384, 128, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0433, "STM32F412xG", "F4", 1024, 16384, 256, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0434, "STM32F469/479", "F4", 2048, 16384, 320, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0441, "STM32F412xG (rev Z)", "F4", 1024, 16384, 256, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0458, "STM32F410x8(B) (rev A)", "F4", 128, 16384, 32, 0x1FFF7A22L, 0x1FFFC000L),
            new Chip(0x0463, "STM32F413/423", "F4", 512, 16384, 320, 0x1FFF7A22L, 0x1FFFC000L),
            // F7
            new Chip(0x0449, "STM32F745/746/756", "F7", 1024, 32768, 320, 0x1FF0F442L, 0x1FFF0000L),
            new Chip(0x0451, "STM32F765/767/768/769", "F7", 2048, 32768, 512, 0x1FF0F442L, 0x1FFF0000L),
            new Chip(0x0452, "STM32F722/723/732/733", "F7", 512, 32768, 256, 0x1FF0F442L, 0x1FFF0000L),
            new Chip(0x0453, "STM32F765/767 (rev A)", "F7", 2048, 32768, 512, 0x1FF0F442L, 0x1FFF0000L),
            new Chip(0x0455, "STM32F72x/73x", "F7", 512, 32768, 256, 0x1FF0F442L, 0x1FFF0000L),
            new Chip(0x0459, "STM32F74x/75x", "F7", 1024, 32768, 320, 0x1FF0F442L, 0x1FFF0000L),
            // L0
            new Chip(0x0425, "STM32L031xx/041xx", "L0", 32, 128, 8, 0x1FF8007CL, 0x1FF80000L),
            new Chip(0x0417, "STM32L07xxx/08xxx", "L0", 192, 128, 20, 0x1FF8007CL, 0x1FF80000L),
            new Chip(0x0447, "STM32L05xxx/06xxx", "L0", 64, 128, 8, 0x1FF8007CL, 0x1FF80000L),
            new Chip(0x0456, "STM32L04xxx/05xxx", "L0", 32, 128, 8, 0x1FF8007CL, 0x1FF80000L),
            new Chip(0x0457, "STM32L05xxx/06xxx (Cat3)", "L0", 32, 128, 8, 0x1FF8007CL, 0x1FF80000L),
            new Chip(0x0465, "STM32L01xxx/02xxx", "L0", 32, 128, 8, 0x1FF8007CL, 0x1FF80000L),
            new Chip(0x0470, "STM32L07xxx/08xxx (Cat5)", "L0", 192, 128, 20, 0x1FF8007CL, 0x1FF80000L),
            // L1
            new Chip(0x0416, "STM32L1xxx6(8/B) MD", "L1", 128, 256, 32, 0x1FF8004CL, 0x1FF80000L),
            new Chip(0x0429, "STM32L1xxx6(8/B) MD-A", "L1", 128, 256, 32, 0x1FF8004CL, 0x1FF80000L),
            new Chip(0x042B, "STM32L1xxx6(8/B) MD (rev Y)", "L1", 128, 256, 32, 0x1FF8004CL, 0x1FF80000L),
            new Chip(0x0436, "STM32L1xxxD", "L1", 384, 256, 48, 0x1FF8004CL, 0x1FF80000L),
            new Chip(0x0437, "STM32L1xxxC", "L1", 256, 256, 48, 0x1FF8004CL, 0x1FF80000L),
            // L4 / L4+（0x0415 与 F42x 冲突，靠 Context 消歧）
            new Chip(0x0415, "STM32L47xxx/48xxx", "L4", 1024, 2048, 128, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0435, "STM32L43xxx/44xxx", "L4", 256, 2048, 64, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0461, "STM32L496xx/4A6xx", "L4", 1024, 2048, 320, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0462, "STM32L45xxx/46xxx", "L4", 512, 2048, 160, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0464, "STM32L41xxx/42xxx", "L4", 128, 2048, 40, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x046D, "STM32L47x/48x (rev B)", "L4", 1024, 2048, 128, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x046F, "STM32L43x/44x (rev B)", "L4", 256, 2048, 64, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0471, "STM32L412/422", "L4", 128, 2048, 40, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0474, "STM32L4P5/Q5", "L4+", 1024, 2048, 320, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0475, "STM32L4R5/S5", "L4+", 2048, 2048, 640, 0x1FFF75E0L, 0x1FFF7800L),
            // L5 / U5
            new Chip(0x0472, "STM32L552/562", "L5", 512, 2048, 256, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0482, "STM32U575/585", "U5", 2048, 8192, 768, 0x0BF96E80L, 0x0BF96E80L),
            // G0 / G4
            new Chip(0x044B, "STM32G05x/06x", "G0", 64, 2048, 18, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0460, "STM32G07xxx/08xxx", "G0", 64, 2048, 36, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0466, "STM32G03xxx/04xxx", "G0", 32, 2048, 8, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0467, "STM32G0Bxxx/Cxxx", "G0", 512, 2048, 144, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x046C, "STM32G0B0/C0", "G0", 512, 2048, 144, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0468, "STM32G431/441", "G4", 128, 2048, 32, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0469, "STM32G47xxx/48xxx", "G4", 512, 2048, 128, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0478, "STM32G484", "G4", 512, 2048, 128, 0x1FFF75E0L, 0x1FFF7800L),
            new Chip(0x0479, "STM32G491/4A1", "G4", 512, 2048, 128, 0x1FFF75E0L, 0x1FFF7800L),
            // H5 / H7
            new Chip(0x0486, "STM32H503", "H5", 128, 8192, 32, 0x1FF1E880L, 0x1FF00000L),
            new Chip(0x0487, "STM32H563/573", "H5", 2048, 8192, 640, 0x1FF1E880L, 0x1FF00000L),
            new Chip(0x0488, "STM32H562", "H5", 1024, 8192, 320, 0x1FF1E880L, 0x1FF00000L),
            new Chip(0x0489, "STM32H5xx", "H5", 2048, 8192, 640, 0x1FF1E880L, 0x1FF00000L),
            new Chip(0x0450, "STM32H743/753/750", "H7", 2048, 131072, 1024, 0x1FF1E880L, 0x1FF00000L),
            new Chip(0x0480, "STM32H7A3/7B3/7B0", "H7", 2048, 131072, 1024, 0x1FF1E880L, 0x1FF00000L),
            new Chip(0x0481, "STM32H742/743 (rev V)", "H7", 2048, 131072, 688, 0x1FF1E880L, 0x1FF00000L),
            new Chip(0x0483, "STM32H723/725/733/735", "H7", 1024, 131072, 560, 0x1FF1E880L, 0x1FF00000L),
            new Chip(0x0490, "STM32H7Ax/7Bx (rev Z)", "H7", 2048, 131072, 1024, 0x1FF1E880L, 0x1FF00000L),
            // C0 / WB / WBA / WL
            new Chip(0x0443, "STM32C011/031", "C0", 32, 2048, 12, 0, 0),
            new Chip(0x0493, "STM32WB35/30", "WB", 512, 4096, 96, 0, 0),
            new Chip(0x0494, "STM32WB55xx", "WB", 1024, 4096, 256, 0, 0),
            new Chip(0x0495, "STM32WB15xx/10xx", "WB", 320, 4096, 48, 0, 0),
            new Chip(0x0485, "STM32WBA52", "WBA", 1024, 8192, 96, 0, 0),
            new Chip(0x0492, "STM32WBA54/55", "WBA", 1024, 8192, 128, 0, 0),
            new Chip(0x0496, "STM32WLEx", "WL", 256, 2048, 48, 0, 0),
            new Chip(0x0497, "STM32WL55xx", "WL", 256, 2048, 64, 0, 0),
            new Chip(0x0498, "STM32WLE5xx/WLE4xx", "WL", 256, 2048, 64, 0, 0),
            new Chip(0x0499, "STM32WL5x", "WL", 256, 2048, 64, 0, 0),
    };

    private ChipDatabase() {
    }

    /** F0/F1/F3：用标准擦除 0x43，不支持扩展擦除。 */
    private static boolean isF1Like(String series) {
        return series.equals("F0") || series.equals("F1") || series.equals("F3");
    }

    /** F2/F4：支持扩展擦除 0x44，Flash 容量普遍在 1MB 以上。 */
    private static boolean isF4Like(String series) {
        return series.equals("F2") || (series.length() >= 2 && series.charAt(0) == 'F'
                && series.charAt(1) == '4');
    }

    static Chip lookup(int pid) {
        for (Chip chip : TABLE) {
            if (chip.pid == pid) {
                return chip;
            }
        }
        return null;
    }

    /**
     * 带上下文的查找：同 PID 多义时按「系列是否匹配擦除能力」和「容量是否接近实测值」打分，
     * 分数最高的胜出；分数相同则保留表中靠前的一项（与顺序有关，勿随意打乱 TABLE）。
     */
    static Chip lookup(int pid, Context context) {
        Chip best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean found = false;

        for (Chip chip : TABLE) {
            if (chip.pid != pid) {
                continue;
            }
            found = true;
            if (context == null) {
                if (best == null) {
                    best = chip;
                }
                continue;
            }
            int score = 0;
            boolean f1 = isF1Like(chip.series);
            boolean f4 = isF4Like(chip.series);
            if (context.hasExtendedErase) {
                if (f4) {
                    score += 50;
                }
                if (chip.series.startsWith("L4")) {
                    score += 20;
                }
                if (f1) {
                    score -= 40;
                }
            } else {
                if (f1) {
                    score += 50;
                }
                if (f4) {
                    score -= 40;
                }
            }
            if (context.flashSizeKb > 0 && chip.flashSizeKb > 0) {
                int diff = Math.abs(chip.flashSizeKb - context.flashSizeKb);
                if (diff == 0) {
                    score += 30;
                } else if (diff <= 128) {
                    score += 10;
                } else if (diff >= 1024) {
                    score -= 20;
                }
                if (context.flashSizeKb >= 1024 && f4) {
                    score += 15;
                }
                if (context.flashSizeKb <= 512 && f1 && !context.hasExtendedErase) {
                    score += 10;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = chip;
            }
        }
        if (!found) {
            return null;
        }
        return best != null ? best : lookup(pid);
    }
}
