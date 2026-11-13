package io.floci.aws.services.mediapackagev2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MediaPackage v2's ingest endpoints, as Vert.x routes ahead of JAX-RS: an encoder writes HLS segments and playlists
 * with chunked {@code PUT}s (or {@code POST}s) and removes them with {@code DELETE}, each body read whole before it
 * is ingested. Reached path-style ({@code /_floci/mediapackagev2/ingest/<region>/<id>-<n>/in/v1/...}) or at the
 * ingest host, which {@link MediaPackageHostFilter} cannot rewrite before a Vert.x route, so the route reads it too.
 */
@ApplicationScoped
public class MediaPackageIngestRoute {

    private static final Logger LOG = Logger.getLogger(MediaPackageIngestRoute.class);
    private static final Pattern PATH = Pattern.compile(
            "^" + Pattern.quote(MediaPackageOrigin.PATH) + "/ingest/([a-z0-9-]+)/([A-Za-z0-9-]+)"
                    + "(/in/v1/[^/]+/[0-9]+/[^/]+/.+)$");
    private static final Pattern INGEST = Pattern.compile("^/in/v1/([^/]+)/([0-9]+)/([^/]+)/(.+)$");
    private static final Pattern HOST = Pattern.compile(
            "(?i)([a-z0-9-]+)\\.ingest\\.mediapackagev2\\.([a-z0-9-]+)\\..*");

    private final MediaPackageOrigin origin;
    private final MediaPackageOriginController controller;

    @Inject
    public MediaPackageIngestRoute(MediaPackageOrigin origin, MediaPackageOriginController controller) {
        this.origin = origin;
        this.controller = controller;
    }

    void register(@Observes Router router) {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.PUT, HttpMethod.POST, HttpMethod.DELETE}) {
            router.routeWithRegex(method, "^(" + Pattern.quote(MediaPackageOrigin.PATH) + "/ingest/.*|/in/v1/.*)$")
                    .order(-11).handler(this::route);
        }
    }

    private void route(RoutingContext context) {
        String path = context.request().path();
        String region;
        String hostId;
        String rest;
        Matcher styled = PATH.matcher(path);
        if (styled.matches()) {
            region = styled.group(1);
            hostId = styled.group(2);
            rest = styled.group(3);
        } else {
            String host = context.request().authority() == null ? "" : context.request().authority().host();
            Matcher matcher = HOST.matcher(host);
            if (!matcher.matches()) {
                context.next();
                return;
            }
            hostId = matcher.group(1);
            region = matcher.group(2);
            rest = path;
        }
        Matcher ingest = INGEST.matcher(rest);
        if (!ingest.matches()) {
            context.response().setStatusCode(404).end();
            return;
        }
        String group = ingest.group(1);
        int index = Integer.parseInt(ingest.group(2));
        String channel = ingest.group(3);
        String file = ingest.group(4);
        if (context.request().method() == HttpMethod.DELETE) {
            handle(context, region, hostId, group, index, channel, file, null);
            return;
        }
        Buffer body = Buffer.buffer();
        context.request().handler(body::appendBuffer);
        context.request().exceptionHandler(failure -> {
            if (!context.response().ended()) {
                context.response().setStatusCode(400).end("The body couldn't be read: " + failure.getMessage());
            }
        });
        context.request().endHandler(done -> handle(context, region, hostId, group, index, channel, file,
                body.getBytes()));
        context.request().resume();
    }

    private void handle(RoutingContext context, String region, String hostId, String group, int index,
                        String channel, String file, byte[] body) {
        context.vertx().executeBlocking(() -> {
            AccountAwareStorageBackend.AccountEntry<ObjectNode> entry = controller.channel(region, hostId, group,
                    channel);
            if (index < 1 || index > 2) {
                throw MediaPackageStore.notFound("CHANNEL", "The ingest endpoint " + index + " doesn't exist.");
            }
            RequestScopes.runAs(entry.accountId(), region, () -> {
                if (body == null) {
                    origin.remove(entry.accountId(), region, group, channel, index, file);
                } else {
                    origin.ingest(entry.accountId(), region, group, channel, index, file, body);
                }
            });
            return body == null ? 204 : 200;
        }, false).onComplete(result -> {
            if (result.succeeded()) {
                context.response().setStatusCode(result.result()).end();
                return;
            }
            Throwable failure = result.cause();
            if (failure instanceof AwsException aws) {
                context.response().setStatusCode(aws.getHttpStatus()).putHeader("Content-Type", "text/plain")
                        .end(aws.getMessage());
            } else {
                LOG.warnv(failure, "MediaPackage v2 could not ingest {0} of {1}/{2}", file, group, channel);
                context.response().setStatusCode(500).putHeader("Content-Type", "text/plain")
                        .end("The file couldn't be ingested: " + failure.getMessage());
            }
        });
    }
}
