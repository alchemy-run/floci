package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.SsrfProtection;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * HTTP/1.1 transport for CloudFront custom origins that validates and pins each DNS resolution to
 * the addresses used by the connection. Keeping the logical hostname in the request URI preserves
 * the origin Host header, TLS SNI, and certificate hostname verification.
 */
final class CloudFrontOriginHttpClient implements AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final CloseableHttpClient client;

    CloudFrontOriginHttpClient(Collection<String> allowedPrivateOriginHosts) {
        this(SystemDefaultDnsResolver.INSTANCE, allowedPrivateOriginHosts, null);
    }

    CloudFrontOriginHttpClient(DnsResolver delegate, Collection<String> allowedPrivateOriginHosts) {
        this(delegate, allowedPrivateOriginHosts, null);
    }

    CloudFrontOriginHttpClient(
            DnsResolver delegate,
            Collection<String> allowedPrivateOriginHosts,
            SSLContext sslContext) {
        Set<String> allowedHosts = allowedPrivateOriginHosts.stream()
                .map(CloudFrontServingController::normalizeHost)
                .collect(Collectors.toUnmodifiableSet());
        DnsResolver validatingResolver = new ValidatingDnsResolver(delegate, allowedHosts);

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setSocketTimeout(Timeout.of(RESPONSE_TIMEOUT))
                .build();
        TlsConfig tlsConfig = TlsConfig.custom()
                .setHandshakeTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
                .build();
        PoolingHttpClientConnectionManagerBuilder connectionManagerBuilder =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(validatingResolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .setDefaultTlsConfig(tlsConfig)
                        .setMaxConnTotal(100)
                        .setMaxConnPerRoute(20);
        if (sslContext != null) {
            connectionManagerBuilder.setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    .buildClassic());
        }
        PoolingHttpClientConnectionManager connectionManager = connectionManagerBuilder.build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.of(RESPONSE_TIMEOUT))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .build();

        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableContentCompression()
                .disableCookieManagement()
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .build();
    }

    static boolean isLocalDevOrigin(String authority) {
        if (authority == null) {
            return false;
        }
        try {
            URI parsed = URI.create("http://" + authority);
            return authority.equals(parsed.getRawAuthority())
                    && parsed.getRawUserInfo() == null
                    && parsed.getHost() != null
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(parsed.getHost().toLowerCase(Locale.ROOT))
                    && parsed.getPort() > 0 && parsed.getPort() <= 65535;
        } catch (IllegalArgumentException ignored) {
            // Malformed authorities never receive the local-origin exception.
            return false;
        }
    }

    static CloudFrontOriginHttpClient forLocalDevOrigin(String authority, String targetHost) {
        return forLocalDevOrigin(SystemDefaultDnsResolver.INSTANCE, authority, targetHost);
    }

    static CloudFrontOriginHttpClient forLocalDevOrigin(DnsResolver resolver, String authority, String targetHost) {
        if (!isLocalDevOrigin(authority)) {
            throw new IllegalArgumentException("A local dev origin requires a loopback host and explicit port");
        }
        String originHost = CloudFrontServingController.normalizeOriginHost(authority);
        String target = CloudFrontServingController.normalizeHost(targetHost);
        // Isolated from the public-origin pool; only the emulator's selected host is reachable.
        return new CloudFrontOriginHttpClient(
                new LocalDevDnsResolver(resolver, target, !originHost.equals(target)), List.of(target));
    }

    HttpResponse<byte[]> send(HttpRequest request, HttpResponse.BodyHandler<byte[]> bodyHandler)
            throws IOException, InterruptedException {
        return send(request, Map.of(), bodyHandler);
    }

    HttpResponse<byte[]> send(HttpRequest request, Map<String, String> originHeaders,
                              HttpResponse.BodyHandler<byte[]> bodyHandler)
            throws IOException, InterruptedException {
        return send(request, originHeaders, bodyHandler, null);
    }

    HttpResponse<byte[]> send(HttpRequest request, Map<String, String> originHeaders,
                              HttpResponse.BodyHandler<byte[]> bodyHandler, byte[] requestBody)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("CloudFront origin request interrupted");
        }
        if (request.bodyPublisher().filter(publisher -> publisher.contentLength() != 0).isPresent()) {
            throw new IOException("CloudFront origin request bodies are not supported");
        }

        ClassicRequestBuilder builder = ClassicRequestBuilder.create(request.method())
                .setUri(request.uri())
                .setVersion(HttpVersion.HTTP_1_1);
        request.headers().map().forEach((name, values) ->
                values.forEach(value -> builder.addHeader(name, value)));
        if (requestBody != null && requestBody.length > 0) {
            builder.setEntity(requestBody, ContentType.APPLICATION_OCTET_STREAM);
        }
        originHeaders.forEach(builder::setHeader);

        HttpClientContext context = HttpClientContext.create();
        Duration responseTimeout = request.timeout().orElse(RESPONSE_TIMEOUT);
        context.setRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.of(responseTimeout))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .build());

        try {
            return client.execute(builder.build(), context, response -> {
                Map<String, List<String>> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (Header header : response.getHeaders()) {
                    responseHeaders.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                            .add(header.getValue());
                }
                byte[] body = response.getEntity() != null
                        ? EntityUtils.toByteArray(response.getEntity())
                        : new byte[0];
                return new PinnedHttpResponse(
                        response.getCode(), request,
                        HttpHeaders.of(responseHeaders, (name, value) -> true),
                        body, request.uri());
            });
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("CloudFront origin request interrupted");
            }
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private record PinnedHttpResponse(
            int statusCode,
            HttpRequest request,
            HttpHeaders headers,
            byte[] body,
            URI uri) implements HttpResponse<byte[]> {

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private record LocalDevDnsResolver(DnsResolver delegate, String targetHost, boolean translated)
            implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!targetHost.equals(CloudFrontServingController.normalizeHost(host))) {
                throw new UnknownHostException("Host is outside the local dev origin");
            }
            InetAddress[] addresses = delegate.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException("Local dev origin host has no addresses: " + targetHost);
            }
            for (InetAddress address : addresses) {
                boolean allowed = address.isLoopbackAddress()
                        || (translated && (address.isSiteLocalAddress() || !SsrfProtection.isBlockedAddress(address)));
                if (!allowed) {
                    throw new UnknownHostException("Local dev origin host resolves to a blocked address: " + targetHost);
                }
            }
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return CloudFrontServingController.normalizeHost(host);
        }
    }

    private record ValidatingDnsResolver(DnsResolver delegate, Set<String> allowedHosts)
            implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = delegate.resolve(host);
            String normalizedHost = CloudFrontServingController.normalizeHost(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException("CloudFront origin host has no addresses: " + normalizedHost);
            }
            if (!allowedHosts.contains(normalizedHost)) {
                for (InetAddress address : addresses) {
                    if (SsrfProtection.isBlockedAddress(address)) {
                        throw new UnknownHostException(
                                "CloudFront origin host resolves to a blocked address: " + normalizedHost);
                    }
                }
            }
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return CloudFrontServingController.normalizeHost(host);
        }
    }
}
