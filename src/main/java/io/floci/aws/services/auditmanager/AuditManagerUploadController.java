package io.floci.aws.services.auditmanager;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The URLs {@code GetEvidenceFileUploadUrl} hands out: a PUT stores a manual evidence file, which
 * {@code BatchImportEvidenceToAssessmentControl} then imports by its {@code evidenceFileName}.
 */
@Path(AuditManagerUploadController.PATH)
public class AuditManagerUploadController {
    static final String PATH = "/_auditmanager-evidence";

    private final AuditManagerService service;

    @Inject
    public AuditManagerUploadController(AuditManagerService service) {
        this.service = service;
    }

    @PUT
    @Path("/{token}")
    @Consumes(MediaType.WILDCARD)
    public Response upload(@PathParam("token") String token, byte[] body) {
        return service.acceptUpload(token, body == null ? new byte[0] : body)
                ? Response.ok().build()
                : Response.status(404).entity("The upload URL is unknown.").type(MediaType.TEXT_PLAIN).build();
    }
}
