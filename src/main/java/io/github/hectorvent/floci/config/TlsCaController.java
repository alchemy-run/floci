package io.github.hectorvent.floci.config;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Exposes Floci's self-signed CA certificate so external clients (a local
 * workerd, a host test process, CI tooling) can trust Floci's HTTPS endpoints
 * — e.g. via {@code NODE_EXTRA_CA_CERTS} — without reaching into the
 * container's filesystem.
 *
 * <p>{@code GET /_floci/tls/ca} → the PEM certificate; 404 when TLS is off or
 * a user-provided (non-generated) certificate has no readable path.
 */
@Path("/_floci/tls")
public class TlsCaController {

    private final EmulatorConfig config;

    @Inject
    public TlsCaController(EmulatorConfig config) {
        this.config = config;
    }

    @GET
    @Path("/ca")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getCaCertificate() {
        if (!config.tls().enabled()) {
            return notAvailable("TLS is not enabled");
        }
        java.nio.file.Path certPath = config.tls().certPath()
                .filter(s -> !s.isBlank())
                .map(java.nio.file.Path::of)
                .orElseGet(() -> java.nio.file.Path.of(
                        config.storage().persistentPath(), "tls", "floci-selfsigned.crt"));
        if (!Files.isReadable(certPath)) {
            return notAvailable("CA certificate not readable at " + certPath);
        }
        try {
            return Response.ok(Files.readString(certPath))
                    .header("Content-Disposition", "inline; filename=\"floci-ca.pem\"")
                    .build();
        } catch (IOException e) {
            return notAvailable("Failed to read CA certificate: " + e.getMessage());
        }
    }

    private static Response notAvailable(String reason) {
        return Response.status(404)
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"message\":\"" + reason.replace("\"", "'") + "\"}")
                .build();
    }
}
