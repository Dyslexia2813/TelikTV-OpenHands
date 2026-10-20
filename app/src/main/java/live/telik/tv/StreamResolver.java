package live.telik.tv;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Resolves the live HLS URL for the "Живая природа" channel.
 *
 * <p>Chain: telik.live page -&gt; cdntvmedia player page -&gt; playerjs.php -&gt; signed m3u8.
 * cdntvmedia rejects requests that lack both a telik/cdntvmedia Referer and
 * {@code Sec-Fetch-Dest: iframe}, so both are always sent.
 *
 * <p>HTTP runs over a raw socket rather than {@code HttpURLConnection}: some
 * Android builds silently drop restricted headers such as {@code Referer},
 * which turns an expected 200 into a 403 with no other symptom.
 *
 * <p>Deliberately free of Android imports so it can be exercised on a desktop
 * JVM against the real endpoints.
 */
final class StreamResolver {

    static final String UA =
            "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/90.0.4430.91 Safari/537.36";

    static final String PLAYER_REFERER = "https://telik.live/zhivaya-priroda.html";

    private static final String PLAYER_PAGE = "https://cdntvmedia.com/educat/zhivaya-priroda.php";
    private static final String PLAYER_JS_FALLBACK =
            "https://cdntvmedia.com/players/playerjs.php?ch=2025&sp=1";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_REDIRECTS = 5;

    private static final Pattern IFRAME_SRC =
            Pattern.compile("src=\"([^\"]*playerjs\\.php[^\"]*)\"");
    private static final Pattern GENERATED_FILE =
            Pattern.compile("generatedFile\\s*=\\s*\"([^\"]+)\"");

    private StreamResolver() {
    }

