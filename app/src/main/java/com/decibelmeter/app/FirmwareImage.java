package com.decibelmeter.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 固件镜像：一段段「地址 + 数据」的集合。
 *
 * 为什么要分段：Intel HEX 文件里的数据不一定连续（例如应用程序在 0x08000000，
 * 后面又有一段字库在 0x08020000），中间空洞不能当作 0x00 写入，所以按连续区间切成多段，
 * 下载时逐段写入，段内每 256 字节为一个写入块。
 *
 * BIN 文件没有地址信息，必须由用户给出起始地址（一般是 0x08000000），因此只有一个段。
 *
 * 地址用 long 存放无符号 32 位值（0x08000000 这类值在 Java 的 int 里仍是正数，
 * 但 0xF0000000 会变成负数，用 long 更保险）。
 */
final class FirmwareImage {

    /** 一段连续地址的固件数据。 */
    static final class Segment {
        final long address;
        final byte[] data;

        Segment(long address, byte[] data) {
            this.address = address;
            this.data = data;
        }
    }

    /** Intel HEX 解析失败的错误类型，界面据此显示对应语言的提示。 */
    static final int ERR_NO_COLON = 1;
    static final int ERR_BAD_LENGTH = 2;
    static final int ERR_LINE_TOO_LONG = 3;
    static final int ERR_BAD_HEX = 4;
    static final int ERR_DATA_SHORT = 5;
    static final int ERR_CHECKSUM = 6;
    static final int ERR_NO_RECORD = 7;

    static final class HexFormatException extends Exception {
        final int kind;
        final int line;

        HexFormatException(int kind, int line) {
            super("hex line " + line + " kind " + kind);
            this.kind = kind;
            this.line = line;
        }
    }

    private final List<Segment> segments = new ArrayList<>();

    int segmentCount() {
        return segments.size();
    }

    Segment segment(int index) {
        return segments.get(index);
    }

    long totalBytes() {
        long sum = 0;
        for (Segment s : segments) {
            sum += s.data.length;
        }
        return sum;
    }

    /** 最小起始地址，用于判断固件是否落在 Flash 范围内。 */
    long minAddress() {
        long min = Long.MAX_VALUE;
        for (Segment s : segments) {
            min = Math.min(min, s.address);
        }
        return segments.isEmpty() ? 0 : min;
    }

    /** 最大结束地址（不含），即最后一段的 address + size。 */
    long maxEndAddress() {
        long max = 0;
        for (Segment s : segments) {
            max = Math.max(max, s.address + s.data.length);
        }
        return max;
    }

