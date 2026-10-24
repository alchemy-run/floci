package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.SsrfProtection;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
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
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.util.Timeout;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * HTTP/1.1 transport for CloudFront custom origins that validates and pins each DNS resolution to
 * the addresses used by the connection. Keeping the logical hostname in the request URI preserves
 * the origin Host header, TLS SNI, and certificate hostname verification.
 */
final class CloudFrontOriginHttpClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CloudFrontOriginHttpClient.class);

    private static final String ORIGIN_TIMEOUT_MESSAGE =
            "The origin didn't respond before the request expired.";
    private static final Duration CONNECT_TIMEOUT = CloudFrontOriginTimeouts.DEFAULT_CONNECTION_TIMEOUT;
    private static final Duration RESPONSE_TIMEOUT = CloudFrontOriginTimeouts.DEFAULT_ORIGIN_READ_TIMEOUT;
    /** CloudFront retries a response timeout only for the methods it treats as idempotent. */
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD");
    static final int MAX_CONNECTIONS = 100;

    private final CloseableHttpClient client;

    CloudFrontOriginHttpClient(Collection<String> allowedPrivateOriginHosts) {
        this(SystemDefaultDnsResolver.INSTANCE, allowedPrivateOriginHosts, null);
    }

    CloudFrontOriginHttpClient(DnsResolver delegate, Collection<String> allowedPrivateOriginHosts) {
        this(delegate, allowedPrivateOriginHosts, null);
    }

    /** Reaches Floci's own listener, whose address the private address check refuses. */
    static CloudFrontOriginHttpClient toFloci() {
        return new CloudFrontOriginHttpClient(SystemDefaultDnsResolver.INSTANCE, (SSLContext) null);
    }

    CloudFrontOriginHttpClient(
            DnsResolver delegate,
            Collection<String> allowedPrivateOriginHosts,
            SSLContext sslContext) {
        this(new ValidatingDnsResolver(delegate, allowedPrivateOriginHosts.stream()
                .map(CloudFrontServingController::normalizeHost)
                .collect(Collectors.toUnmodifiableSet())), sslContext);
    }

    private CloudFrontOriginHttpClient(DnsResolver resolver, SSLContext sslContext) {
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
                        .setDnsResolver(resolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .setDefaultTlsConfig(tlsConfig)
                        // CloudFront has no per-origin connection limit, and a streamed response holds its
                        // connection for the whole transfer, so one origin may use the whole pool.
                        .setMaxConnTotal(MAX_CONNECTIONS)
                        .setMaxConnPerRoute(MAX_CONNECTIONS);
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

    /**
     * The connection settings of a distribution's origin that one request must honor:
     * {@code Origin.ConnectionAttempts}, {@code Origin.ConnectionTimeout} and the custom origin's
     * {@code OriginReadTimeout}. Read per request, since the client and its pool are shared.
     */
    record OriginTimeouts(Duration connectTimeout, Duration responseTimeout, int connectionAttempts) {
    }

    /**
     * An origin response whose body has not been read. The caller reads {@code body} as it needs it
     * and closes it, which releases the connection. {@code contentLength} is the length the body
     * arrives with, {@code -1} when it comes chunked or with none.
     */
    record OpenResponse(int statusCode, HttpHeaders headers, long contentLength, InputStream body) {
    }

    /**
     * Sends {@code request} with {@code forwardedHeaders} added and {@code requestBody}, when it is
     * not {@code null}, streamed as the entity, with {@code requestBodyLength} as its
     * {@code Content-Length}, or chunked when the length is negative. Each origin header replaces
     * every same-named request or forwarded header, so a viewer cannot repeat a header to smuggle its
     * own value past an origin custom header. The response comes back before its body is read, so
     * neither body has to fit in memory.
     */
    OpenResponse open(HttpRequest request,
                      List<CloudFrontOriginRequestBuilder.Header> forwardedHeaders,
                      Map<String, String> originHeaders, InputStream requestBody, long requestBodyLength)
            throws IOException, InterruptedException {
        return open(request, forwardedHeaders, originHeaders, requestBody, requestBodyLength, null);
    }

    /**
     * Sends {@code request}, honoring {@code timeouts}. A connection attempt counts for every
     * method, so a connect timeout or a refused connection is retried up to the configured
     * connection attempts whatever the method is. A response timeout is retried only for an
     * idempotent method (GET, HEAD), whose request has no body to replay; every other method is
     * attempted once for it, as CloudFront does not retry it. When the attempts run out, a timeout
     * raises {@link CloudFrontOriginTimeoutException} so the viewer is answered 504 rather than
     * 502, while a refused connection propagates unchanged, as a bad gateway.
     */
    OpenResponse open(HttpRequest request,
                      List<CloudFrontOriginRequestBuilder.Header> forwardedHeaders,
                      Map<String, String> originHeaders, InputStream requestBody, long requestBodyLength,
                      OriginTimeouts timeouts)
            throws IOException, InterruptedException {
        OriginTimeouts effective = timeouts != null
                ? timeouts
                : new OriginTimeouts(CONNECT_TIMEOUT, request.timeout().orElse(RESPONSE_TIMEOUT), 1);
        int attempts = Math.max(1, effective.connectionAttempts());
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return send(request, forwardedHeaders, originHeaders, requestBody, requestBodyLength, effective);
            } catch (ConnectTimeoutException e) {
                lastFailure = e;
            } catch (SocketTimeoutException e) {
                if (!idempotent(request.method())) {
                    throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE, e);
                }
                lastFailure = e;
            } catch (ConnectException e) {
                lastFailure = e;
            }
            if (attempt < attempts) {
                LOG.debugv("CloudFront origin connection attempt {0} of {1} failed; retrying",
                        attempt, attempts);
            }
        }
        if (lastFailure instanceof SocketTimeoutException timeout) {
            throw new CloudFrontOriginTimeoutException(ORIGIN_TIMEOUT_MESSAGE, timeout);
        }
        throw lastFailure;
    }

    private OpenResponse send(HttpRequest request,
                              List<CloudFrontOriginRequestBuilder.Header> forwardedHeaders,
                              Map<String, String> originHeaders, InputStream requestBody, long requestBodyLength,
                              OriginTimeouts timeouts)
            throws IOException, InterruptedException {
        // No entity content type: the caller's Content-Type request header is sent as is.
        HttpUriRequestBase originRequest = originRequest(request, forwardedHeaders, originHeaders,
                requestBody != null ? new InputStreamEntity(requestBody, requestBodyLength, null) : null);
        ClassicHttpResponse response;
        try {
            response = client.executeOpen(RoutingSupport.determineHost(originRequest), originRequest,
                    context(timeouts));
        } catch (HttpException e) {
            throw new IOException("CloudFront origin request has no target host", e);
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("CloudFront origin request interrupted");
            }
            throw e;
        }
        try {
            HttpEntity entity = response.getEntity();
            InputStream body = entity != null
                    ? new ResponseBody(entity.getContent(), originRequest, response)
                    : new ResponseBody(InputStream.nullInputStream(), null, response);
            // The entity's length follows the framing the body arrives with; with no entity, as for a
            // HEAD, the header describes the body a GET would get.
            long contentLength = entity != null ? entity.getContentLength() : headerContentLength(response);
            return new OpenResponse(response.getCode(), responseHeaders(response), contentLength, body);
        } catch (IOException | RuntimeException e) {
            originRequest.cancel();
            closeAborted(response);
            throw e;
        }
    }

    private static boolean idempotent(String method) {
        return method != null && IDEMPOTENT_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    /**
     * An origin response body that releases its connection when it is closed. Closed at its end, the
     * connection goes back to the pool. Closed before its end, as when a viewer goes away or a custom
     * error page replaces it, the request is cancelled and the connection closed, since closing the
     * response normally reads the rest of the body to reuse the connection, however long it is.
     */
    private static final class ResponseBody extends FilterInputStream {

        private final HttpUriRequestBase request;
        private final ClassicHttpResponse response;
        private boolean finished;

        ResponseBody(InputStream content, HttpUriRequestBase request, ClassicHttpResponse response) {
            super(content);
            this.request = request;
            this.response = response;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            finished |= b < 0;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            finished |= read < 0;
            return read;
        }

        @Override
        public void close() throws IOException {
            if (finished || request == null) {
                try {
                    super.close();
                } finally {
                    response.close();
                }
                return;
            }
            request.cancel();
            closeAborted(response);
        }
    }

    /** Closes a response whose request was cancelled; reading the rest of it fails, as it should. */
    private static void closeAborted(ClassicHttpResponse response) {
        try {
            response.close();
        } catch (IOException e) {
            LOG.debugv("Closed an unfinished CloudFront origin response: {0}", e.getMessage());
        }
    }

    private static long headerContentLength(ClassicHttpResponse response) {
        Header header = response.getFirstHeader("Content-Length");
        if (header == null) {
            return -1;
        }
        try {
            return Long.parseLong(header.getValue().trim());
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring an invalid custom-origin Content-Length: {0}", e.getMessage());
            return -1;
        }
    }

    private HttpUriRequestBase originRequest(HttpRequest request,
                                             List<CloudFrontOriginRequestBuilder.Header> forwardedHeaders,
                                             Map<String, String> originHeaders, HttpEntity entity)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("CloudFront origin request interrupted");
        }
        if (request.bodyPublisher().filter(publisher -> publisher.contentLength() != 0).isPresent()) {
            throw new IOException("CloudFront origin request bodies must be passed separately");
        }

        // A request that can be cancelled, so a response closed before its end is not read to the end.
        HttpUriRequestBase builder = new HttpUriRequestBase(request.method(), request.uri());
        builder.setVersion(HttpVersion.HTTP_1_1);
        Set<String> replaced = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        replaced.addAll(originHeaders.keySet());
        request.headers().map().forEach((name, values) -> {
            if (!replaced.contains(name)) {
                values.forEach(value -> builder.addHeader(name, value));
            }
        });
        for (CloudFrontOriginRequestBuilder.Header header : forwardedHeaders) {
            if (!replaced.contains(header.name())) {
                builder.addHeader(header.name(), header.value());
            }
        }
        originHeaders.forEach(builder::addHeader);
        if (entity != null) {
            builder.setEntity(entity);
        }
        return builder;
    }

    private static HttpClientContext context(OriginTimeouts timeouts) {
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.of(timeouts.connectTimeout()))
                // Waiting for a free pooled connection is emulator-side backpressure, not an origin
                // setting, so it keeps the fixed default rather than the origin's connect timeout.
                .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setResponseTimeout(Timeout.of(timeouts.responseTimeout()))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .build());
        return context;
    }

    private static HttpHeaders responseHeaders(ClassicHttpResponse response) {
        Map<String, List<String>> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : response.getHeaders()) {
            responseHeaders.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                    .add(header.getValue());
        }
        return HttpHeaders.of(responseHeaders, (name, value) -> true);
    }

    @Override
    public void close() throws IOException {
        client.close();
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
                        || (translated && !SsrfProtection.isMetadataAddress(address)
                                && (isPrivateAddress(address) || !SsrfProtection.isBlockedAddress(address)));
                if (!allowed) {
                    throw new UnknownHostException("Local dev origin host resolves to a blocked address: " + targetHost);
                }
            }
            return addresses.clone();
        }

        /**
         * IPv4 private or IPv6 unique-local ({@code fc00::/7}). Docker Desktop's host gateway
         * resolves {@code host.docker.internal} to both, e.g. {@code fdc4:f303:9324::254}.
         */
        private static boolean isPrivateAddress(InetAddress address) {
            byte[] bytes = address.getAddress();
            return address.isSiteLocalAddress()
                    || (bytes.length == 16 && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return CloudFrontServingController.normalizeHost(host);
        }
    }
}
