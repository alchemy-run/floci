package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoFederatedIdentity;
import io.github.hectorvent.floci.services.cognitoidentity.model.CognitoIdentityProvider;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import io.github.hectorvent.floci.services.cognitoidentity.model.PrincipalTagAttributeMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon Cognito Identity pool management and local identity credential operations.
 */
@ApplicationScoped
public class CognitoIdentityJsonHandler {

    private final CognitoIdentityService cognitoIdentityService;
    private final ObjectMapper objectMapper;

    @Inject
    public CognitoIdentityJsonHandler(CognitoIdentityService cognitoIdentityService, ObjectMapper objectMapper) {
        this.cognitoIdentityService = cognitoIdentityService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateIdentityPool" -> Response.ok(
                    identityPoolNode(cognitoIdentityService.createIdentityPool(parsePool(request), region))).build();

            case "DescribeIdentityPool" -> Response.ok(identityPoolNode(
                    cognitoIdentityService.describeIdentityPool(text(request, "IdentityPoolId"), region))).build();

            case "UpdateIdentityPool" -> Response.ok(
                    identityPoolNode(cognitoIdentityService.updateIdentityPool(parsePool(request), region))).build();

            case "DeleteIdentityPool" -> {
                cognitoIdentityService.deleteIdentityPool(text(request, "IdentityPoolId"), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            case "ListIdentityPools" -> {
                CognitoIdentityService.Page page = cognitoIdentityService.listIdentityPools(
                        integer(request, "MaxResults"), text(request, "NextToken"), region);
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode identityPools = response.putArray("IdentityPools");
                for (IdentityPool pool : page.identityPools()) {
                    ObjectNode summary = identityPools.addObject();
                    summary.put("IdentityPoolId", pool.getIdentityPoolId());
                    summary.put("IdentityPoolName", pool.getIdentityPoolName());
                }
                if (page.nextToken() != null) {
                    response.put("NextToken", page.nextToken());
                }
                yield Response.ok(response).build();
            }

            case "SetIdentityPoolRoles" -> {
                cognitoIdentityService.setIdentityPoolRoles(text(request, "IdentityPoolId"),
                        roles(request.get("Roles")), request.get("RoleMappings"), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            case "GetIdentityPoolRoles" -> {
                IdentityPool pool = cognitoIdentityService.getIdentityPoolRoles(
                        text(request, "IdentityPoolId"), region);
                ObjectNode response = objectMapper.createObjectNode();
                response.put("IdentityPoolId", pool.getIdentityPoolId());
                response.set("Roles", objectMapper.valueToTree(pool.getRoles()));
                response.set("RoleMappings", pool.getRoleMappings() != null
                        ? pool.getRoleMappings()
                        : objectMapper.createObjectNode());
                yield Response.ok(response).build();
            }

            case "TagResource" -> {
                cognitoIdentityService.tagResource(text(request, "ResourceArn"),
                        stringMap(request.get("Tags")), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            case "UntagResource" -> {
                cognitoIdentityService.untagResource(text(request, "ResourceArn"),
                        stringList(request.get("TagKeys")), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            case "ListTagsForResource" -> {
                Map<String, String> tags = cognitoIdentityService.listTagsForResource(
                        text(request, "ResourceArn"), region);
                ObjectNode response = objectMapper.createObjectNode();
                response.set("Tags", objectMapper.valueToTree(tags));
                yield Response.ok(response).build();
            }

            case "SetPrincipalTagAttributeMap" -> {
                String identityPoolId = text(request, "IdentityPoolId");
                PrincipalTagAttributeMap attributeMap = cognitoIdentityService.setPrincipalTagAttributeMap(
                        identityPoolId, text(request, "IdentityProviderName"),
                        bool(request, "UseDefaults"), stringMap(request.get("PrincipalTags")), region);
                yield Response.ok(principalTagAttributeMapNode(identityPoolId, attributeMap)).build();
            }

            case "GetPrincipalTagAttributeMap" -> {
                String identityPoolId = text(request, "IdentityPoolId");
                PrincipalTagAttributeMap attributeMap = cognitoIdentityService.getPrincipalTagAttributeMap(
                        identityPoolId, text(request, "IdentityProviderName"), region);
                yield Response.ok(principalTagAttributeMapNode(identityPoolId, attributeMap)).build();
            }

            case "GetId" -> {
                CognitoFederatedIdentity identity = cognitoIdentityService.getId(
                        text(request, "IdentityPoolId"), stringMap(request.get("Logins")), region);
                yield Response.ok(objectMapper.createObjectNode()
                        .put("IdentityId", identity.getIdentityId())).build();
            }

            case "GetCredentialsForIdentity" -> Response.ok(objectMapper.valueToTree(
                    cognitoIdentityService.getCredentialsForIdentity(text(request, "IdentityId"),
                            stringMap(request.get("Logins")), region))).build();

            case "GetOpenIdToken" -> Response.ok(objectMapper.valueToTree(
                    cognitoIdentityService.getOpenIdToken(text(request, "IdentityId"), region))).build();

            case "DescribeIdentity" -> Response.ok(identityNode(
                    cognitoIdentityService.describeIdentity(text(request, "IdentityId"), region))).build();

            case "ListIdentities" -> {
                String poolId = text(request, "IdentityPoolId");
                ObjectNode response = objectMapper.createObjectNode().put("IdentityPoolId", poolId);
                ArrayNode identities = response.putArray("Identities");
                cognitoIdentityService.listIdentities(poolId, region)
                        .forEach(identity -> identities.add(identityNode(identity)));
                yield Response.ok(response).build();
            }

            case "DeleteIdentities" -> {
                ObjectNode response = objectMapper.createObjectNode();
                response.set("UnprocessedIdentityIds", objectMapper.valueToTree(
                        cognitoIdentityService.deleteIdentities(stringList(request.get("IdentityIdsToDelete")), region)));
                yield Response.ok(response).build();
            }

            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    private IdentityPool parsePool(JsonNode request) {
        IdentityPool pool = new IdentityPool();
        pool.setIdentityPoolId(text(request, "IdentityPoolId"));
        pool.setIdentityPoolName(text(request, "IdentityPoolName"));
        pool.setAllowUnauthenticatedIdentities(requiredBool(request, "AllowUnauthenticatedIdentities"));
        pool.setAllowClassicFlow(request.path("AllowClassicFlow").asBoolean(false));
        pool.setSupportedLoginProviders(stringMap(request.get("SupportedLoginProviders")));
        pool.setDeveloperProviderName(text(request, "DeveloperProviderName"));
        pool.setOpenIdConnectProviderArns(stringList(request.get("OpenIdConnectProviderARNs")));
        pool.setSamlProviderArns(stringList(request.get("SamlProviderARNs")));
        pool.setIdentityPoolTags(request.has("IdentityPoolTags")
                ? stringMap(request.get("IdentityPoolTags")) : null);

        List<CognitoIdentityProvider> providers = new ArrayList<>();
        JsonNode providersNode = request.get("CognitoIdentityProviders");
        if (providersNode != null && providersNode.isArray()) {
            for (JsonNode node : providersNode) {
                CognitoIdentityProvider provider = new CognitoIdentityProvider();
                provider.setProviderName(text(node, "ProviderName"));
                provider.setClientId(text(node, "ClientId"));
                provider.setServerSideTokenCheck(node.path("ServerSideTokenCheck").asBoolean(false));
                providers.add(provider);
            }
        }
        pool.setCognitoIdentityProviders(providers);
        return pool;
    }

    private ObjectNode identityPoolNode(IdentityPool pool) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityPoolId", pool.getIdentityPoolId());
        node.put("IdentityPoolName", pool.getIdentityPoolName());
        node.put("AllowUnauthenticatedIdentities", pool.isAllowUnauthenticatedIdentities());
        node.put("AllowClassicFlow", pool.isAllowClassicFlow());
        node.set("SupportedLoginProviders", objectMapper.valueToTree(pool.getSupportedLoginProviders()));
        if (pool.getDeveloperProviderName() != null) {
            node.put("DeveloperProviderName", pool.getDeveloperProviderName());
        }
        node.set("OpenIdConnectProviderARNs", objectMapper.valueToTree(pool.getOpenIdConnectProviderArns()));
        node.set("SamlProviderARNs", objectMapper.valueToTree(pool.getSamlProviderArns()));
        node.set("IdentityPoolTags", objectMapper.valueToTree(pool.getIdentityPoolTags()));

        ArrayNode providers = node.putArray("CognitoIdentityProviders");
        for (CognitoIdentityProvider provider : pool.getCognitoIdentityProviders()) {
            ObjectNode providerNode = providers.addObject();
            providerNode.put("ProviderName", provider.getProviderName());
            providerNode.put("ClientId", provider.getClientId());
            providerNode.put("ServerSideTokenCheck", provider.isServerSideTokenCheck());
        }
        return node;
    }

    private ObjectNode identityNode(CognitoFederatedIdentity identity) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityId", identity.getIdentityId());
        node.set("Logins", objectMapper.valueToTree(identity.getLogins()));
        node.put("CreationDate", identity.getCreationDate());
        node.put("LastModifiedDate", identity.getLastModifiedDate());
        return node;
    }

    private ObjectNode principalTagAttributeMapNode(String identityPoolId, PrincipalTagAttributeMap attributeMap) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityPoolId", identityPoolId);
        node.put("IdentityProviderName", attributeMap.getIdentityProviderName());
        node.put("UseDefaults", attributeMap.isUseDefaults());
        node.set("PrincipalTags", objectMapper.valueToTree(attributeMap.getPrincipalTags()));
        return node;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.asInt();
    }

    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new AwsException("SerializationException", field + " must be a boolean.", 400);
        }
        return value.asBoolean();
    }

    private boolean requiredBool(JsonNode node, String field) {
        Boolean value = bool(node, field);
        if (value == null) {
            throw new AwsException("ValidationException", "1 validation error detected: Value null at '"
                    + Character.toLowerCase(field.charAt(0)) + field.substring(1)
                    + "' failed to satisfy constraint: Member must not be null", 400);
        }
        return value;
    }

    private Map<String, String> roles(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new AwsException("SerializationException", "Roles must be an object.", 400);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (!value.isTextual() && !value.isNull()) {
                throw new AwsException("SerializationException", "Role ARN must be a string.", 400);
            }
            values.put(entry.getKey(), value.isNull() ? null : value.textValue());
        }
        return values;
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        }
        return values;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        }
        return values;
    }
}
