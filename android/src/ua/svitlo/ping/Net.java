package ua.svitlo.ping;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Простий GET-запит, який працює і на старих Android (4.4–7.0): вмикаємо TLS 1.2 та додаємо сучасні кореневі сертифікати. */
final class Net {
    private Net() {}

    static final class Res {
        final int code; final String msg; final boolean ok;
        Res(int code, String msg) { this.code = code; this.msg = msg; this.ok = code >= 200 && code < 300; }
    }

    private static SSLSocketFactory factory;

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return true; }   // не знаємо — спробуємо надіслати
    }

    static Res get(Context c, String url) {
        Res r = once(c, url);
        // дуже старий телефон не зміг встановити захищене з'єднання — пробуємо звичайне http
        if (r.code == -2 && url.startsWith("https://")) {
            Res r2 = once(c, "http://" + url.substring(8));
            if (r2.code > 0) return r2;
        }
        return r;
    }

    private static Res once(Context c, String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            if (con instanceof HttpsURLConnection) {
                SSLSocketFactory f = sslFactory(c);
                if (f != null) ((HttpsURLConnection) con).setSSLSocketFactory(f);
            }
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            con.setUseCaches(false);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "SvitloPing/" + BuildInfo.VERSION + " (Android " + Build.VERSION.RELEASE + ")");
            con.setRequestProperty("Connection", "close");
            int code = con.getResponseCode();
            try { InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream(); if (in != null) { byte[] b = new byte[256]; while (in.read(b) > 0) { } in.close(); } } catch (Exception ignored) { }
            if (code >= 200 && code < 300) return new Res(code, "сервер прийняв");
            if (code == 404) return new Res(code, "черга не знайдена на сервері — перевірте номер черги");
            return new Res(code, "сервер відповів помилкою " + code);
        } catch (UnknownHostException e) {
            return new Res(-1, "немає інтернету (не знайдено адресу сервера)");
        } catch (SSLException e) {
            return new Res(-2, "не вдалося встановити захищене з'єднання");
        } catch (java.net.SocketTimeoutException e) {
            return new Res(-1, "сервер не відповів вчасно (повільний інтернет?)");
        } catch (IOException e) {
            return new Res(-1, "немає інтернету або сервер недоступний");
        } catch (Exception e) {
            return new Res(-1, "помилка: " + e.getClass().getSimpleName());
        } finally {
            if (con != null) try { con.disconnect(); } catch (Exception ignored) { }
        }
    }

    // ---------- TLS для старих телефонів ----------
    private static synchronized SSLSocketFactory sslFactory(Context c) {
        if (factory != null) return factory;
        try {
            TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sys.init((KeyStore) null);
            X509TrustManager sysTm = firstX509(sys.getTrustManagers());

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            InputStream in = c.getResources().openRawResource(R.raw.roots);
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) ks.setCertificateEntry("root" + (i++), cert);
            in.close();
            TrustManagerFactory own = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            own.init(ks);
            X509TrustManager ownTm = firstX509(own.getTrustManagers());

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new Both(sysTm, ownTm) }, null);
            factory = Build.VERSION.SDK_INT < 21 ? new Tls12(ctx.getSocketFactory()) : ctx.getSocketFactory();
        } catch (Exception e) {
            factory = null;   // залишаємо стандартну поведінку системи
        }
        return factory;
    }

    private static X509TrustManager firstX509(TrustManager[] a) {
        for (TrustManager t : a) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        return null;
    }

    /** Довіряємо сертифікату, якщо його визнає система АБО наш набір сучасних кореневих сертифікатів. */
    private static final class Both implements X509TrustManager {
        final X509TrustManager a, b;
        Both(X509TrustManager a, X509TrustManager b) { this.a = a; this.b = b; }
        @Override public void checkClientTrusted(X509Certificate[] ch, String t) throws CertificateException { a.checkClientTrusted(ch, t); }
        @Override public void checkServerTrusted(X509Certificate[] ch, String t) throws CertificateException {
            try { a.checkServerTrusted(ch, t); }
            catch (CertificateException e) { if (b == null) throw e; b.checkServerTrusted(ch, t); }
        }
        @Override public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> l = new ArrayList<>(Arrays.asList(a.getAcceptedIssuers()));
            if (b != null) l.addAll(Arrays.asList(b.getAcceptedIssuers()));
            return l.toArray(new X509Certificate[0]);
        }
    }

    /** Android 4.4: TLS 1.2 є, але вимкнений за замовчуванням — вмикаємо. */
    private static final class Tls12 extends SSLSocketFactory {
        final SSLSocketFactory d;
        Tls12(SSLSocketFactory d) { this.d = d; }
        private Socket on(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> want = new ArrayList<>();
                for (String p : ss.getSupportedProtocols()) if (p.equals("TLSv1.2") || p.equals("TLSv1.1") || p.equals("TLSv1")) want.add(p);
                ss.setEnabledProtocols(want.toArray(new String[0]));
                List<String> cs = new ArrayList<>();
                for (String s2 : ss.getSupportedCipherSuites()) if (!s2.contains("_anon_") && !s2.contains("NULL") && !s2.contains("EXPORT") && !s2.contains("_DES_") && !s2.contains("RC4") && !s2.contains("MD5")) cs.add(s2);
                if (!cs.isEmpty()) ss.setEnabledCipherSuites(cs.toArray(new String[0]));
            }
            return s;
        }
        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return on(d.createSocket(s, h, p, a)); }
        @Override public Socket createSocket(String h, int p) throws IOException { return on(d.createSocket(h, p)); }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return on(d.createSocket(h, p, l, lp)); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return on(d.createSocket(h, p)); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return on(d.createSocket(h, p, l, lp)); }
    }
}
