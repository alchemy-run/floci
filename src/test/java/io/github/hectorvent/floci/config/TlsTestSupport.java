package io.github.hectorvent.floci.config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class TlsTestSupport {

    private TlsTestSupport() {}

    static SSLContext trustOnlyTheCa() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci",
                FlociCertificateAuthority.loadOrCreate(Path.of(SelfSignedTlsProfile.TLS_DIR)).certificate());
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    static Set<String> dnsSans(X509Certificate certificate) throws Exception {
        Set<String> names = new TreeSet<>();
        for (List<?> entry : certificate.getSubjectAlternativeNames()) {
            if (Integer.valueOf(2).equals(entry.get(0))) {
                names.add(String.valueOf(entry.get(1)));
            }
        }
        return names;
    }
}
