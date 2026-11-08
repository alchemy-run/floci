package io.floci.aws.services.acm;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Looks a name's CNAME up in public DNS, the way ACM finds a validation record kept outside
 * Route 53 (a Cloudflare zone, say). A lookup that fails or times out answers with no targets,
 * so an offline emulator keeps the certificate pending rather than failing the request.
 */
@ApplicationScoped
public class PublicDns {

    private static final Logger LOG = Logger.getLogger(PublicDns.class);

    /** The CNAME targets of {@code name}, without the trailing dot; empty when it has none. */
    public List<String> cnameTargets(String name) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "1500");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        List<String> targets = new ArrayList<>();
        try {
            InitialDirContext context = new InitialDirContext(env);
            try {
                Attributes attributes = context.getAttributes(name, new String[] {"CNAME"});
                Attribute cname = attributes.get("CNAME");
                if (cname != null) {
                    NamingEnumeration<?> values = cname.getAll();
                    while (values.hasMore()) {
                        targets.add(values.next().toString());
                    }
                }
            } finally {
                context.close();
            }
        } catch (NamingException exception) {
            LOG.debugv("No public CNAME for {0}: {1}", name, exception.getMessage());
        }
        return targets;
    }
}
