package io.floci.aws.core.common;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The internet-facing traffic Floci serves for a customer's resources: each request a CloudFront distribution or an
 * Elastic Load Balancing listener answers, and each connection a TCP listener accepts, as an {@link Observation}
 * fired to CDI observers (Internet Monitor measures its monitors' traffic from them).
 */
@ApplicationScoped
public class ViewerTraffic {

    private static final Logger LOG = Logger.getLogger(ViewerTraffic.class);

    /**
     * One request or connection. {@code resources} are the ARNs it reached (a load balancer and its VPC, or a
     * distribution), {@code serviceLocation} the Region that served it or {@code CloudFront}, {@code failed} whether
     * it went unanswered or was answered with a 5xx.
     */
    public record Observation(String accountId, String region, String serviceLocation, List<String> resources,
                              String clientIp, Instant at, long latencyMillis, boolean failed, long bytesIn,
                              long bytesOut) {
    }

    private final Event<Observation> events;

    @Inject
    public ViewerTraffic(Event<Observation> events) {
        this.events = events;
    }

    public void record(Observation observation) {
        try {
            events.fire(observation);
        } catch (RuntimeException e) {
            LOG.debugv(e, "A traffic observation of {0} was not recorded", observation.resources());
        }
    }

    /** Records a request Quarkus routes once its response has been sent, or the client went away. */
    public void observe(RoutingContext context, String accountId, String region, String serviceLocation,
                        List<String> resources) {
        if (context == null) {
            return;
        }
        long started = System.nanoTime();
        HttpServerRequest request = context.request();
        AtomicBoolean done = new AtomicBoolean();
        context.addEndHandler(result -> {
            if (done.compareAndSet(false, true)) {
                HttpServerResponse response = context.response();
                boolean failed = result.failed() || !response.ended() || response.getStatusCode() >= 500;
                record(new Observation(accountId, region, serviceLocation, resources, clientIp(request),
                        Instant.now(), (System.nanoTime() - started) / 1_000_000, failed, request.bytesRead(),
                        response.bytesWritten()));
            }
        });
    }

    /** Records a request a raw Vert.x server answers once its response has ended, or its connection closed. */
    public void observe(HttpServerRequest request, String accountId, String region, String serviceLocation,
                        List<String> resources) {
        long started = System.nanoTime();
        HttpServerResponse response = request.response();
        AtomicBoolean done = new AtomicBoolean();
        Runnable finish = () -> {
            if (done.compareAndSet(false, true)) {
                boolean failed = !response.ended() || response.getStatusCode() >= 500;
                record(new Observation(accountId, region, serviceLocation, resources, clientIp(request),
                        Instant.now(), (System.nanoTime() - started) / 1_000_000, failed, request.bytesRead(),
                        response.bytesWritten()));
            }
        };
        response.endHandler(ignored -> finish.run());
        response.closeHandler(ignored -> finish.run());
    }

    /** The viewer's address: the first {@code X-Forwarded-For} hop a relay added, else the peer. */
    public static String clientIp(HttpServerRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.remoteAddress() == null ? null : request.remoteAddress().hostAddress();
    }
}
