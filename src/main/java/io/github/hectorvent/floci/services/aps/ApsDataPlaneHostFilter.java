package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reserves the workspace authority before S3's virtual-host routing without rewriting signed requests. */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 200)
public class ApsDataPlaneHostFilter implements ContainerRequestFilter {

    private static final Pattern HOST = Pattern.compile(
            "^(?:aps-workspaces-)?(ws-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.localhost\\.floci\\.io$");

    @Override
    public void filter(ContainerRequestContext request) {
        URI uri = request.getUriInfo().getRequestUri();
        String authority = request.getHeaderString("Host");
        String host = authority == null ? uri.getHost() : URI.create("http://" + authority).getHost();
        if (host == null) {
            return;
        }
        Matcher match = HOST.matcher(host.toLowerCase(Locale.ROOT));
        if (!match.matches()) {
            return;
        }
        if (!SigV4CredentialScope.serviceName(request.getHeaderString("Authorization"))
                .filter("aps"::equals).isPresent()) {
            throw new AwsException("AccessDeniedException", "AMP requires SigV4 signing for aps", 403);
        }
        if (!uri.getPath().startsWith("/workspaces/" + match.group(1) + "/api/v1/")) {
            throw new AwsException("ResourceNotFoundException", "The workspace endpoint does not match the path", 404);
        }
    }
}
