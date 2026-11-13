package io.floci.aws.services.amplify;

import io.floci.aws.core.common.AwsPartition;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.RequestHost;
import io.floci.aws.core.common.RequestUriRewrite;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Routes requests addressed to an Amplify branch's host ({@code <branch>.<appId>.amplifyapp.com}, or
 * a verified custom domain) to {@link AmplifyServingController}, which serves the branch's deployed
 * files. Runs after the CloudFront filter (15) and before the S3 virtual-host filter.
 */
@Provider
@PreMatching
@Priority(16)
public class AmplifyHostingFilter implements ContainerRequestFilter {
    static final String SITE_PROPERTY = AmplifyHostingFilter.class.getName() + ".site";
    static final String PATH_PROPERTY = AmplifyHostingFilter.class.getName() + ".path";

    // Resolved per request: a @PreMatching filter is built into the native image's heap.
    private final Instance<AmplifyHosting> hosting;

    @Inject
    public AmplifyHostingFilter(Instance<AmplifyHosting> hosting) {
        this.hosting = hosting;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        URI uri = request.getUriInfo().getRequestUri();
        String path = uri.getRawPath();
        if (path != null && (path.startsWith("/_floci/") || path.startsWith("/_amplify/"))) {
            return;
        }
        String host = RequestHost.of(request);
        if (host == null || !mayBeSite(host)) {
            return;
        }
        AmplifyHosting.Site site = hosting.get().siteFor(host);
        if (site == null) {
            if (host.toLowerCase().replaceAll(":\\d+$", "").endsWith(".amplifyapp.com")) {
                // No branch of any app is served at this host, as Amplify answers for one it doesn't know.
                request.abortWith(Response.status(404).type("text/plain").entity("Not Found").build());
            }
            return;
        }
        request.setProperty(SITE_PROPERTY, site);
        request.setProperty(PATH_PROPERTY, path == null || path.isEmpty() ? "/" : path);
        StringBuilder rewritten = new StringBuilder();
        if (uri.getScheme() != null) {
            rewritten.append(uri.getScheme()).append("://").append(uri.getRawAuthority());
        }
        rewritten.append("/_amplify/site");
        if (uri.getRawQuery() != null) {
            rewritten.append('?').append(uri.getRawQuery());
        }
        RequestUriRewrite.apply(request, URI.create(rewritten.toString()));
    }

    /** Whether a host could be a branch's: an amplifyapp.com host, or a custom domain rather than an AWS or local one. */
    private static boolean mayBeSite(String host) {
        String name = host.toLowerCase().replaceAll(":\\d+$", "");
        if (name.endsWith(".amplifyapp.com")) {
            return true;
        }
        if (!name.contains(".") || name.endsWith(".floci.io") || name.endsWith(".localhost")
                || name.matches("[0-9.]+") || name.startsWith("[")) {
            return false;
        }
        for (AwsPartition partition : AwsPartitions.all()) {
            if (name.endsWith("." + partition.dnsSuffix()) || name.endsWith("." + partition.dualStackDnsSuffix())) {
                return false;
            }
        }
        return !name.endsWith(".cloudfront.net"); // partition-literal: CloudFront's own domain, in every partition
    }
}
