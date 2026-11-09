package io.floci.aws.services.cloudfront;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsProxyServer;
import io.quarkus.runtime.Startup;
import io.quarkus.vertx.http.HttpServer;
import io.vertx.core.Vertx;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.Function;

/**
 * HTTPS for a distribution's alternate domain names. AWS serves a distribution's
 * {@code ViewerCertificate.ACMCertificateArn} to viewers that ask for one of its aliases; Floci's
 * own certificate only covers its endpoint hosts, so a browser opening {@code https://<alias>/}
 * would be refused. This server holds the TLS proxy's ports for the names a distribution claims,
 * presents that distribution's certificate, and relays the decrypted request to Floci's HTTPS
 * listener, where {@link CloudFrontDistributionFilter} routes it by its {@code Host} like any other
 * viewer request.
 */
@Startup
@ApplicationScoped
public class CloudFrontViewerTls {

    private static final Logger LOG = Logger.getLogger(CloudFrontViewerTls.class);

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final CloudFrontService service;
    private final TlsProxyServer tlsProxyServer;
    private final HttpServer httpServer;

    private NetServer server;
    private NetClient client;
    private Function<String, Integer> handoff;

    @Inject
    public CloudFrontViewerTls(Vertx vertx, EmulatorConfig config, CloudFrontService service,
                               TlsProxyServer tlsProxyServer, HttpServer httpServer) {
        this.vertx = vertx;
        this.config = config;
        this.service = service;
        this.tlsProxyServer = tlsProxyServer;
        this.httpServer = httpServer;
    }

    @PostConstruct
    void start() {
        if (!config.tls().enabled() || !config.services().cloudfront().enabled()) {
            return;
        }
        NetServerOptions options = new NetServerOptions()
                .setHost("127.0.0.1")
                .setPort(0)
                .setSsl(true)
                .setSslEngineOptions(new JdkSSLEngineOptions())
                .setKeyCertOptions(KeyCertOptions.wrap(
                        new CloudFrontViewerKeyManager(vertx, service::viewerCertificateFor)));
        client = vertx.createNetClient(new NetClientOptions()
                .setSsl(true)
                .setTrustAll(true)
                .setHostnameVerificationAlgorithm(""));
        server = vertx.createNetServer(options);
        server.connectHandler(this::relay);
        server.listen().onSuccess(listening -> {
            int port = listening.actualPort();
            handoff = name -> service.viewerCertificateFor(name) != null ? port : null;
            tlsProxyServer.shareTlsByServerName(handoff);
        }).onFailure(err -> LOG.warnv("CloudFront viewer TLS failed to start: {0}", err.getMessage()));
    }

    /** The loopback port viewer TLS is terminated on, or -1 when it is not running. */
    public int port() {
        return server == null ? -1 : server.actualPort();
    }

    private void relay(NetSocket viewer) {
        viewer.pause();
        client.connect(httpServer.getSecurePort(), "127.0.0.1").onComplete(connected -> {
            if (connected.failed()) {
                LOG.debugv("CloudFront viewer TLS: Floci's HTTPS listener refused the relay: {0}",
                        connected.cause().getMessage());
                viewer.close();
                return;
            }
            NetSocket floci = connected.result();
            viewer.pipeTo(floci);
            floci.pipeTo(viewer);
        });
    }

    @PreDestroy
    void stop() {
        if (handoff != null) {
            tlsProxyServer.unshareTlsByServerName(handoff);
        }
        if (server != null) {
            server.close();
        }
        if (client != null) {
            client.close();
        }
    }
}
