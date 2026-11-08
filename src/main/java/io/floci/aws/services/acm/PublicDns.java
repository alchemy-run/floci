package io.floci.aws.services.acm;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.dns.DnsClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Looks a name's CNAME up in public DNS, the way ACM finds a validation record kept outside
 * Route 53 (a Cloudflare zone, say).
 *
 * <p>The record is asked of the zone's own name servers. A client polls DescribeCertificate from
 * the moment it requests the certificate, before it has published the record, and a recursive
 * resolver would cache that first "no such name" for the zone's negative TTL (30 minutes on
 * Cloudflare), long after the record exists. A lookup that fails answers with no targets, so an
 * offline emulator keeps the certificate pending rather than failing the request.
 *
 * <p>Queries go through Vert.x's DNS client; Quarkus disables JNDI, the JDK's own DNS lookup.
 */
@ApplicationScoped
public class PublicDns {

    private static final Logger LOG = Logger.getLogger(PublicDns.class);
    private static final long TIMEOUT_MILLIS = 1500;

    @Inject
    Vertx vertx;

    /** The CNAME targets of {@code name}, without the trailing dot; empty when it has none. */
    public List<String> cnameTargets(String name) {
        if (vertx == null || Context.isOnEventLoopThread()) {
            // The answer arrives on the event loop, so waiting for it there would never end.
            return List.of();
        }
        String bare = stripDot(name);
        for (String server : authoritativeServers(bare)) {
            List<String> targets = lookup(server, client -> client.resolveCNAME(bare), "CNAME", bare);
            if (!targets.isEmpty()) {
                return targets;
            }
        }
        return lookup(null, client -> client.resolveCNAME(bare), "CNAME", bare);
    }

    /** The name servers of the closest enclosing zone, asked of the recursive resolver. */
    private List<String> authoritativeServers(String name) {
        String candidate = name;
        while (candidate.contains(".")) {
            String zone = candidate.substring(candidate.indexOf('.') + 1);
            candidate = zone;
            // A name with a CNAME is no zone apex, and an NS query for it follows the alias and
            // answers with the name servers of whatever the alias points at.
            if (!lookup(null, client -> client.resolveCNAME(zone), "CNAME", zone).isEmpty()) {
                continue;
            }
            List<String> servers = lookup(null, client -> client.resolveNS(zone), "NS", zone);
            if (!servers.isEmpty()) {
                return servers;
            }
        }
        return List.of();
    }

    private List<String> lookup(String server, Function<DnsClient, Future<List<String>>> query,
                                String type, String name) {
        DnsClientOptions options = new DnsClientOptions().setQueryTimeout(TIMEOUT_MILLIS);
        if (server != null) {
            options.setHost(stripDot(server)).setPort(53);
        }
        DnsClient client = vertx.createDnsClient(options);
        try {
            List<String> answers = query.apply(client).toCompletionStage().toCompletableFuture()
                    .get(TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS);
            return answers == null ? List.of() : answers.stream().map(PublicDns::stripDot).toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception exception) {
            LOG.debugv("No {0} record for {1} at {2}: {3}", type, name,
                    server == null ? "the resolver" : server, exception.getMessage());
            return List.of();
        } finally {
            client.close();
        }
    }

    private static String stripDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
