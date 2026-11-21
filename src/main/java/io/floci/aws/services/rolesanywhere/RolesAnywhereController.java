package io.floci.aws.services.rolesanywhere;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import io.floci.aws.services.qbusiness.QBusinessController;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The IAM Roles Anywhere REST API, routed here by {@link RolesAnywhereRouteFilter}, with {@code CreateSession}
 * ({@code POST /sessions}), which no SDK model describes, answered by {@link RolesAnywhereSessions}.
 */
@Path(RolesAnywhereRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class RolesAnywhereController {

    private static final RequestConstraintCheck SHAPES = QBusinessController.shapes("aws/rolesanywhere-request-shapes.json",
            "IAM Roles Anywhere request shapes");

    private final RolesAnywhereService service;
    private final RolesAnywhereSessions sessions;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public RolesAnywhereController(RolesAnywhereService service, RolesAnywhereSessions sessions,
                                   RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.sessions = sessions;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "rolesanywhere");
    }

    @GET
    @Path("{path: .*}")
    public Response get(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("GET", headers, uri, body);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("POST", headers, uri, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PATCH", headers, uri, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PUT", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(RolesAnywhereRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        if ("POST".equals(method) && "/sessions".equals(path)) {
            return Response.status(201).entity(sessions.createSession(region, headers, uri, body))
                    .type(MediaType.APPLICATION_JSON).build();
        }
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body, (operation, input) -> {
            SHAPES.check(operation, input);
            return service.handle(operation, input, region);
        });
    }
}
