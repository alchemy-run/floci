package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The search page of a Kendra experience, at its {@code HOME} endpoint: a search box over the experience's index,
 * limited to its content sources, that queries the index as the experience's account.
 */
@Path(KendraExperienceController.PATH + "{region}/{indexId}/{id}")
public class KendraExperienceController {

    static final String PATH = "/_floci/kendra/experiences/";

    private static final String PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><title>%s</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:system-ui,sans-serif;max-width:48rem;margin:2rem auto;padding:0 1rem}
            input{width:100%%;padding:.6rem;font-size:1rem}li{margin:1rem 0}small{color:#666}</style></head>
            <body><h1>%s</h1><form id="f"><input id="q" placeholder="Search" autofocus></form><ul id="r"></ul>
            <script>
            const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",
              '"':"&quot;"})[c]);
            document.getElementById("f").onsubmit = async (e) => {
              e.preventDefault();
              const res = await fetch("query?q=" + encodeURIComponent(document.getElementById("q").value));
              const out = await res.json();
              document.getElementById("r").innerHTML = (out.ResultItems || []).map((i) =>
                "<li><b>" + esc(i.DocumentTitle && i.DocumentTitle.Text) + "</b> <small>" + esc(i.Type) +
                "</small><br>" + esc(i.DocumentExcerpt && i.DocumentExcerpt.Text) + "</li>").join("") ||
                "<li>" + esc(out.message || "No results") + "</li>";
            };
            </script></body></html>
            """;

    private final KendraStore store;
    private final KendraQueries queries;

    @Inject
    public KendraExperienceController(KendraStore store, KendraQueries queries) {
        this.store = store;
        this.queries = queries;
    }

    private Map.Entry<String, ObjectNode> find(String region, String indexId, String id) {
        String key = KendraStore.key(region, KendraStore.EXPERIENCE, indexId + "/" + id);
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(KendraStore.EXPERIENCE)) {
            if (entry.key().equals(key) && "ACTIVE".equals(entry.value().path("Status").asText())) {
                return Map.entry(entry.accountId(), entry.value());
            }
        }
        return null;
    }

    @GET
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    public Response page(@PathParam("region") String region, @PathParam("indexId") String indexId,
                         @PathParam("id") String id) {
        Map.Entry<String, ObjectNode> experience = find(region, indexId, id);
        if (experience == null) {
            return Response.status(404).entity("No active experience " + id + ".").build();
        }
        String name = KendraText.decode(experience.getValue().path("Name").asText()).replace("<", "&lt;");
        return Response.ok(PAGE.formatted(name, name), MediaType.TEXT_HTML).build();
    }

    @GET
    @Path("/query")
    public Response query(@PathParam("region") String region, @PathParam("indexId") String indexId,
                          @PathParam("id") String id, @QueryParam("q") String q) {
        Map.Entry<String, ObjectNode> experience = find(region, indexId, id);
        if (experience == null) {
            return Response.status(404).type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("message", "No active experience " + id + ".")).build();
        }
        ObjectNode request = store.mapper().createObjectNode().put("IndexId", indexId).put("QueryText", q);
        JsonNode sources = experience.getValue().path("Configuration").path("ContentSourceConfiguration");
        if (sources.path("DataSourceIds").size() > 0 && !sources.path("DirectPutContent").asBoolean(false)) {
            ArrayNode any = request.putObject("AttributeFilter").putArray("OrAllFilters");
            sources.get("DataSourceIds").forEach(sourceId -> any.addObject().putObject("EqualsTo")
                    .put("Key", "_data_source_id").putObject("Value").put("StringValue", sourceId.asText()));
        }
        try {
            ObjectNode out = RequestScopes.callAs(experience.getKey(), region, () -> queries.query(request, region));
            return Response.ok(out, MediaType.APPLICATION_JSON).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("message", e.getMessage())).build();
        }
    }
}
