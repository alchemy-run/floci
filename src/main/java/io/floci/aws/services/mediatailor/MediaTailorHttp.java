package io.floci.aws.services.mediatailor;

import io.floci.aws.config.EmulatorConfig;
import io.quarkus.vertx.http.HttpServer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The HTTP requests MediaTailor makes: origin manifests, source manifests, ad decision server requests, ad creatives and
 * beacons. A URL on one of Floci's own hosts ({@code localhost}, {@code *.localhost.floci.io}, Floci's hostname) is
 * requested over loopback with its host in the Host header, so a MediaPackage egress domain or an S3 bucket host
 * reaches Floci however DNS resolves; any other URL is requested as is.
 */
@ApplicationScoped
public class MediaTailorHttp {

    /** A response: its status, body and content type. */
    record Fetched(int status, byte[] body, String contentType, String url) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** A request that got no response: the host was unknown or unreachable, or it timed out. */
    static final class Unreachable extends RuntimeException {
        final boolean timeout;

        Unreachable(String message, boolean timeout) {
            super(message);
            this.timeout = timeout;
        }
    }

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final HttpServer gateway;
    private HttpClient client;

    @Inject
    public MediaTailorHttp(Vertx vertx, EmulatorConfig config, HttpServer gateway) {
        this.vertx = vertx;
        this.config = config;
        this.gateway = gateway;
    }

    @PostConstruct
    void start() {
        client = vertx.createHttpClient(new HttpClientOptions().setTrustAll(false).setVerifyHost(true)
                .setMaxPoolSize(32).setKeepAlive(true));
    }

    @PreDestroy
    void stop() {
        if (client != null) {
            client.close();
        }
    }

    private long timeout() {
        return config.services().mediaTailor().requestTimeoutMillis();
    }

    /** Whether a URL is Floci's own: one of its hosts, on the port it's advertised or listens on. */
    boolean local(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        URI base = URI.create(config.effectiveBaseUrl());
        String configured = config.hostname().orElse(null);
        boolean flociHost = List.of("localhost", "127.0.0.1", "[::1]").contains(lower)
                || lower.equals(MediaTailorStore.DNS_SUFFIX) || lower.endsWith("." + MediaTailorStore.DNS_SUFFIX)
                || configured != null && (lower.equals(configured) || lower.endsWith("." + configured))
                || base.getHost() != null && (lower.equals(base.getHost()) || lower.endsWith("." + base.getHost()));
        if (!flociHost) {
            return false;
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        int advertised = base.getPort() >= 0 ? base.getPort() : "https".equalsIgnoreCase(base.getScheme()) ? 443 : 80;
        return port == advertised || port == gatewayPort() || port == config.port();
    }

    private int gatewayPort() {
        int actual = gateway.getPort();
        return actual > 0 ? actual : config.port();
    }

    Fetched get(String url) {
        return request("GET", url, Map.of(), 5);
    }

    Fetched get(String url, Map<String, String> headers) {
        return request("GET", url, headers, 5);
    }

    private Fetched request(String method, String url, Map<String, String> headers, int redirects) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new Unreachable("The URL " + url + " isn't valid.", false);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new Unreachable("The URL " + url + " isn't an http:// or https:// URL.", false);
        }
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.valueOf(method)).setURI(path)
                .setTimeout(timeout()).setFollowRedirects(false);
        String authority = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        if (local(uri)) {
            options.setHost("127.0.0.1").setPort(gatewayPort()).setSsl(false);
        } else {
            boolean https = scheme.equals("https");
            options.setHost(uri.getHost()).setPort(uri.getPort() < 0 ? (https ? 443 : 80) : uri.getPort())
                    .setSsl(https);
        }
        options.putHeader("Host", authority);
        options.putHeader("User-Agent", "Floci MediaTailor");
        headers.forEach(options::putHeader);
        try {
            Fetched fetched = client.request(options)
                    .compose(request -> request.send())
                    .compose(response -> response.body().map(body -> new Fetched(response.statusCode(),
                            body == null ? new byte[0] : body.getBytes(), response.getHeader("Content-Type"),
                            response.getHeader("Location"))))
                    .toCompletionStage().toCompletableFuture().get(timeout() + 1000, TimeUnit.MILLISECONDS);
            if (fetched.status() >= 300 && fetched.status() < 400 && fetched.url() != null && redirects > 0) {
                return request(method, uri.resolve(fetched.url()).toString(), headers, redirects - 1);
            }
            return new Fetched(fetched.status(), fetched.body(), fetched.contentType(), url);
        } catch (TimeoutException e) {
            throw new Unreachable("The request to " + url + " timed out.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unreachable("The request to " + url + " was interrupted.", false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            boolean timeout = cause.getClass().getSimpleName().contains("Timeout");
            throw new Unreachable("The request to " + url + " failed: " + cause.getMessage(), timeout);
        }
    }

    /** Fires a beacon or tracking URL without waiting for it. */
    void fire(String url, Consumer<Throwable> failed) {
        vertx.executeBlocking(() -> {
            try {
                Fetched fetched = get(url);
                if (fetched.status() >= 400) {
                    failed.accept(new Unreachable("The beacon " + url + " answered " + fetched.status() + ".",
                            false));
                }
            } catch (RuntimeException e) {
                failed.accept(e);
            }
            return null;
        }, false);
    }
}
