package io.floci.aws.services.healthlake;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestHost;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes a data store's FHIR requests ({@code /datastore/<id>/r4/...}) to the proxy {@code /_floci/healthlake/<region>
 * /<id>/<path>}, the Region taken from the {@code healthlake.<region>.} host, or from the signature's scope.
 */
@Provider
@PreMatching
@Priority(5)
public class HealthLakeDataPlaneRoutingFilter implements ContainerRequestFilter {

    private static final Pattern PATH = Pattern.compile("/datastore/([0-9a-f]{32})/r4(/.*)?");
    private static final Pattern SCOPE = Pattern.compile("Credential=[^/]+/\\d{8}/([^/]+)/");

    /** Resolved when first needed: a request filter is built before the configuration is. */
    @Inject
    Instance<EmulatorConfig> config;

    /** The Region of a SigV4 signature's credential scope, or the default without one. */
    private String scopeRegion(String authorization) {
        Matcher scope = SCOPE.matcher(authorization == null ? "" : authorization);
        return scope.find() ? scope.group(1) : config.get().defaultRegion();
    }

    @Override
    public void filter(ContainerRequestContext context) {
        URI original = context.getUriInfo().getRequestUri();
        Matcher matcher = PATH.matcher(original.getRawPath() == null ? "" : original.getRawPath());
        if (!matcher.matches()) {
            return;
        }
        String host = RequestHost.of(context.getHeaderString("Host"), original);
        String region = null;
        String[] labels = host == null ? new String[0] : host.split("\\.");
        if (labels.length > 2 && "healthlake".equals(labels[0]) && AwsRegions.isRegionId(labels[1])) {
            region = labels[1];
        }
        if (region == null) {
            region = scopeRegion(context.getHeaderString("Authorization"));
        }
        String rest = matcher.group(2) == null || matcher.group(2).isEmpty() ? "/" : matcher.group(2);
        context.setRequestUri(UriBuilder.fromUri(original).host("localhost")
                .replacePath(HealthLakeDataPlaneController.BASE_PREFIX + region + "/" + matcher.group(1) + rest)
                .build());
    }
}
