package io.floci.aws.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * What Q Business's signed links open: presigned document content, Q Apps exports and file uploads, and the web
 * experience, a chat page that talks to the application through {@link QBusinessChat}.
 */
@Path("/_qbusiness")
@Consumes(MediaType.WILDCARD)
public class QBusinessLinksController {

    private final QBusinessStore store;
    private final QBusinessChat chat;
    private final QAppsService qapps;
    private final ObjectMapper mapper;

    @Inject
    public QBusinessLinksController(QBusinessStore store, QBusinessChat chat,
                                    QAppsService qapps, ObjectMapper mapper) {
        this.store = store;
        this.chat = chat;
        this.qapps = qapps;
        this.mapper = mapper;
    }

    private static Response forbidden() {
        return Response.status(403).type(MediaType.TEXT_PLAIN).entity("The link is invalid or has expired.").build();
    }

    @GET
    @Path("content/{token}")
    public Response content(@PathParam("token") String token) {
        String[] parts = QBusinessContentLinks.open(token);
        if (parts == null) {
            return forbidden();
        }
        return switch (parts[0]) {
            case "document" -> RequestScopes.callAs(parts[1], parts[2], () -> {
                ObjectNode document = store.get(parts[2], QBusinessStore.DOCUMENT, parts[3] + "/" + parts[4] + "/"
                        + parts[5]).orElse(null);
                if (document == null) {
                    return Response.status(404).build();
                }
                ObjectNode meta = QBusinessStore.meta(document);
                byte[] raw = Base64.getDecoder().decode(meta.path("raw").asText());
                if ("EXTRACTED".equals(parts[6])) {
                    return Response.ok(QBusinessSearch.text(raw, meta.path("contentType").asText()), "text/plain")
                            .build();
                }
                return Response.ok(raw, QBusinessService.mime(meta.path("contentType").asText())).build();
            });
            case "export" -> RequestScopes.callAs(parts[1], parts[2], () -> {
                ObjectNode export = store.get(parts[2], "qapp-export", parts[3] + "/" + parts[4]).orElse(null);
                if (export == null) {
                    return Response.status(404).build();
                }
                return Response.ok(Base64.getDecoder().decode(QBusinessStore.meta(export).path("data").asText()),
                        "text/csv").build();
            });
            default -> forbidden();
        };
    }

    @PUT
    @Path("content/{token}")
    public Response uploadPut(@PathParam("token") String token, byte[] body) {
        return upload(token, body);
    }

    @POST
    @Path("content/{token}")
    public Response uploadPost(@PathParam("token") String token, byte[] body) {
        return upload(token, body);
    }

    private Response upload(String token, byte[] body) {
        String[] parts = QBusinessContentLinks.open(token);
        if (parts == null || !"upload".equals(parts[0])) {
            return forbidden();
        }
        byte[] data = body == null ? new byte[0] : body;
        if (!QAppsService.sha256(data).equals(parts[9])) {
            return Response.status(400).type(MediaType.TEXT_PLAIN).entity("The file doesn't match "
                    + "fileContentsSha256.").build();
        }
        try {
            String fileId = RequestScopes.callAs(parts[1], parts[2], () -> qapps.storeFile(parts[2], parts[3],
                    parts[4], parts[5], parts[6], parts[7], parts[8].isEmpty() ? null : parts[8], data));
            return Response.ok(mapper.createObjectNode().put("fileId", fileId).toString(), MediaType.APPLICATION_JSON)
                    .build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).type(MediaType.TEXT_PLAIN).entity(e.getMessage()).build();
        }
    }

    // ================================================================ web experience

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @GET
    @Path("web/{region}/{id}/")
    public Response page(@PathParam("region") String region, @PathParam("id") String id,
                         @QueryParam("token") String token) {
        String[] parts = token == null ? null : QBusinessContentLinks.open(token);
        if (parts == null || !"session".equals(parts[0]) || !parts[4].equals(id)) {
            return Response.status(401).type(MediaType.TEXT_HTML).entity("<!doctype html><title>Amazon Q Business"
                    + "</title><p>Sign in through the application's identity provider, or open an anonymous URL from "
                    + "CreateAnonymousWebExperienceUrl.</p>").build();
        }
        ObjectNode web = RequestScopes.callAs(parts[1], region, () -> store.get(region, QBusinessStore.WEB, parts[3]
                + "/" + id).orElse(null));
        if (web == null) {
            return Response.status(404).build();
        }
        String title = escape(web.path("title").asText("Amazon Q Business"));
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width\"><title>" + title + "</title><style>body{font:16px system-ui;"
                + "max-width:720px;margin:2rem auto;padding:0 1rem}#log p{white-space:pre-wrap}.you{color:#555}"
                + "form{display:flex;gap:.5rem}input{flex:1;padding:.5rem}</style></head><body><h1>" + title
                + "</h1><p>" + escape(web.path("subtitle").asText("")) + "</p><p>"
                + escape(web.path("welcomeMessage").asText("")) + "</p><div id=\"log\"></div>"
                + "<form id=\"f\"><input id=\"q\" autocomplete=\"off\" placeholder=\"Ask a question\"><button>Send"
                + "</button></form><script>let c=null,p=null;const t=" + mapper.valueToTree(token) + ";"
                + "f.onsubmit=async e=>{e.preventDefault();const m=q.value;q.value='';log.insertAdjacentHTML("
                + "'beforeend','<p class=you></p>');log.lastChild.textContent=m;const r=await fetch('chat',{method:"
                + "'POST',headers:{'content-type':'application/json'},body:JSON.stringify({token:t,message:m,"
                + "conversationId:c,parentMessageId:p})});const j=await r.json();c=j.conversationId;p=j."
                + "systemMessageId;log.insertAdjacentHTML('beforeend','<p></p>');log.lastChild.textContent=j."
                + "systemMessage||j.message};</script></body></html>";
        return Response.ok(html, MediaType.TEXT_HTML).build();
    }

    @POST
    @Path("web/{region}/{id}/chat")
    public Response webChat(@PathParam("region") String region, @PathParam("id") String id, byte[] body) {
        JsonNode in;
        try {
            in = mapper.readTree(body == null ? new byte[0] : body);
        } catch (IOException e) {
            return Response.status(400).build();
        }
        String[] parts = QBusinessContentLinks.open(in.path("token").asText(""));
        if (parts == null || !"session".equals(parts[0]) || !parts[4].equals(id)) {
            return forbidden();
        }
        ObjectNode request = mapper.createObjectNode().put("applicationId", parts[3])
                .put("userMessage", in.path("message").asText());
        if (in.hasNonNull("conversationId")) {
            request.put("conversationId", in.get("conversationId").asText())
                    .put("parentMessageId", in.path("parentMessageId").asText());
        }
        try {
            ObjectNode result = RequestScopes.callAs(parts[1], region, () -> chat.chatSync(request, region));
            return Response.ok(result.toString(), MediaType.APPLICATION_JSON).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON).entity(mapper.createObjectNode()
                    .put("message", e.getMessage()).toString().getBytes(StandardCharsets.UTF_8)).build();
        }
    }
}