    /**
     * 追加一段数据；若地址正好接在上一段末尾（0x00 记录的地址是 16 位偏移，
     * 由扩展线性地址记录提供高 16 位），直接并入上一段，避免产生大量碎段。
     */
    void append(long address, byte[] data, int length) {
        if (length <= 0) {
            return;
        }
        if (!segments.isEmpty()) {
            Segment last = segments.get(segments.size() - 1);
            if (last.address + last.data.length == address) {
                byte[] merged = new byte[last.data.length + length];
                System.arraycopy(last.data, 0, merged, 0, last.data.length);
                System.arraycopy(data, 0, merged, last.data.length, length);
                segments.set(segments.size() - 1, new Segment(last.address, merged));
                return;
            }
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        segments.add(new Segment(address, copy));
    }

    /** BIN 文件：整块数据放到指定起始地址。 */
    static FirmwareImage fromBin(byte[] data, long startAddress) {
        FirmwareImage image = new FirmwareImage();
        image.append(startAddress, data, data.length);
        return image;
    }

    /** 从源数据第 from 个字节起取 length 个字节。 */
    private static byte[] slice(byte[] src, int from, int length) {
        byte[] part = new byte[length];
        System.arraycopy(src, from, part, 0, length);
        return part;
    }

    private static int nibble(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }

    /**
     * 解析 Intel HEX 文本。
     *
     * 每行格式：':'+长度(2)+地址(4)+类型(2)+数据(2*长度)+校验和(2)
     * 类型 00=数据记录，01=文件结束，02=段地址(旧 8086 方式)，04=扩展线性地址(高 16 位)，05=起始地址。
     * 校验规则：长度+地址+类型+数据+校验和 所有字节相加，低 8 位应为 0。
     */
    static FirmwareImage parseIntelHex(String text) throws HexFormatException {
        FirmwareImage image = new FirmwareImage();
        long base = 0;
        int lineNumber = 0;

        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            lineNumber++;
            if (line.charAt(0) != ':') {
                throw new HexFormatException(ERR_NO_COLON, lineNumber);
            }
            String body = line.substring(1);
            if (body.length() < 10 || (body.length() % 2) != 0) {
                throw new HexFormatException(ERR_BAD_LENGTH, lineNumber);
            }
            if (body.length() / 2 > 260) {
                throw new HexFormatException(ERR_LINE_TOO_LONG, lineNumber);
            }
            byte[] record = new byte[body.length() / 2];
            for (int i = 0; i < record.length; i++) {
                int hi = nibble(body.charAt(i * 2));
                int lo = nibble(body.charAt(i * 2 + 1));
                if (hi < 0 || lo < 0) {
                    throw new HexFormatException(ERR_BAD_HEX, lineNumber);
                }
                record[i] = (byte) ((hi << 4) | lo);
            }

            int dataLength = record[0] & 0xFF;
            int offset = (record[1] & 0xFF) << 8 | (record[2] & 0xFF);
            int type = record[3] & 0xFF;
            if (record.length < dataLength + 5) {
                throw new HexFormatException(ERR_DATA_SHORT, lineNumber);
            }
            int sum = 0;
            for (int i = 0; i < dataLength + 5; i++) {
                sum += record[i] & 0xFF;
            }
            if ((sum & 0xFF) != 0) {
                throw new HexFormatException(ERR_CHECKSUM, lineNumber);
            }

            if (type == 0x00) {
                image.append(base + offset, slice(record, 4, dataLength), dataLength);
            } else if (type == 0x01) {
                break;
            } else if (type == 0x02) {
                base = (((record[4] & 0xFF) << 8 | (record[5] & 0xFF)) << 4) & 0xFFFFFFFFL;
            } else if (type == 0x04) {
                base = (((record[4] & 0xFF) << 8 | (record[5] & 0xFF)) << 16) & 0xFFFFFFFFL;
            }
            // 类型 05（起始执行地址）与烧写无关，忽略
        }

        if (image.segmentCount() == 0) {
            throw new HexFormatException(ERR_NO_RECORD, lineNumber);
        }
        return image;
    }

    private static String byteHex(int b) {
        return String.format(java.util.Locale.US, "%02X", b & 0xFF);
    }

    /**
     * 输出一行 HEX 记录（type=类型，offset=16 位地址，data=数据）。
     * 校验和 = 0x100 - 前面所有字节之和的低 8 位。
     */
    private static void hexLine(StringBuilder out, int type, int offset, byte[] data, int length) {
        int sum = length + ((offset >> 8) & 0xFF) + (offset & 0xFF) + type;
        out.append(':').append(byteHex(length)).append(byteHex((offset >> 8) & 0xFF))
                .append(byteHex(offset & 0xFF)).append(byteHex(type));
        for (int i = 0; i < length; i++) {
            out.append(byteHex(data[i]));
            sum += data[i] & 0xFF;
        }
        out.append(byteHex((0x100 - sum) & 0xFF)).append('\n');
    }

    /** 把镜像导出成 Intel HEX 文本（读回 Flash 保存为文件时用），每条数据记录 16 字节。 */
    String toIntelHex() {
        StringBuilder out = new StringBuilder(segments.size() * 1024 + 64);
        int lastEla = -1;
        for (Segment seg : segments) {
            int offset = 0;
            while (offset < seg.data.length) {
                long absolute = seg.address + offset;
                int ela = (int) ((absolute >> 16) & 0xFFFF);
                if (ela != lastEla) {
                    byte[] ext = {(byte) (ela >> 8), (byte) (ela & 0xFF)};
                    hexLine(out, 0x04, 0, ext, 2);
                    lastEla = ela;
                }
                int chunk = Math.min(16, seg.data.length - offset);
                byte[] part = new byte[chunk];
                System.arraycopy(seg.data, offset, part, 0, chunk);
                hexLine(out, 0x00, (int) (absolute & 0xFFFF), part, chunk);
                offset += chunk;
            }
        }
        hexLine(out, 0x01, 0, new byte[0], 0);
        return out.toString();
    }
}
