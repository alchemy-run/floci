package io.floci.aws.services.acm;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.dns.DnsClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Looks a name's CNAME up in public DNS, the way ACM finds a validation record kept outside
 * Route 53 (a Cloudflare zone, say).
 *
 * <p>The record is asked of the zone's own name servers. A client polls DescribeCertificate from
 * the moment it requests the certificate, before it has published the record, and a recursive
 * resolver would cache that first "no such name" for the zone's negative TTL (30 minutes on
 * Cloudflare), long after the record exists.
 *
 * <p>No request waits on DNS: {@link #cnameTargets} answers with what the last lookup found and
 * starts a fresh one in the background when that is stale, so a certificate is issued on a poll
 * shortly after its record appears, as on AWS, where validation also takes a while. A lookup
 * that fails finds nothing, so an offline emulator keeps the certificate pending.
 *
 * <p>Queries go through Vert.x's DNS client; Quarkus disables JNDI, the JDK's own DNS lookup.
 */
@ApplicationScoped
public class PublicDns {

    private static final Logger LOG = Logger.getLogger(PublicDns.class);
    private static final long QUERY_TIMEOUT_MILLIS = 1500;
    private static final Duration REFRESH_AFTER = Duration.ofSeconds(2);

    private record Observation(List<String> targets, Instant checkedAt, boolean refreshing) {}

    private final Map<String, Observation> observations = new ConcurrentHashMap<>();

    @Inject
    Vertx vertx;

    /**
     * The CNAME targets of {@code name} the last lookup found, without the trailing dot; empty
     * before the first lookup completes or when it found none.
     */
    public List<String> cnameTargets(String name) {
        String bare = stripDot(name).toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        Observation seen = observations.get(bare);
        boolean stale = seen == null || (!seen.refreshing() && seen.checkedAt().plus(REFRESH_AFTER).isBefore(now));
        if (vertx != null && stale) {
            Observation refreshing = new Observation(seen == null ? List.of() : seen.targets(),
                    seen == null ? now : seen.checkedAt(), true);
            boolean claimed = seen == null
                    ? observations.putIfAbsent(bare, refreshing) == null
                    : observations.replace(bare, seen, refreshing);
            if (claimed) {
                lookUp(bare).onComplete(result -> observations.put(bare, new Observation(
                        result.succeeded() ? result.result() : List.of(), Instant.now(), false)));
            }
        }
        Observation current = observations.get(bare);
        return current == null ? List.of() : current.targets();
    }

    /** Asks the zone's own name servers first, then the recursive resolver. */
    private Future<List<String>> lookUp(String name) {
        return authoritativeServers(name).compose(servers -> askEach(servers, name, 0))
                .compose(targets -> targets.isEmpty()
                        ? query(null, client -> client.resolveCNAME(name), "CNAME", name)
                        : Future.succeededFuture(targets));
    }

    private Future<List<String>> askEach(List<String> servers, String name, int index) {
        if (index >= servers.size()) {
            return Future.succeededFuture(List.of());
        }
        return query(servers.get(index), client -> client.resolveCNAME(name), "CNAME", name)
                .compose(targets -> targets.isEmpty()
                        ? askEach(servers, name, index + 1) : Future.succeededFuture(targets));
    }

    /** The name servers of the closest enclosing zone, asked of the recursive resolver. */
    private Future<List<String>> authoritativeServers(String name) {
        int dot = name.indexOf('.');
        if (dot < 0 || name.indexOf('.', dot + 1) < 0) {
            return Future.succeededFuture(List.of());
        }
        String zone = name.substring(dot + 1);
        // A name with a CNAME is no zone apex, and an NS query for it follows the alias and
        // answers with the name servers of whatever the alias points at.
        return query(null, client -> client.resolveCNAME(zone), "CNAME", zone).compose(aliases -> {
            if (!aliases.isEmpty()) {
                return authoritativeServers(zone);
            }
            return query(null, client -> client.resolveNS(zone), "NS", zone).compose(servers ->
                    servers.isEmpty() ? authoritativeServers(zone) : Future.succeededFuture(servers));
        });
    }

    private Future<List<String>> query(String server, Function<DnsClient, Future<List<String>>> question,
                                       String type, String name) {
        DnsClientOptions options = new DnsClientOptions().setQueryTimeout(QUERY_TIMEOUT_MILLIS);
        if (server != null) {
            options.setHost(stripDot(server)).setPort(53);
        }
        DnsClient client = vertx.createDnsClient(options);
        return question.apply(client)
                .map(answers -> answers == null ? List.<String>of()
                        : answers.stream().map(PublicDns::stripDot).toList())
                .recover(failure -> {
                    LOG.debugv("No {0} record for {1} at {2}: {3}", type, name,
                            server == null ? "the resolver" : server, failure.getMessage());
                    return Future.succeededFuture(List.of());
                })
                .andThen(ignored -> client.close());
    }

    private static String stripDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