    /**
     * Resolves the stream URL and returns the address the CDN finally serves.
     *
     * <p>Returning the post-redirect URL matters: the playlist lists segments as
     * relative paths ({@code 37/25-05000.ts}) and the 302 target contains a path
     * component that the original URL lacks, so resolving a segment against the
     * original URL yields a 404.
     *
     * @param socketFactory TLS factory used for every request, or null for the
     *                      process default.
     */
    static String resolve(SSLSocketFactory socketFactory) throws Exception {
        String playerUrl = findPlayerUrl(socketFactory);
        String js = fetch(playerUrl, "https://cdntvmedia.com/", "iframe", socketFactory).body;
        Matcher m = GENERATED_FILE.matcher(js);
        if (!m.find()) {
            throw new Exception("generatedFile missing in " + playerUrl);
        }
        List<String> candidates = new ArrayList<>(2);
        for (String part : m.group(1).replace("\\/", "/").split(" or ")) {
            String url = part.trim();
            if (url.startsWith("https://")) {
                candidates.add(url);
            }
        }
        Exception last = null;
        for (String candidate : candidates) {
            try {
                return fetch(candidate, null, null, socketFactory).finalUrl;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("no stream URL found");
    }

    private static String findPlayerUrl(SSLSocketFactory socketFactory) {
        try {
            String page = fetch(PLAYER_PAGE, PLAYER_REFERER, "iframe", socketFactory).body;
            Matcher m = IFRAME_SRC.matcher(page);
            if (m.find()) {
                String src = m.group(1);
                if (src.startsWith("//")) {
                    return "https:" + src;
                }
                if (src.startsWith("/")) {
                    return "https://cdntvmedia.com" + src;
                }
                return src;
            }
        } catch (Exception ignored) {
            // fall through to the known channel id
        }
        return PLAYER_JS_FALLBACK;
    }

    /**
     * Minimal HTTP/1.1 GET with exact control over the wire headers.
     * Follows redirects and transparently decodes gzip.
     */
    static Response fetch(String url, String referer, String secFetchDest,
                          SSLSocketFactory socketFactory) throws Exception {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL parsed = new URL(current);
            boolean https = "https".equalsIgnoreCase(parsed.getProtocol());
            int port = parsed.getPort() != -1 ? parsed.getPort() : (https ? 443 : 80);

            Socket socket = openSocket(parsed.getHost(), port, https, socketFactory);
            try {
                String path = parsed.getPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                if (parsed.getQuery() != null) {
                    path += "?" + parsed.getQuery();
                }

                StringBuilder req = new StringBuilder();
                req.append("GET ").append(path).append(" HTTP/1.1\r\n");
                req.append("Host: ").append(parsed.getHost());
                if (parsed.getPort() != -1) {
                    req.append(':').append(parsed.getPort());
                }
                req.append("\r\n");
                req.append("User-Agent: ").append(UA).append("\r\n");
                req.append("Accept: */*\r\n");
                if (referer != null) {
                    req.append("Referer: ").append(referer).append("\r\n");
                }
                if (secFetchDest != null) {
                    req.append("Sec-Fetch-Dest: ").append(secFetchDest).append("\r\n");
                }
                req.append("Connection: close\r\n\r\n");

                OutputStream out = socket.getOutputStream();
                out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.flush();

                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                String statusLine = readLine(in);
                if (statusLine == null) {
                    throw new IOException("empty response from " + current);
                }
                int status = parseStatus(statusLine);
                String location = null;
                boolean chunked = false;
                boolean gzipped = false;
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String value = line.substring(colon + 1).trim();
                    if (name.equals("location")) {
                        location = value;
                    } else if (name.equals("transfer-encoding")
                            && value.toLowerCase().contains("chunked")) {
                        chunked = true;
                    } else if (name.equals("content-encoding")
                            && value.toLowerCase().contains("gzip")) {
                        gzipped = true;
                    }
                }

                if (status >= 300 && status < 400 && location != null) {
                    current = new URL(parsed, location).toString();
                    continue;
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status + " for " + current);
                }
                InputStream body = chunked ? new ChunkedInputStream(in) : in;
                if (gzipped) {
                    body = new GZIPInputStream(body);
                }
                return new Response(readAll(body), current);
            } finally {
                closeQuietly(socket);
            }
        }
        throw new IOException("too many redirects for " + url);
    }

    /** Body plus the URL it was actually served from. */
    static final class Response {
        final String body;
        final String finalUrl;

        Response(String body, String finalUrl) {
            this.body = body;
            this.finalUrl = finalUrl;
        }
    }

    /** Decodes HTTP/1.1 chunked transfer encoding. */
    private static final class ChunkedInputStream extends InputStream {

        private final InputStream in;
        private int remaining;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            if (!advance()) {
                return -1;
            }
            remaining--;
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (!advance()) {
                return -1;
            }
            int toRead = Math.min(length, remaining);
            int read = in.read(buffer, offset, toRead);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        /** @return true when bytes remain in the current chunk. */
        private boolean advance() throws IOException {
            if (remaining > 0) {
                return true;
            }
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                return false;
            }
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) {
                sizeLine = sizeLine.substring(0, semicolon);
            }
            try {
                remaining = Integer.parseInt(sizeLine.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + sizeLine);
            }
            if (remaining <= 0) {
                return false;
            }
            return true;
        }
    }

    private static Socket openSocket(String host, int port, boolean https,
                                     SSLSocketFactory socketFactory) throws IOException {
        if (!https) {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            plain.setSoTimeout(READ_TIMEOUT_MS);
            return plain;
        }
        SSLSocketFactory factory = socketFactory != null
                ? socketFactory
                : (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket();
        ssl.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        ssl.setSoTimeout(READ_TIMEOUT_MS);
        try {
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(Collections.singletonList(new SNIHostName(host)));
            // A raw SSLSocket does not check the hostname unless asked, which
            // would accept any valid certificate for the port.
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
        } catch (Exception ignored) {
            // SNI is best-effort; the handshake below still validates the cert.
        }
        ssl.startHandshake();
        return ssl;
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("bad status line: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad status line: " + statusLine);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return buf.toString("ISO-8859-1");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
