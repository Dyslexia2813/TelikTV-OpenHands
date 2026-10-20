package live.telik.tv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the live HLS URL for the "Живая природа" channel.
 *
 * Chain: telik.live page -> cdntvmedia player page -> playerjs.php -> signed m3u8.
 * cdntvmedia rejects requests that lack both a telik/cdntvmedia Referer and
 * "Sec-Fetch-Dest: iframe", so both are always sent.
 */
final class StreamResolver {

    private static final String PLAYER_PAGE = "https://cdntvmedia.com/educat/zhivaya-priroda.php";
    private static final String PLAYER_REFERER = "https://telik.live/zhivaya-priroda.html";
    private static final String PLAYER_JS_FALLBACK = "https://cdntvmedia.com/players/playerjs.php?ch=2025&sp=1";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/90.0.4430.91 Mobile Safari/537.36";

    private static final Pattern IFRAME_SRC =
            Pattern.compile("src=\"([^\"]*playerjs\\.php[^\"]*)\"");
    private static final Pattern GENERATED_FILE =
            Pattern.compile("generatedFile\\s*=\\s*\"([^\"]+)\"");

    private StreamResolver() {
    }

    static String resolve() throws Exception {
        String playerUrl = findPlayerUrl();
        String js = fetch(playerUrl, "https://cdntvmedia.com/");
        Matcher m = GENERATED_FILE.matcher(js);
        if (!m.find()) {
            throw new Exception("generatedFile not found");
        }
        String raw = m.group(1).replace("\\/", "/");
        int mirror = raw.indexOf(" or ");
        if (mirror >= 0) {
            raw = raw.substring(0, mirror);
        }
        return raw.trim();
    }

    private static String findPlayerUrl() {
        try {
            String page = fetch(PLAYER_PAGE, PLAYER_REFERER);
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

    private static String fetch(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Sec-Fetch-Dest", "iframe");
            c.setRequestProperty("Accept", "*/*");

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (code != HttpURLConnection.HTTP_OK || in == null) {
                throw new Exception("HTTP " + code + " for " + url);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
