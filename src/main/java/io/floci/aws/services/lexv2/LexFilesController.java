package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Base64;
import java.util.Optional;

/**
 * The presigned URLs Lex V2 hands out: downloads of exports, test execution artifacts, discrepancy reports and
 * recommendation results, and the upload URL {@code CreateUploadUrl} gives for an import's archive.
 */
@Path("/_floci/lexv2/files")
public class LexFilesController {

    private final LexStore store;

    @Inject
    public LexFilesController(LexStore store) {
        this.store = store;
    }

    @GET
    @Path("/{token}")
    public Response download(@PathParam("token") String token) {
        Optional<AccountAwareStorageBackend.AccountEntry<ObjectNode>> entry = store.file(token);
        if (entry.isEmpty() || !entry.get().value().hasNonNull("content")) {
            return Response.status(403).type(MediaType.TEXT_PLAIN).entity("The URL has expired or doesn't exist.")
                    .build();
        }
        ObjectNode file = entry.get().value();
        return Response.ok(Base64.getDecoder().decode(file.get("content").asText()),
                        file.path("contentType").asText(MediaType.APPLICATION_OCTET_STREAM))
                .header("Content-Disposition", "attachment; filename=\"" + file.path("name").asText("file") + "\"")
                .build();
    }

    @PUT
    @Path("/{token}")
    @Consumes(MediaType.WILDCARD)
    public Response upload(@PathParam("token") String token, byte[] body) {
        Optional<AccountAwareStorageBackend.AccountEntry<ObjectNode>> entry = store.file(token);
        if (entry.isEmpty() || !entry.get().value().path("upload").asBoolean(false)) {
            return Response.status(403).type(MediaType.TEXT_PLAIN).entity("The upload URL has expired or doesn't "
                    + "exist.").build();
        }
        ObjectNode file = entry.get().value();
        file.put("content", Base64.getEncoder().encodeToString(body == null ? new byte[0] : body));
        file.put("contentType", "application/zip");
        store.putFileForAccount(entry.get().accountId(), token, file);
        return Response.ok().build();
    }
}
