package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudfront.model.KeyValueStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CloudFront KeyValueStore <em>data plane</em> ({@code cloudfront-keyvaluestore},
 * restJson1). Real AWS serves it from
 * {@code {accountId}.cloudfront-kvs.global.api.aws}; with an endpoint override
 * the SDK's endpoint rules produce {@code {accountId}.{host}} against Floci,
 * so the embedded DNS wildcard delivers it here. Paths address stores by ARN:
 *
 * <pre>
 * GET    /key-value-stores/{kvsArn}            DescribeKeyValueStore
 * GET    /key-value-stores/{kvsArn}/keys       ListKeys
 * POST   /key-value-stores/{kvsArn}/keys       UpdateKeys (bulk puts+deletes)
 * GET    /key-value-stores/{kvsArn}/keys/{key} GetKey
 * PUT    /key-value-stores/{kvsArn}/keys/{key} PutKey
 * DELETE /key-value-stores/{kvsArn}/keys/{key} DeleteKey
 * </pre>
 *
 * The ARN path label is percent-encoded by SDKs but may arrive raw from
 * hand-written clients, so the templates use a greedy {@code .+} regex —
 * JAX-RS literal-count ordering keeps the {@code /keys} routes more specific.
 */
@Path("/key-value-stores")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class CloudFrontKvsDataPlaneController {

    private final CloudFrontService service;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudFrontKvsDataPlaneController(CloudFrontService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/{kvsArn: .+}/keys/{key: [^/]+}")
    public Response getKey(@PathParam("kvsArn") String kvsArn, @PathParam("key") String key) {
        String value = service.kvsGetKey(kvsArn, key);
        Map<String, String> keys = service.kvsKeys(kvsArn);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Key", key);
        node.put("Value", value);
        node.put("ItemCount", keys.size());
        node.put("TotalSizeInBytes", service.kvsTotalSizeInBytes(kvsArn));
        return Response.ok(node.toString()).build();
    }

    @PUT
    @Path("/{kvsArn: .+}/keys/{key: [^/]+}")
    public Response putKey(@PathParam("kvsArn") String kvsArn, @PathParam("key") String key,
                           @HeaderParam("If-Match") String ifMatch, String body) {
        String value = readValueField(body);
        KeyValueStore store = service.kvsPutKey(kvsArn, key, value, ifMatch);
        return countsResponse(kvsArn, store);
    }

    @DELETE
    @Path("/{kvsArn: .+}/keys/{key: [^/]+}")
    public Response deleteKey(@PathParam("kvsArn") String kvsArn, @PathParam("key") String key,
                              @HeaderParam("If-Match") String ifMatch) {
        KeyValueStore store = service.kvsDeleteKey(kvsArn, key, ifMatch);
        return countsResponse(kvsArn, store);
    }

    @GET
    @Path("/{kvsArn: .+}/keys")
    public Response listKeys(@PathParam("kvsArn") String kvsArn,
                             @QueryParam("NextToken") String nextToken,
                             @QueryParam("MaxResults") Integer maxResults) {
        // TreeMap gives a stable key order so NextToken (the last returned
        // key) is a valid resume point.
        Map<String, String> keys = new TreeMap<>(service.kvsKeys(kvsArn));
        List<Map.Entry<String, String>> entries = new ArrayList<>(keys.entrySet());
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getKey().compareTo(nextToken) > 0) {
                    break;
                }
                start = i + 1;
            }
        }
        int limit = maxResults != null && maxResults > 0 ? maxResults : entries.size();
        int end = Math.min(entries.size(), start + limit);

        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode items = node.putArray("Items");
        for (int i = start; i < end; i++) {
            ObjectNode item = items.addObject();
            item.put("Key", entries.get(i).getKey());
            item.put("Value", entries.get(i).getValue());
        }
        if (end < entries.size()) {
            node.put("NextToken", entries.get(end - 1).getKey());
        }
        return Response.ok(node.toString()).build();
    }

    @POST
    @Path("/{kvsArn: .+}/keys")
    public Response updateKeys(@PathParam("kvsArn") String kvsArn,
                               @HeaderParam("If-Match") String ifMatch, String body) {
        Map<String, String> puts = new LinkedHashMap<>();
        List<String> deletes = new ArrayList<>();
        JsonNode request = parseBody(body);
        JsonNode putsNode = request.path("Puts");
        if (putsNode.isArray()) {
            for (JsonNode put : putsNode) {
                puts.put(put.path("Key").asText(), put.path("Value").asText());
            }
        }
        JsonNode deletesNode = request.path("Deletes");
        if (deletesNode.isArray()) {
            for (JsonNode delete : deletesNode) {
                deletes.add(delete.path("Key").asText());
            }
        }
        KeyValueStore store = service.kvsUpdateKeys(kvsArn, ifMatch, puts, deletes);
        return countsResponse(kvsArn, store);
    }

    @GET
    @Path("/{kvsArn: .+}")
    public Response describe(@PathParam("kvsArn") String kvsArn) {
        KeyValueStore store = service.describeKeyValueStoreByArn(kvsArn);
        Map<String, String> keys = service.kvsKeys(kvsArn);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ItemCount", keys.size());
        node.put("TotalSizeInBytes", service.kvsTotalSizeInBytes(kvsArn));
        node.put("KvsARN", store.getArn());
        Instant created = store.getCreatedTime() != null ? store.getCreatedTime() : store.getLastModifiedTime();
        node.put("Created", created.getEpochSecond());
        if (store.getLastModifiedTime() != null) {
            node.put("LastModified", store.getLastModifiedTime().getEpochSecond());
        }
        node.put("Status", store.getStatus());
        return Response.ok(node.toString()).header("ETag", store.getEtag()).build();
    }

    private Response countsResponse(String kvsArn, KeyValueStore store) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ItemCount", service.kvsKeys(kvsArn).size());
        node.put("TotalSizeInBytes", service.kvsTotalSizeInBytes(kvsArn));
        return Response.ok(node.toString()).header("ETag", store.getEtag()).build();
    }

    private String readValueField(String body) {
        JsonNode request = parseBody(body);
        JsonNode value = request.path("Value");
        if (value.isMissingNode() || value.isNull()) {
            throw new AwsException("ValidationException", "Value is required.", 400);
        }
        return value.asText();
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }
}
