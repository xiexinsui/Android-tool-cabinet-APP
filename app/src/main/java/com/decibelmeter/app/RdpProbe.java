package com.decibelmeter.app;

import android.content.Context;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * RDP 安全层探测：发一个 X.224 Connection Request 问服务端「你能用哪种安全层」，读完响应就断开。
 *
 * 为什么值得单独做：内置客户端能不能连上一台机器，第一道分水岭就是它要不要 NLA（网络级身份
 * 验证）。要 NLA 就得实现 CredSSP + NTLM 那一整套，不要的话 TLS 就够——这两条实现路径差一个
 * 数量级，而光看 3389 端口开不开完全看不出来。Windows 的「仅允许网络级身份验证」默认是勾上的，
 * 但虚拟机、老系统、被组策略改过的机器各不相同，只能一台一台问。
 *
 * 报文不是凭记忆写的：19 字节的 client hello 是从本机 mstsc 抓下来的真实包
 * （{@code 03 00 00 13 0e e0 00 00 00 00 00 01 00 08 00 <requestedProtocols LE32>}），
 * 只改最后 4 字节的 requestedProtocols。失败码表按 FreeRDP {@code libfreerdp/core/nego.h} 的
 * 定义抄，不是猜的。
 *
 * 问两次才给结论：只请求 TLS（0x01）看它拒不拒，再请求 TLS|HYBRID|HYBRID_EX（0x0b）看它首选什么。
 * 只问后者会漏掉「TLS 其实也允许」这种情况，因为服务端总是挑权限最高的那个。
 */
public final class RdpProbe {

    /** MS-RDPBCGR 2.2.1.1 的 requestedProtocols。 */
    private static final int PROTOCOL_SSL = 0x01;
    private static final int PROTOCOL_HYBRID = 0x02;
    private static final int PROTOCOL_HYBRID_EX = 0x08;

    /** 一次探测的结论。responded=false 表示连上都没连上。 */
    public static final class Result {
        public final boolean responded;
        /** 服务端在放开请求时选中的协议位（0 表示没谈成）。 */
        public final int selected;
        /** RDP_NEG_FAILURE 的 failureCode，0 表示没有失败。 */
        public final int failureCode;
        /** 只请求 TLS 时是否被接受——决定「这台是否强制 NLA」。 */
        public final boolean tlsAloneOk;
        public final long rttMillis;
        /** 连接层错误（DNS、拒绝、超时）的原文，null 表示没出错。 */
        public final String error;

        Result(boolean responded, int selected, int failureCode, boolean tlsAloneOk,
               long rttMillis, String error) {
            this.responded = responded;
            this.selected = selected;
            this.failureCode = failureCode;
            this.tlsAloneOk = tlsAloneOk;
            this.rttMillis = rttMillis;
            this.error = error;
        }

        static Result failed(String error) {
            return new Result(false, 0, 0, false, 0, error);
        }

        /** 需要 CredSSP/NTLM 才能实现内置连接——这是这个探测最有用的一条结论。 */
        public boolean requiresNla() {
            return responded && !tlsAloneOk;
        }

        /**
         * 一句话结论。协议名保持英文原样（HYBRID、CredSSP 这些是排查时要拿去搜的关键字，
         * 翻译成中文反而没法对照文档），只有句子本身本地化。
         */
        public String describe(Context context) {
            if (!responded) {
                return context.getString(R.string.rdp_probe_failed, error);
            }
            if (failureCode != 0 && selected == 0) {
                return context.getString(R.string.rdp_probe_rejected, failureName(context));
            }
            StringBuilder builder = new StringBuilder();
            builder.append(context.getString(R.string.rdp_probe_ok,
                    protocolName(context, selected), rttMillis));
            builder.append('\n').append(context.getString(
                    requiresNla() ? R.string.rdp_probe_nla_required : R.string.rdp_probe_tls_ok));
            return builder.toString();
        }

