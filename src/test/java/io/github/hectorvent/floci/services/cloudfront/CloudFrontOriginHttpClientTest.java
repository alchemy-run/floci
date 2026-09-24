package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.apache.hc.client5.http.DnsResolver;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFrontOriginHttpClientTest {

    @Test
    void localDevOriginsRequireAnExactLoopbackAuthorityAndExplicitPort() {
        for (String authority : List.of("localhost:5173", "LOCALHOST:5173", "127.0.0.1:80", "[::1]:65535")) {
            assertTrue(CloudFrontOriginHttpClient.isLocalDevOrigin(authority), authority);
        }
        for (String authority : List.of("localhost", "127.0.0.1", "[::1]", "localhost:0", "localhost:65536",
                "localhost:-1", "localhost:", "localhost:abc", "localhost:80/", "localhost:80?x=1",
                "localhost:80#fragment", "user@localhost:80", "localhost.evil.test:80", "localhost.:80",
                "http://localhost:80", "127.1:80", "0.0.0.0:80", "[::]:80", "[::ffff:127.0.0.1]:80",
                "169.254.169.254:80", "10.0.0.1:8080", "host.docker.internal:8080", "example.com:8080")) {
            assertFalse(CloudFrontOriginHttpClient.isLocalDevOrigin(authority), authority);
            assertThrows(IllegalArgumentException.class,
                    () -> CloudFrontOriginHttpClient.forLocalDevOrigin(authority, "127.0.0.1"), authority);
        }
        assertFalse(CloudFrontOriginHttpClient.isLocalDevOrigin(null));
    }

    @Test
    void localDevTransportPinsTheTranslatedHostWithoutRelaxingThePublicClient() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> hostHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        DnsResolver delegate = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                assertEquals("host.docker.internal", host);
                assertEquals(1, resolutions.incrementAndGet());
                return new InetAddress[] { InetAddress.getByName("127.0.0.1") };
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        String authority = "localhost:" + server.getAddress().getPort();
        HttpRequest request = request("http://host.docker.internal:" + server.getAddress().getPort() + "/");
        try (CloudFrontOriginHttpClient local = CloudFrontOriginHttpClient.forLocalDevOrigin(
                     delegate, authority, "host.docker.internal");
             CloudFrontOriginHttpClient ordinary = new CloudFrontOriginHttpClient(
                     resolver(InetAddress.getByName("127.0.0.1")), List.of())) {
            assertEquals(200, local.send(request, Map.of("Host", authority),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode());
            assertEquals(authority, hostHeader.get());
            assertEquals(1, resolutions.get());
            assertThrows(UnknownHostException.class,
                    () -> ordinary.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertThrows(UnknownHostException.class, () -> local.send(
                    request("http://other.invalid:8080/"), HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(1, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localDevTransportRejectsNonLoopbackAnswersAndMetadataTranslations() throws Exception {
        for (String address : List.of("10.0.0.1", "169.254.169.254", "8.8.8.8")) {
            try (CloudFrontOriginHttpClient local = CloudFrontOriginHttpClient.forLocalDevOrigin(
                    resolver(InetAddress.getByName("127.0.0.1"), InetAddress.getByName(address)),
                    "localhost:5173", "localhost")) {
                assertThrows(UnknownHostException.class, () -> local.send(
                        request("http://localhost:5173/"), HttpResponse.BodyHandlers.ofByteArray()));
            }
        }
        try (CloudFrontOriginHttpClient local = CloudFrontOriginHttpClient.forLocalDevOrigin(
                resolver(InetAddress.getByName("192.168.65.254"), InetAddress.getByName("169.254.169.254")),
                "localhost:5173", "host.docker.internal")) {
            assertThrows(UnknownHostException.class, () -> local.send(
                    request("http://host.docker.internal:5173/"), HttpResponse.BodyHandlers.ofByteArray()));
        }
    }

    @Test
    void localDevTransportDoesNotFollowRedirects() throws Exception {
        AtomicInteger redirectedHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectedHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String authority = "localhost:" + server.getAddress().getPort();
        try (CloudFrontOriginHttpClient local = CloudFrontOriginHttpClient.forLocalDevOrigin(
                resolver(InetAddress.getByName("127.0.0.1")), authority, "localhost")) {
            HttpResponse<byte[]> response = local.send(
                    request("http://" + authority + "/start"), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(302, response.statusCode());
            assertEquals("/target", response.headers().firstValue("location").orElseThrow());
            assertEquals(0, redirectedHits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwardsEdgeRequestBodiesThroughThePinnedTransport() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        byte[] body = "edge-body".getBytes(StandardCharsets.UTF_8);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("edge.invalid"))) {
            HttpRequest request = HttpRequest.newBuilder(
                    request("http://edge.invalid:" + server.getAddress().getPort() + "/").uri())
                    .method("POST", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<byte[]> response = client.send(request, Map.of("Content-Type", "text/plain"),
                    HttpResponse.BodyHandlers.ofByteArray(), body);
            assertEquals(204, response.statusCode());
            assertArrayEquals(body, received.get());
            assertEquals("text/plain", contentType.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsPrivateAddressBeforeConnecting() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(hits);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of())) {
            HttpRequest request = request("http://blocked.invalid:" + server.getAddress().getPort() + "/");

            assertThrows(UnknownHostException.class,
                    () -> client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsEveryMixedAnswerContainingABlockedAddress() throws Exception {
        DnsResolver resolver = resolver(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("127.0.0.1"));
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(resolver, List.of())) {
            assertThrows(UnknownHostException.class, () -> client.send(
                    request("http://mixed.invalid:8080/"),
                    HttpResponse.BodyHandlers.ofByteArray()));
        }
    }

    @Test
    void connectsWithTheSingleValidatedResolutionAndPreservesHostHeader() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> hostHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "origin-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AtomicInteger resolutions = new AtomicInteger();
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (resolutions.incrementAndGet() > 1) {
                    return new InetAddress[] { InetAddress.getByName("203.0.113.10") };
                }
                return new InetAddress[] { InetAddress.getByName("127.0.0.1") };
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver, List.of("rebind.invalid"))) {
            HttpResponse<byte[]> response = client.send(
                    request("http://rebind.invalid:" + server.getAddress().getPort() + "/"),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertArrayEquals("origin-ok".getBytes(StandardCharsets.UTF_8), response.body());
            assertEquals(1, resolutions.get());
            assertEquals(1, hits.get());
            assertEquals("rebind.invalid:" + server.getAddress().getPort(), hostHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotFollowOriginRedirects() throws Exception {
        AtomicInteger redirectedHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectedHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("redirect.invalid"))) {
            HttpResponse<byte[]> response = client.send(
                    request("http://redirect.invalid:" + server.getAddress().getPort() + "/start"),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(302, response.statusCode());
            assertEquals(0, redirectedHits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void originHeadersReplaceSameNamedRequestHeaders() throws Exception {
        AtomicReference<List<String>> receivedValues = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedValues.set(exchange.getRequestHeaders().get("X-Origin-Verify"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(
                            "http://origin.invalid:" + server.getAddress().getPort() + "/"))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Origin-Verify", "viewer-value")
                    .GET()
                    .build();

            client.send(
                    request,
                    Map.of("X-Origin-Verify", "configured-value"),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(List.of("configured-value"), receivedValues.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpsPinningPreservesSniAndHostnameVerification() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = generator.generateSelfSignedCertificate(
                "origin.invalid", List.of("origin.invalid"), KeyAlgorithm.RSA_2048);
        X509Certificate certificate = generator.parseCertificate(generated.certificatePem());
        SSLContext serverContext = serverSslContext(
                certificate, generator.parsePrivateKey(generated.privateKeyPem()));
        SSLContext clientContext = clientSslContext(certificate);

        AtomicReference<String> hostHeader = new AtomicReference<>();
        AtomicReference<String> requestedSni = new AtomicReference<>();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            if (exchange instanceof HttpsExchange httpsExchange
                    && httpsExchange.getSSLSession() instanceof ExtendedSSLSession sslSession) {
                for (SNIServerName name : sslSession.getRequestedServerNames()) {
                    if (name instanceof SNIHostName sniHostName) {
                        requestedSni.set(sniHostName.getAsciiName());
                    }
                }
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        DnsResolver resolver = resolver(InetAddress.getByName("127.0.0.1"));
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver, List.of("origin.invalid", "wrong.invalid"), clientContext)) {
            int port = server.getAddress().getPort();
            HttpResponse<byte[]> response = client.send(
                    request("https://origin.invalid:" + port + "/"),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals("origin.invalid:" + port, hostHeader.get());
            assertEquals("origin.invalid", requestedSni.get());
            assertThrows(IOException.class, () -> client.send(
                    request("https://wrong.invalid:" + port + "/"),
                    HttpResponse.BodyHandlers.ofByteArray()));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(AtomicInteger hits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpRequest request(String uri) {
        return HttpRequest.newBuilder(java.net.URI.create(uri))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
    }

    private static DnsResolver resolver(InetAddress... addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static SSLContext serverSslContext(
            X509Certificate certificate, java.security.PrivateKey privateKey) throws Exception {
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("origin", privateKey, password,
                new java.security.cert.Certificate[] { certificate });
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientSslContext(X509Certificate certificate) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("origin", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }
}
