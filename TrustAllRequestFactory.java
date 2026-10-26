package com.wp.wdp.auto.response.event.service.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * LOCAL ONLY. A request factory that skips TLS certificate and hostname
 * validation so internal *.nb01.local endpoints work without importing the
 * corporate CA into the JVM trust store.
 *
 * Shared by RestInvokerConfig (RestTemplate) and ClientConfiguration (RestClient).
 * Never wire this outside the "local" Spring profile.
 */
public final class TrustAllRequestFactory extends SimpleClientHttpRequestFactory {

    private final SSLContext sslContext;

    public TrustAllRequestFactory() {
        this.sslContext = buildTrustAllContext();
    }

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        if (connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(sslContext.getSocketFactory());
            https.setHostnameVerifier((hostname, session) -> true);
        }
        super.prepareConnection(connection, httpMethod);
    }

    private static SSLContext buildTrustAllContext() {
        try {
            TrustManager[] trustAll = { new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // trust everything (local only)
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // trust everything (local only)
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            } };

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to build trust-all SSL context", e);
        }
    }
}
