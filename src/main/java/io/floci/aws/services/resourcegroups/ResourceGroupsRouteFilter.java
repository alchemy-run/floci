package io.floci.aws.services.resourcegroups;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/** Resource Groups' paths are also S3 path-style bucket routes, so they are routed by signing service. */
@Provider
@PreMatching
public class ResourceGroupsRouteFilter implements ContainerRequestFilter {
    static final String INTERNAL_PREFIX = "/_rg";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/groups", "/get-group", "/update-group", "/delete-group", "/groups-list", "/get-group-query",
            "/update-group-query", "/get-group-configuration", "/put-group-configuration", "/group-resources",
            "/ungroup-resources", "/list-group-resources", "/get-account-settings", "/update-account-settings",
            "/list-grouping-statuses", "/start-tag-sync-task", "/get-tag-sync-task", "/cancel-tag-sync-task",
            "/list-tag-sync-tasks");
    private final SignedServiceRoute route;

    @Inject
    public ResourceGroupsRouteFilter(ResolvedServiceCatalog catalog) {
        route = new SignedServiceRoute(catalog, "resource-groups", INTERNAL_PREFIX,
                path -> PUBLIC_PATHS.contains(path) || SignedServiceRoute.under("/resources").test(path));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
