package io.floci.aws.services.amplify;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Amplify Hosting's data plane: the pre-signed upload URLs of manual deployments, access log
 * links, and the branch sites {@link AmplifyHostingFilter} routes here.
 */
@Path("/_amplify")
public class AmplifyServingController {
    private final AmplifyHosting hosting;

    @Inject
    public AmplifyServingController(AmplifyHosting hosting) {
        this.hosting = hosting;
    }

    @PUT
    @Path("/uploads/{token}")
    @Consumes(MediaType.WILDCARD)
    public Response uploadZip(@PathParam("token") String token, byte[] body) {
        hosting.upload(token, null, body == null ? new byte[0] : body);
        return Response.ok().build();
    }

    @PUT
    @Path("/uploads/{token}/{path: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response uploadFile(@PathParam("token") String token, @PathParam("path") String path, byte[] body) {
        hosting.upload(token, path, body == null ? new byte[0] : body);
        return Response.ok().build();
    }

    @GET
    @Path("/logs/{token}")
    public Response accessLog(@PathParam("token") String token) {
        return Response.ok(hosting.accessLog(token), "text/csv").build();
    }

    @GET
    @Path("/site")
    public Response get(@Context ContainerRequestContext request, @Context UriInfo uri, @Context HttpHeaders headers) {
        return serve("GET", request, uri, headers, true);
    }

    @HEAD
    @Path("/site")
    public Response head(@Context ContainerRequestContext request, @Context UriInfo uri, @Context HttpHeaders headers) {
        return serve("HEAD", request, uri, headers, false);
    }

    private Response serve(String method, ContainerRequestContext request, UriInfo uri, HttpHeaders headers,
                           boolean body) {
        AmplifyHosting.Site site = (AmplifyHosting.Site) request.getProperty(AmplifyHostingFilter.SITE_PROPERTY);
        String path = (String) request.getProperty(AmplifyHostingFilter.PATH_PROPERTY);
        if (site == null) {
            return Response.status(404).build();
        }
        AmplifyHosting.Served served = hosting.serve(site, method, headers.getHeaderString("Host"), path,
                uri.getRequestUri().getRawQuery(), headers.getHeaderString("Authorization"),
                headers.getHeaderString("X-Forwarded-For"), headers.getHeaderString("Referer"),
                headers.getHeaderString("User-Agent"));
        Response.ResponseBuilder response = Response.status(served.status()).type(served.contentType());
        served.headers().forEach(response::header);
        if (body) {
            response.entity(served.body());
        }
        return response.build();
    }
}
