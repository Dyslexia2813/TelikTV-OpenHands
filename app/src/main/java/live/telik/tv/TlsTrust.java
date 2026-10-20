package live.telik.tv;

import android.content.Context;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Trust store combining the platform roots with the ISRG (Let's Encrypt) roots
 * bundled in {@code res/raw}.
 *
 * <p>The channel's hosts use Let's Encrypt's 2025 chain, which terminates in
 * {@code ISRG Root X1}. Android 7.0 does not ship that root as a trust anchor
 * (it arrived only in 7.1.1), and many TV boxes that advertise a newer version
 * still run a 7.x build. Without these roots every HTTPS request fails during
 * the handshake, which the player surfaces only as a black screen.
 *
 * <p>ExoPlayer goes through {@code HttpURLConnection} and is covered by
 * {@code res/xml/network_security_config.xml}, which declares the same roots.
 * This class serves the resolver's own raw sockets, which bypass that config.
 */
final class TlsTrust {

    private TlsTrust() {
    }

    /** @return a factory trusting platform roots plus the bundled ISRG roots. */
    static SSLSocketFactory socketFactory(Context context) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{buildTrustManager(context)}, null);
        return sslContext.getSocketFactory();
    }

    private static X509TrustManager buildTrustManager(Context context) throws Exception {
        TrustManagerFactory platformFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        platformFactory.init((KeyStore) null);
        final X509TrustManager platform = firstX509(platformFactory.getTrustManagers());

        KeyStore extrasStore = KeyStore.getInstance(KeyStore.getDefaultType());
        extrasStore.load(null, null);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        int[] rootIds = {R.raw.isrg_root_x1, R.raw.isrg_root_x2};
        for (int id : rootIds) {
            try (InputStream in = context.getResources().openRawResource(id)) {
                Certificate cert = certFactory.generateCertificate(in);
                extrasStore.setCertificateEntry(Integer.toString(id), cert);
            }
        }
        TrustManagerFactory extrasFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        extrasFactory.init(extrasStore);
        final X509TrustManager extras = firstX509(extrasFactory.getTrustManagers());

        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                platform.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    platform.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    extras.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] a = platform.getAcceptedIssuers();
                X509Certificate[] b = extras.getAcceptedIssuers();
                X509Certificate[] all = new X509Certificate[a.length + b.length];
                System.arraycopy(a, 0, all, 0, a.length);
                System.arraycopy(b, 0, all, a.length, b.length);
                return all;
            }
        };
    }

    private static X509TrustManager firstX509(TrustManager[] managers) throws CertificateException {
        for (TrustManager manager : managers) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new CertificateException("no X509TrustManager available");
    }
}
