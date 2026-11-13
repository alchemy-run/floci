package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * What a SiteWise Monitor portal serves at its start URL: a page with the portal's projects, their dashboards and
 * the assets they share; and the portal logos the portals' image locations name.
 */
@Path(SiteWisePortalController.PATH)
public class SiteWisePortalController {

    static final String PATH = "/_floci/iotsitewise";

    private final SiteWiseStore store;
    private final SiteWiseMonitor monitor;

    @Inject
    public SiteWisePortalController(SiteWiseStore store, SiteWiseMonitor monitor) {
        this.store = store;
        this.monitor = monitor;
    }

    @GET
    @Path("/portals/{portalId}")
    public Response portal(@PathParam("portalId") String portalId) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(key -> key.endsWith("/" + SiteWiseStore.PORTAL + "/" + portalId))) {
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            try {
                Map<String, Object> page = RequestScopes.callAs(entry.accountId(), region,
                        () -> monitor.portalPage(entry.accountId(), region, portalId));
                return Response.ok(html(page)).type("text/html; charset=utf-8").build();
            } catch (AwsException e) {
                return Response.status(404).build();
            }
        }
        return Response.status(404).build();
    }

    @GET
    @Path("/images/{region}/{imageId}")
    public Response image(@PathParam("region") String region, @PathParam("imageId") String imageId) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(key -> key.equals(region + "/" + SiteWiseMonitor.IMAGE + "/" + imageId))) {
            byte[] data = monitor.image(entry.accountId(), region, imageId);
            if (data != null) {
                return Response.ok(data).type("image/png").build();
            }
        }
        return Response.status(404).build();
    }

    @SuppressWarnings("unchecked")
    private static String html(Map<String, Object> page) {
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>")
                .append(escape((String) page.get("name"))).append("</title></head><body>");
        if (!((String) page.get("logo")).isEmpty()) {
            out.append("<img alt=\"logo\" src=\"").append(escape((String) page.get("logo"))).append("\">");
        }
        out.append("<h1>").append(escape((String) page.get("name"))).append("</h1>");
        for (Map<String, Object> project : (List<Map<String, Object>>) page.get("projects")) {
            out.append("<section><h2>").append(escape((String) project.get("name"))).append("</h2><h3>Dashboards</h3><ul>");
            for (String dashboard : (List<String>) project.get("dashboards")) {
                out.append("<li>").append(escape(dashboard)).append("</li>");
            }
            out.append("</ul><h3>Assets</h3><ul>");
            for (String asset : (List<String>) project.get("assets")) {
                out.append("<li>").append(escape(asset)).append("</li>");
            }
            out.append("</ul></section>");
        }
        return out.append("</body></html>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
