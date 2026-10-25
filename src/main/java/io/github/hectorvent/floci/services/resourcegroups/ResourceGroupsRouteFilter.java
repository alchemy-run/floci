package io.github.hectorvent.floci.services.resourcegroups;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

@Provider
@PreMatching
public class ResourceGroupsRouteFilter implements ContainerRequestFilter {
    static final String INTERNAL_PREFIX = "/_rg";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/groups", "/get-group", "/delete-group", "/group-resources", "/list-group-resources");
    private final SignedServiceRoute route;

    @Inject
    public ResourceGroupsRouteFilter(ResolvedServiceCatalog catalog) {
        route = new SignedServiceRoute(catalog, "resource-groups", INTERNAL_PREFIX, PUBLIC_PATHS::contains);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
