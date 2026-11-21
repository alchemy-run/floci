package io.floci.aws.services.ecrpublic;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/** Serves the logos public repositories' catalog data links to, as the ECR Public Gallery serves them. */
@Path(EcrPublicLogoController.PATH)
public class EcrPublicLogoController {

    static final String PATH = "/_ecr-public/logos";

    private final EcrPublicService service;

    @Inject
    public EcrPublicLogoController(EcrPublicService service) {
        this.service = service;
    }

    @GET
    @Path("{account}/{repository: .+}")
    public Response logo(@PathParam("account") String account, @PathParam("repository") String repository,
                         @QueryParam("type") String type) {
        return service.logo(account, repository)
                .map(bytes -> Response.ok(bytes, "jpeg".equals(type) ? "image/jpeg" : "image/png").build())
                .orElseGet(() -> Response.status(404).build());
    }
}
