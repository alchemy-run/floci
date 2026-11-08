package io.floci.aws.services.acm;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Looks a name's CNAME up in public DNS, the way ACM finds a validation record kept outside
 * Route 53 (a Cloudflare zone, say).
 *
 * <p>The record is asked of the zone's own name servers. A client polls DescribeCertificate from
 * the moment it requests the certificate, before it has published the record, and a recursive
 * resolver would cache that first "no such name" for the zone's negative TTL (30 minutes on
 * Cloudflare), long after the record exists. A lookup that fails answers with no targets, so an
 * offline emulator keeps the certificate pending rather than failing the request.
 */
@ApplicationScoped
public class PublicDns {

    private static final Logger LOG = Logger.getLogger(PublicDns.class);

    /** The CNAME targets of {@code name}, without the trailing dot; empty when it has none. */
    public List<String> cnameTargets(String name) {
        String bare = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        for (String server : authoritativeServers(bare)) {
            List<String> targets = lookup(bare, "CNAME", server);
            if (!targets.isEmpty()) {
                return targets;
            }
        }
        return lookup(bare, "CNAME", null);
    }

    /** The name servers of the closest enclosing zone that has any, asked of the recursive resolver. */
    private static List<String> authoritativeServers(String name) {
        String candidate = name;
        while (candidate.contains(".")) {
            candidate = candidate.substring(candidate.indexOf('.') + 1);
            // A name with a CNAME is no zone apex, and an NS query for it follows the alias and
            // answers with the name servers of whatever the alias points at.
            if (!lookup(candidate, "CNAME", null).isEmpty()) {
                continue;
            }
            List<String> servers = lookup(candidate, "NS", null);
            if (!servers.isEmpty()) {
                return servers;
            }
        }
        return List.of();
    }

    private static List<String> lookup(String name, String type, String server) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "1500");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        if (server != null) {
            env.put("java.naming.provider.url", "dns://" + stripDot(server));
        }
        List<String> values = new ArrayList<>();
        try {
            InitialDirContext context = new InitialDirContext(env);
            try {
                Attribute attribute = context.getAttributes(name, new String[] {type}).get(type);
                if (attribute != null) {
                    NamingEnumeration<?> all = attribute.getAll();
                    while (all.hasMore()) {
                        values.add(stripDot(all.next().toString()));
                    }
                }
            } finally {
                context.close();
            }
        } catch (NamingException exception) {
            LOG.debugv("No {0} record for {1} at {2}: {3}", type, name,
                    server == null ? "the resolver" : server, exception.getMessage());
        }
        return values;
    }

    private static String stripDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
