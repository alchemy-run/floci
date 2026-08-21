package io.github.hectorvent.floci.services.cloudfront.edge;

import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports where each emulated distribution's edge listens, so a client that
 * created a distribution through the CloudFront API can open it in a browser.
 *
 * <p>{@code {id}.cloudfront.net} is the distribution's domain name in the API
 * — faithful to AWS, and useless as an address on a developer's machine. The
 * port assigned by {@link CloudFrontEdgePorts} is the usable one, and it is
 * assigned by the emulator (not the client), so it has to be reported back.
 *
 * <p>Lives outside {@link CloudFrontEdgeRoutingFilter#EDGE_PREFIX} so it can
 * never be mistaken for a distribution id by the edge's catch-all route.
 */
@Path("/_floci/cloudfront-edge")
@ApplicationScoped
public class CloudFrontEdgeInfoController {

    @Inject
    CloudFrontService service;

    @Inject
    CloudFrontEdgePorts edgePorts;

    /**
     * Give back their ports to distributions that outlived the process (a
     * persistent storage backend restores them, the in-memory Vert.x servers do
     * not come back on their own).
     */
    void onStart(@Observes StartupEvent event) {
        if (!edgePorts.enabled()) {
            return;
        }
        for (Distribution distribution : service.listDistributions(null, Integer.MAX_VALUE)) {
            edgePorts.bind(distribution.getId());
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        edgePorts.assignments().forEach((id, port) -> items.add(describe(id, port)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Enabled", edgePorts.enabled());
        body.put("Distributions", items);
        return body;
    }

    @GET
    @Path("/{distributionId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> get(@PathParam("distributionId") String distributionId) {
        // Bind on read as well as on create: a distribution restored from disk,
        // or one created while the range was full, gets a port as soon as one is
        // asked for.
        Integer port = edgePorts.portOf(distributionId);
        if (port == null) {
            service.getDistribution(distributionId);
            port = edgePorts.bind(distributionId);
        }
        if (port == null) {
            throw new NotFoundException("No edge port is assigned to " + distributionId);
        }
        return describe(distributionId, port);
    }

    private Map<String, Object> describe(String distributionId, int port) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("DistributionId", distributionId);
        out.put("Port", port);
        out.put("Url", edgePorts.urlOf(distributionId));
        return out;
    }
}