        private String failureName(Context context) {
            switch (failureCode) {
                case 1: return "SSL_REQUIRED_BY_SERVER";
                case 2: return "SSL_NOT_ALLOWED_BY_SERVER";
                case 3: return "SSL_CERT_NOT_ON_SERVER";
                case 4: return "INCONSISTENT_FLAGS";
                case 5: return "HYBRID_REQUIRED_BY_SERVER";
                case 6: return "SSL_WITH_USER_AUTH_REQUIRED_BY_SERVER";
                default: return context.getString(R.string.rdp_probe_unknown_code,
                        Integer.toHexString(failureCode));
            }
        }

        private static String protocolName(Context context, int bits) {
            if ((bits & PROTOCOL_HYBRID_EX) != 0) {
                return "HYBRID_EX";
            }
            if ((bits & PROTOCOL_HYBRID) != 0) {
                return "HYBRID";
            }
            if ((bits & PROTOCOL_SSL) != 0) {
                return "SSL";
            }
            return "RDP";
        }
    }

    private RdpProbe() {
    }

    /**
     * 探测一台主机。必须在工作线程调用（两次 TCP 连接，最坏 2 × timeout）。
     */
    public static Result run(String host, int port, int timeoutMillis) {
        Round tls = negotiate(host, port, timeoutMillis, PROTOCOL_SSL);
        if (tls.error != null) {
            return Result.failed(tls.error);
        }
        Round full = negotiate(host, port, timeoutMillis,
                PROTOCOL_SSL | PROTOCOL_HYBRID | PROTOCOL_HYBRID_EX);
        if (full.error != null) {
            return Result.failed(full.error);
        }
        return new Result(true, full.selected, full.failureCode, tls.accepted(),
                full.rttMillis, null);
    }

    /** 一次往返回的原始结论。 */
    private static final class Round {
        int selected;
        int failureCode;
        long rttMillis;
        String error;

        boolean accepted() {
            return error == null && failureCode == 0;
        }
    }

    private static Round negotiate(String host, int port, int timeoutMillis, int requested) {
        Round round = new Round();
        Socket socket = new Socket();
        long started = System.currentTimeMillis();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            byte[] hello = clientHello(requested);
            OutputStream out = socket.getOutputStream();
            out.write(hello);
            out.flush();
            byte[] response = new byte[19];
            DataInputStream in = new DataInputStream(socket.getInputStream());
            in.readFully(response);
            round.rttMillis = System.currentTimeMillis() - started;
            parse(response, round);
        } catch (IOException e) {
            round.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // 探测用的连接，关不掉也没有后果
            }
        }
        return round;
    }

    /** mstsc 的 19 字节 client hello，只换掉末尾的 requestedProtocols。 */
    private static byte[] clientHello(int requestedProtocols) {
        byte[] hello = new byte[]{
                (byte) 0x03, 0x00, 0x00, 0x13,
                0x0e, (byte) 0xe0, 0x00, 0x00, 0x00,
                0x00, 0x00,
                0x01, 0x00, (byte) 0x08, 0x00,
                0x00, 0x00, 0x00, 0x00,
        };
        ByteBuffer.wrap(hello).order(ByteOrder.LITTLE_ENDIAN).putInt(15, requestedProtocols);
        return hello;
    }

    /**
     * 响应布局：TPKT(4) + LI(1) + X.224 CC(6：code + DST-REF + SRC-REF + CLASS) 之后就是
     * RDP_NEG_RSP / RDP_NEG_FAILURE（type(1) flags(1) length(2) 值(4)），所以值在第 15 字节。
     * 偏移少算 4 字节会把 length 当成 code 读，得出一个看着像样但完全错误的结论——踩过一次。
     */
    private static void parse(byte[] response, Round round) {
        if (response.length < 19 || (response[5] & 0xF0) != 0xD0) {
            round.error = "响应不是 X.224 连接确认";
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
        int type = buffer.get(11) & 0xFF;
        if (type == 0x02) {
            round.selected = buffer.getInt(15);
        } else if (type == 0x03) {
            round.failureCode = buffer.getInt(15);
        } else {
            round.error = "未知的协商数据类型 0x" + Integer.toHexString(type);
        }
    }
}
