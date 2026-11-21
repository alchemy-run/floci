package io.floci.aws.services.ivschat;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestHost;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.TextWebSocket;
import io.floci.aws.core.common.WebSocketUpgrades;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IVS Chat's messaging endpoint: the WebSocket a client opens with a chat token as its {@code Sec-WebSocket-Protocol},
 * at {@code edge.ivschat.<region>.<domain>} (on AWS {@code wss://edge.ivschat.<region>.amazonaws.com}, on Floci the
 * host {@code edge.ivschat.<region>.localhost.floci.io}, which resolves to 127.0.0.1, on the gateway port), or at
 * {@code /_floci/ivschat/<region>} on any host. The handshake echoes the token, as the service does, and a token
 * that is missing, used or expired is refused with 401.
 */
@ApplicationScoped
public class IvsChatEdge {

    static final String PATH_PREFIX = "/_floci/ivschat/";

    private final IvsChatService service;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;

    @Inject
    public IvsChatEdge(IvsChatService service, ServiceConfigAccess serviceConfigAccess, Vertx vertx) {
        this.service = service;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
    }

    void register(@Observes Router router) {
        router.route("/").handler(context -> handle(context, regionOfHost(RequestHost.of(context.request()))));
        router.route(PATH_PREFIX + ":region").handler(context -> handle(context, context.pathParam("region")));
    }

    /** The Region of an {@code edge.ivschat.<region>.} host, or null for any other host. */
    static String regionOfHost(String host) {
        if (host == null) {
            return null;
        }
        String[] labels = host.toLowerCase().split("\\.");
        if (labels.length < 4 || !"edge".equals(labels[0]) || !"ivschat".equals(labels[1])) {
            return null;
        }
        return AwsRegions.isRegionId(labels[2]) ? labels[2] : null;
    }

    private void handle(RoutingContext context, String region) {
        HttpServerRequest request = context.request();
        if (region == null || !WebSocketUpgrades.isHandshake(request) || !serviceConfigAccess.isEnabled("ivschat")) {
            context.next();
            return;
        }
        String protocols = request.getHeader("Sec-WebSocket-Protocol");
        String token = protocols == null ? null : protocols.split(",")[0].trim();
        String key = request.getHeader("Sec-WebSocket-Key");
        HttpServerResponse response = request.response();
        if (key == null || key.isBlank()) {
            response.setStatusCode(400).end("Missing Sec-WebSocket-Key.");
            return;
        }
        IvsChatService.Grant grant;
        try {
            grant = service.redeem(token, region);
        } catch (AwsException e) {
            response.setStatusCode(e.getHttpStatus()).end(e.getMessage());
            return;
        }
        response.setStatusCode(101)
                .putHeader("Upgrade", "websocket")
                .putHeader("Connection", "Upgrade")
                .putHeader("Sec-WebSocket-Accept", TextWebSocket.accept(key))
                .putHeader("Sec-WebSocket-Protocol", token);
        String ip = request.remoteAddress() == null ? null : request.remoteAddress().hostAddress();
        request.toNetSocket().onSuccess(socket -> {
            AtomicReference<IvsChatService.Connection> connection = new AtomicReference<>();
            TextWebSocket chat = new TextWebSocket(socket,
                    text -> service.receive(connection.get(), text),
                    () -> {
                        if (connection.get() != null) {
                            service.disconnected(connection.get());
                        }
                    });
            connection.set(service.connect(grant, chat, ip));
            long remaining = Duration.between(Instant.now(), grant.sessionExpiry()).toMillis();
            vertx.setTimer(Math.max(1, remaining), ignored -> chat.close(1000, "The session expired."));
        });
    }
}
