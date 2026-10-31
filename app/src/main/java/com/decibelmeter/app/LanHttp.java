package com.decibelmeter.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 局域网里的轻量 HTTP/HTTPS 读取器，只服务于「识别设备」与 UPnP 描述抓取。
 *
 * 不用 HttpsURLConnection 的原因：
 * <ul>
 *   <li>设备管理页大量使用自签证书，为了读一个标题去改全局 HostnameVerifier 不值得；</li>
 *   <li>需要同时拿到证书主题、状态行与原始响应头；</li>
 *   <li>要严格控制超时与响应体上限，不能让一台坏掉的路由器把识别流程拖住。</li>
 * </ul>
 * 这里信任所有证书仅用于「读公开的设备描述」，不参与登录、下载或任何凭据交换。
 */
final class LanHttp {

    static final class Response {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        String body = "";
        String tlsSubject;
        String error;

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    private LanHttp() {
    }

    static Response get(String host, int port, boolean https, String path,
                        int timeoutMillis, int maxBytes) {
        Response response = new Response();
        Socket socket = null;
        try {
            socket = https ? tlsSocket() : new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                ssl.setUseClientMode(true);
                ssl.startHandshake();
                response.tlsSubject = subjectOf(ssl);
            }
            String request = "GET " + (path == null || path.isEmpty() ? "/" : path)
                    + " HTTP/1.0\r\n"
                    + "Host: " + host + (isDefaultPort(https, port) ? "" : ":" + port) + "\r\n"
                    + "User-Agent: ToolKit-Lan/1.0\r\n"
                    + "Accept: text/html,application/xhtml+xml,application/xml\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(Charset.forName("UTF-8")));
            out.flush();
            byte[] raw = readAll(socket.getInputStream(), maxBytes);
            parse(raw, response);
        } catch (SocketTimeoutException e) {
            response.error = "timeout";
        } catch (Exception e) {
            response.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // 识别用的临时连接，关不掉没有后果
                }
            }
        }
        return response;
    }

    static Response getUrl(String url, int timeoutMillis, int maxBytes) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null
                    ? "http" : uri.getScheme().toLowerCase(Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                Response response = new Response();
                response.error = "unsupported scheme " + scheme;
                return response;
            }
            String host = uri.getHost();
            if (host == null) {
                Response response = new Response();
                response.error = "no host in URL";
                return response;
            }
            int port = uri.getPort() > 0 ? uri.getPort()
                    : (scheme.equals("https") ? 443 : 80);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }
            return get(host, port, scheme.equals("https"), path, timeoutMillis, maxBytes);
        } catch (Exception e) {
            Response response = new Response();
            response.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return response;
        }
    }

    private static boolean isDefaultPort(boolean https, int port) {
        return (https && port == 443) || (!https && port == 80);
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (out.size() < maxBytes) {
            int read = in.read(buffer, 0, Math.min(buffer.length, maxBytes - out.size()));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void parse(byte[] raw, Response response) {
        int split = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n'
                    && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                split = i;
                break;
            }
        }
        int headerEnd = split < 0 ? raw.length : split;
        String headerText = new String(raw, 0, headerEnd, Charset.forName("ISO-8859-1"));
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) {
            response.error = "no response headers";
            return;
        }
        String statusLine = lines[0];
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length >= 2) {
            try {
                response.status = Integer.parseInt(statusParts[1]);
            } catch (NumberFormatException e) {
                response.error = "malformed status line";
                return;
            }
        } else {
            response.error = "malformed status line";
            return;
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            response.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                    lines[i].substring(colon + 1).trim());
        }
        if (split >= 0) {
            byte[] body = new byte[raw.length - split - 4];
            System.arraycopy(raw, split + 4, body, 0, body.length);
            response.body = decode(body, response.header("content-type"));
        }
    }

    static String decode(byte[] body, String contentType) {
        String charsetName = null;
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.US).startsWith("charset=")) {
                    charsetName = trimmed.substring(8).replace("\"", "").trim();
                }
            }
        }
        if (charsetName != null) {
            try {
                return new String(body, Charset.forName(charsetName));
            } catch (Exception e) {
                // 服务端声明了不存在的编码，按下面的兜底试
            }
        }
        String utf8 = new String(body, Charset.forName("UTF-8"));
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        return new String(body, Charset.forName("GB18030"));
    }

    static String title(String html) {
        if (html == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (!matcher.find()) {
            return null;
        }
        String title = matcher.group(1)
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return title.isEmpty() ? null : truncate(title, 90);
    }

    static String truncate(String text, int max) {
        return text != null && text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static String subjectOf(SSLSocket socket) {
        try {
            Certificate[] certificates = socket.getSession().getPeerCertificates();
            if (certificates.length > 0 && certificates[0] instanceof X509Certificate) {
                return ((X509Certificate) certificates[0]).getSubjectX500Principal().getName();
            }
        } catch (Exception e) {
            // 拿不到证书主题不影响读标题
        }
        return null;
    }

    /** 只为读取设备自签证书下的公开页面；不做主机名校验。 */
    private static Socket tlsSocket() throws Exception {
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        return context.getSocketFactory().createSocket();
    }
}
