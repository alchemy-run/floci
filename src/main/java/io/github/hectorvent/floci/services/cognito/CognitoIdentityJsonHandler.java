package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognito.model.CognitoIdentityPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CognitoIdentityJsonHandler {

    private final CognitoIdentityService service;
    private final ObjectMapper objectMapper;

    @Inject
    public CognitoIdentityJsonHandler(CognitoIdentityService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateIdentityPool" -> handleCreateIdentityPool(request, region);
            case "DescribeIdentityPool" -> handleDescribeIdentityPool(request);
            case "UpdateIdentityPool" -> handleUpdateIdentityPool(request);
            case "DeleteIdentityPool" -> handleDeleteIdentityPool(request);
            case "ListIdentityPools" -> handleListIdentityPools(request);
            case "SetIdentityPoolRoles" -> handleSetIdentityPoolRoles(request);
            case "GetIdentityPoolRoles" -> handleGetIdentityPoolRoles(request);
            case "GetId" -> handleGetId(request);
            case "GetCredentialsForIdentity" -> handleGetCredentialsForIdentity(request);
            case "GetOpenIdToken" -> handleGetOpenIdToken(request);
            case "DescribeIdentity" -> handleDescribeIdentity(request);
            case "ListIdentities" -> handleListIdentities(request);
            case "DeleteIdentities" -> handleDeleteIdentities(request);
            case "ListTagsForResource" -> handleListTagsForResource(request);
            case "TagResource" -> handleTagResource(request);
            case "UntagResource" -> handleUntagResource(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateIdentityPool(JsonNode request, String region) {
        CognitoIdentityPool pool = service.createIdentityPool(region, asMap(request));
        return Response.ok(poolToNode(pool)).build();
    }

    private Response handleDescribeIdentityPool(JsonNode request) {
        return Response.ok(poolToNode(service.describeIdentityPool(request.path("IdentityPoolId").asText()))).build();
    }

    private Response handleUpdateIdentityPool(JsonNode request) {
        return Response.ok(poolToNode(service.updateIdentityPool(asMap(request)))).build();
    }

    private Response handleDeleteIdentityPool(JsonNode request) {
        service.deleteIdentityPool(request.path("IdentityPoolId").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListIdentityPools(JsonNode request) {
        int maxResults = request.path("MaxResults").asInt(60);
        if (maxResults <= 0) {
            maxResults = 60;
        }
        int offset = 0;
        String nextToken = request.path("NextToken").asText(null);
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException ignored) {
                offset = 0;
            }
        }
        List<CognitoIdentityPool> all = service.listIdentityPools();
        int to = Math.min(all.size(), offset + maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("IdentityPools");
        for (int i = offset; i < to; i++) {
            CognitoIdentityPool pool = all.get(i);
            ObjectNode item = items.addObject();
            item.put("IdentityPoolId", pool.getIdentityPoolId());
            item.put("IdentityPoolName", pool.getIdentityPoolName());
        }
        if (to < all.size()) {
            response.put("NextToken", String.valueOf(to));
        }
        return Response.ok(response).build();
    }

    private Response handleSetIdentityPoolRoles(JsonNode request) {
        service.setIdentityPoolRoles(
                request.path("IdentityPoolId").asText(),
                readStringMap(request.path("Roles")),
                request.path("RoleMappings").isObject()
                        ? objectMapper.convertValue(request.path("RoleMappings"),
                                new TypeReference<Map<String, Object>>() {})
                        : Map.of()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetIdentityPoolRoles(JsonNode request) {
        CognitoIdentityPool pool = service.getIdentityPoolRoles(request.path("IdentityPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("IdentityPoolId", pool.getIdentityPoolId());
        ObjectNode roles = response.putObject("Roles");
        pool.getRoles().forEach(roles::put);
        if (!pool.getRoleMappings().isEmpty()) {
            response.set("RoleMappings", objectMapper.valueToTree(pool.getRoleMappings()));
        }
        return Response.ok(response).build();
    }

    private Response handleGetId(JsonNode request) {
        CognitoFederatedIdentity identity = service.getId(
                request.path("IdentityPoolId").asText(),
                readStringMap(request.path("Logins"))
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.put("IdentityId", identity.getIdentityId());
        return Response.ok(response).build();
    }

    private Response handleGetCredentialsForIdentity(JsonNode request) {
        return Response.ok(objectMapper.valueToTree(service.getCredentialsForIdentity(
                request.path("IdentityId").asText(),
                readStringMap(request.path("Logins"))
        ))).build();
    }

    private Response handleGetOpenIdToken(JsonNode request) {
        return Response.ok(objectMapper.valueToTree(service.getOpenIdToken(
                request.path("IdentityId").asText()
        ))).build();
    }

    private Response handleDescribeIdentity(JsonNode request) {
        return Response.ok(identityToNode(service.describeIdentity(request.path("IdentityId").asText()))).build();
    }

    private Response handleListIdentities(JsonNode request) {
        List<CognitoFederatedIdentity> identities = service.listIdentities(request.path("IdentityPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Identities");
        identities.forEach(identity -> items.add(identityToNode(identity)));
        return Response.ok(response).build();
    }

    private Response handleDeleteIdentities(JsonNode request) {
        List<String> ids = new ArrayList<>();
        request.path("IdentityIdsToDelete").forEach(n -> ids.add(n.asText()));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode unprocessed = response.putArray("UnprocessedIdentityIds");
        for (Map<String, String> item : service.deleteIdentities(ids)) {
            ObjectNode node = unprocessed.addObject();
            item.forEach(node::put);
        }
        return Response.ok(response).build();
    }

    private Response handleListTagsForResource(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", objectMapper.valueToTree(service.listTagsForResource(request.path("ResourceArn").asText())));
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request) {
        service.tagResource(request.path("ResourceArn").asText(), readStringMap(request.path("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        List<String> keys = new ArrayList<>();
        request.path("TagKeys").forEach(n -> keys.add(n.asText()));
        service.untagResource(request.path("ResourceArn").asText(), keys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode poolToNode(CognitoIdentityPool pool) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityPoolId", pool.getIdentityPoolId());
        node.put("IdentityPoolName", pool.getIdentityPoolName());
        node.put("AllowUnauthenticatedIdentities", pool.isAllowUnauthenticatedIdentities());
        if (pool.getAllowClassicFlow() != null) {
            node.put("AllowClassicFlow", pool.getAllowClassicFlow());
        }
        if (!pool.getSupportedLoginProviders().isEmpty()) {
            node.set("SupportedLoginProviders", objectMapper.valueToTree(pool.getSupportedLoginProviders()));
        }
        if (pool.getDeveloperProviderName() != null) {
            node.put("DeveloperProviderName", pool.getDeveloperProviderName());
        }
        if (!pool.getOpenIdConnectProviderARNs().isEmpty()) {
            node.set("OpenIdConnectProviderARNs", objectMapper.valueToTree(pool.getOpenIdConnectProviderARNs()));
        }
        if (!pool.getCognitoIdentityProviders().isEmpty()) {
            node.set("CognitoIdentityProviders", objectMapper.valueToTree(pool.getCognitoIdentityProviders()));
        }
        if (!pool.getSamlProviderARNs().isEmpty()) {
            node.set("SamlProviderARNs", objectMapper.valueToTree(pool.getSamlProviderARNs()));
        }
        node.set("IdentityPoolTags", objectMapper.valueToTree(pool.getIdentityPoolTags()));
        return node;
    }

    private ObjectNode identityToNode(CognitoFederatedIdentity identity) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityId", identity.getIdentityId());
        ArrayNode logins = node.putArray("Logins");
        identity.getLogins().forEach(logins::add);
        node.put("CreationDate", identity.getCreationDate());
        node.put("LastModifiedDate", identity.getLastModifiedDate());
        return node;
    }

    private Map<String, Object> asMap(JsonNode request) {
        if (request == null || request.isMissingNode() || request.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(request, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        return out;
    }
}
