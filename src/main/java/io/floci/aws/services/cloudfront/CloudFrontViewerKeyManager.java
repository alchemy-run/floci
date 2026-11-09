package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.floci.aws.core.common.tls.ReloadingKeyManager;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemKeyCertOptions;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Chooses, per TLS handshake, the certificate of the distribution the ClientHello's server name
 * belongs to. Aliases are qualified by the certificate they come from, so a key and its chain are
 * always read from the same material even when a distribution's certificate changes mid-handshake.
 */
final class CloudFrontViewerKeyManager extends X509ExtendedKeyManager {

    private static final int MAX_CACHED_CERTIFICATES = 256;

    private final Vertx vertx;
    private final Function<String, CertificateMaterial> certificateFor;
    private final Map<String, X509KeyManager> managers = new ConcurrentHashMap<>();

    CloudFrontViewerKeyManager(Vertx vertx, Function<String, CertificateMaterial> certificateFor) {
        this.vertx = vertx;
        this.certificateFor = certificateFor;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        String host = requestedServerName(engine);
        CertificateMaterial material = host == null ? null : certificateFor.apply(host);
        if (material == null || material.privateKey() == null || material.certificateBody() == null) {
            return null;
        }
        String key = material.arn() + "#" + Integer.toHexString(Objects.hash(
                material.certificateBody(), material.certificateChain(), material.privateKey()));
        X509KeyManager manager = managers.get(key);
        if (manager == null) {
            if (managers.size() >= MAX_CACHED_CERTIFICATES) {
                managers.clear();
            }
            manager = managers.computeIfAbsent(key, k -> keyManagerOf(material));
        }
        String alias = manager instanceof X509ExtendedKeyManager extended
                ? extended.chooseEngineServerAlias(keyType, issuers, engine)
                : manager.chooseServerAlias(keyType, issuers, null);
        return alias == null ? null : key + "\u0000" + alias;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return new String[0];
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        X509KeyManager manager = managerOf(alias);
        return manager == null ? null : manager.getCertificateChain(innerAlias(alias));
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        X509KeyManager manager = managerOf(alias);
        return manager == null ? null : manager.getPrivateKey(innerAlias(alias));
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[0];
    }

    @Override
    public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
        return null;
    }

    private X509KeyManager managerOf(String alias) {
        int separator = alias == null ? -1 : alias.indexOf('\u0000');
        return separator < 0 ? null : managers.get(alias.substring(0, separator));
    }

    private static String innerAlias(String alias) {
        return alias.substring(alias.indexOf('\u0000') + 1);
    }

    private X509KeyManager keyManagerOf(CertificateMaterial material) {
        String chain = material.certificateChain() == null || material.certificateChain().isBlank()
                ? material.certificateBody()
                : material.certificateBody().strip() + "\n" + material.certificateChain().strip() + "\n";
        PemKeyCertOptions options = new PemKeyCertOptions()
                .addKeyValue(Buffer.buffer(material.privateKey()))
                .addCertValue(Buffer.buffer(chain));
        return ReloadingKeyManager.keyManagerOf(vertx, options);
    }

    private static String requestedServerName(SSLEngine engine) {
        if (engine != null && engine.getHandshakeSession() instanceof ExtendedSSLSession session) {
            for (SNIServerName name : session.getRequestedServerNames()) {
                if (name instanceof SNIHostName hostname) {
                    return hostname.getAsciiName().toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }
}
