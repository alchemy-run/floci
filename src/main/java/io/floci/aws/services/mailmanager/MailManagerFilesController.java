package io.floci.aws.services.mailmanager;

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
 * The presigned URLs Mail Manager hands out: archived messages' download links, and the URL an address list import
 * job's CSV or JSON file is uploaded to.
 */
@Path("/_floci/mailmanager/files")
public class MailManagerFilesController {

    private final MailManagerStore store;

    @Inject
    public MailManagerFilesController(MailManagerStore store) {
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
                file.path("contentType").asText(MediaType.APPLICATION_OCTET_STREAM)).build();
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
        store.putForAccount(entry.get().accountId(), "files", MailManagerStore.FILE, token, file);
        return Response.ok().build();
    }
}
