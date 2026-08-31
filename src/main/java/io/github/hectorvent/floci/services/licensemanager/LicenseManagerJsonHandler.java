package io.github.hectorvent.floci.services.licensemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.licensemanager.model.LicenseConfiguration;
import io.github.hectorvent.floci.services.licensemanager.model.LicenseConsumption;
import io.github.hectorvent.floci.services.licensemanager.model.SellerGrant;
import io.github.hectorvent.floci.services.licensemanager.model.SellerLicense;
import io.github.hectorvent.floci.services.licensemanager.model.SellerToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for AWS License Manager. Dispatched from
 * {@code AwsJson11Controller} under the {@code AWSLicenseManager.} target prefix.
 */
@ApplicationScoped
public class LicenseManagerJsonHandler {

    private final LicenseManagerService service;
    private final ObjectMapper objectMapper;

    @Inject
    public LicenseManagerJsonHandler(LicenseManagerService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateLicenseConfiguration" -> create(body, region);
                case "GetLicenseConfiguration" -> get(body);
                case "ListLicenseConfigurations" -> list(body, region);
                case "UpdateLicenseConfiguration" -> update(body);
                case "DeleteLicenseConfiguration" -> delete(body);
                case "TagResource" -> tagResource(body);
                case "UntagResource" -> untagResource(body);
                case "ListTagsForResource" -> listTags(body);
                case "ListAssociationsForLicenseConfiguration" -> emptyList("LicenseConfigurationAssociations");
                case "ListUsageForLicenseConfiguration" -> emptyList("LicenseConfigurationUsageList");
                case "ListFailuresForLicenseConfigurationOperations" -> emptyList("LicenseOperationFailureList");
                case "ListResourceInventory" -> emptyList("ResourceInventoryList");
                case "ListLicenseSpecificationsForResource" -> emptyList("LicenseSpecifications");
                case "UpdateLicenseSpecificationsForResource" -> ok();
                case "GetServiceSettings" -> getServiceSettings();
                case "CreateLicense" -> createLicense(body, region);
                case "CreateLicenseVersion" -> createLicenseVersion(body);
                case "GetLicense" -> getLicense(body);
                case "GetLicenseUsage" -> getLicenseUsage(body);
                case "ListLicenses", "ListReceivedLicenses", "ListLicenseVersions" -> listLicenses(body);
                case "DeleteLicense" -> deleteLicense(body);
                case "CreateToken" -> createToken(body);
                case "ListTokens" -> listTokens(body);
                case "GetAccessToken" -> getAccessToken(body);
                case "DeleteToken" -> deleteToken(body);
                case "CheckoutLicense" -> checkoutLicense(body);
                case "CheckoutBorrowLicense" -> checkoutBorrowLicense(body);
                case "ExtendLicenseConsumption" -> extendConsumption(body);
                case "CheckInLicense" -> checkInLicense(body);
                case "CreateGrant" -> createGrant(body);
                case "CreateGrantVersion" -> createGrantVersion(body);
                case "GetGrant" -> getGrant(body);
                case "DeleteGrant" -> deleteGrant(body);
                case "AcceptGrant" -> acceptGrant(body);
                case "RejectGrant" -> rejectGrant(body);
                case "ListReceivedGrants", "ListDistributedGrants" -> listGrants(body);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                        "AWSLicenseManager." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response create(JsonNode request, String region) {
        LicenseConfiguration created = service.create(
                region,
                textOrNull(request, "Name"),
                textOrNull(request, "Description"),
                textOrNull(request, "LicenseCountingType"),
                longOrNull(request, "LicenseCount"),
                boolOrNull(request, "LicenseCountHardLimit"),
                boolOrNull(request, "DisassociateWhenNotFound"),
                stringList(request.path("LicenseRules")),
                readTagMap(request.path("Tags")),
                longOrNull(request, "LicenseExpiry"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("LicenseConfigurationArn", created.getLicenseConfigurationArn());
        return Response.ok(response).build();
    }

    private Response get(JsonNode request) {
        LicenseConfiguration config = service.get(textOrNull(request, "LicenseConfigurationArn"));
        return Response.ok(toDetail(config, true)).build();
    }

    private Response list(JsonNode request, String region) {
        List<String> arns = stringList(request.path("LicenseConfigurationArns"));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        String nextToken = textOrNull(request, "NextToken");
        List<LicenseConfiguration> configs = service.list(region, arns, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("LicenseConfigurations");
        for (LicenseConfiguration config : configs) {
            list.add(toDetail(config, false));
        }
        String token = service.nextToken(region, arns, nextToken, maxResults);
        if (token != null) {
            response.put("NextToken", token);
        }
        return Response.ok(response).build();
    }

    private Response update(JsonNode request) {
        service.update(
                textOrNull(request, "LicenseConfigurationArn"),
                textOrNull(request, "Name"),
                textOrNull(request, "Description"),
                longOrNull(request, "LicenseCount"),
                boolOrNull(request, "LicenseCountHardLimit"),
                boolOrNull(request, "DisassociateWhenNotFound"),
                request.has("LicenseRules") ? stringList(request.path("LicenseRules")) : null,
                textOrNull(request, "LicenseConfigurationStatus"),
                longOrNull(request, "LicenseExpiry"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response delete(JsonNode request) {
        service.delete(textOrNull(request, "LicenseConfigurationArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response tagResource(JsonNode request) {
        service.tagResource(textOrNull(request, "ResourceArn"), readTagMap(request.path("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode request) {
        service.untagResource(textOrNull(request, "ResourceArn"), stringList(request.path("TagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listTags(JsonNode request) {
        Map<String, String> tags = service.listTags(textOrNull(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsArray(tags));
        return Response.ok(response).build();
    }

    private Response createLicense(JsonNode request, String region) {
        SellerLicense license = service.createLicense(region, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("LicenseArn", license.getLicenseArn());
        response.put("Status", license.getStatus());
        response.put("Version", license.getVersion());
        return Response.ok(response).build();
    }

    private Response createLicenseVersion(JsonNode request) {
        SellerLicense license = service.createLicenseVersion(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("LicenseArn", license.getLicenseArn());
        response.put("Status", license.getStatus());
        response.put("Version", license.getVersion());
        return Response.ok(response).build();
    }

    private Response getLicense(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("License", licenseNode(service.getLicense(textOrNull(request, "LicenseArn"))));
        return Response.ok(response).build();
    }

    private Response getLicenseUsage(JsonNode request) {
        SellerLicense license = service.getLicense(textOrNull(request, "LicenseArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode usages = response.putObject("LicenseUsage").putArray("EntitlementUsages");
        for (Map<String, Object> entitlement : license.getEntitlements()) {
            ObjectNode usage = usages.addObject();
            if (entitlement.get("Name") != null) {
                usage.put("Name", String.valueOf(entitlement.get("Name")));
            }
            usage.put("ConsumedValue", "0");
            if (entitlement.get("MaxCount") != null) {
                usage.put("MaxCount", String.valueOf(entitlement.get("MaxCount")));
            }
            if (entitlement.get("Unit") != null) {
                usage.put("Unit", String.valueOf(entitlement.get("Unit")));
            }
        }
        return Response.ok(response).build();
    }

    private Response listLicenses(JsonNode request) {
        List<String> arns = stringList(request.path("LicenseArns"));
        if (arns.isEmpty() && request.hasNonNull("LicenseArn")) {
            arns = List.of(request.get("LicenseArn").asText());
        }
        List<SellerLicense> found = service.listLicenses(arns);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Licenses");
        for (SellerLicense license : found) {
            list.add(licenseNode(license));
        }
        return Response.ok(response).build();
    }

    private Response deleteLicense(JsonNode request) {
        Map<String, String> deleted = service.deleteLicense(
                textOrNull(request, "LicenseArn"), textOrNull(request, "SourceVersion"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Status", deleted.get("Status"));
        response.put("DeletionDate", deleted.get("DeletionDate"));
        return Response.ok(response).build();
    }

    private Response createToken(JsonNode request) {
        SellerToken token = service.createToken(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TokenId", token.getTokenId());
        response.put("TokenType", token.getTokenType());
        response.put("Token", token.getToken());
        return Response.ok(response).build();
    }

    private Response listTokens(JsonNode request) {
        List<SellerToken> found = service.listTokens(stringList(request.path("TokenIds")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Tokens");
        for (SellerToken token : found) {
            ObjectNode node = list.addObject();
            node.put("TokenId", token.getTokenId());
            node.put("TokenType", token.getTokenType());
            node.put("LicenseArn", token.getLicenseArn());
            if (token.getExpirationTime() != null) {
                node.put("ExpirationTime", token.getExpirationTime());
            }
            node.put("Status", token.getStatus());
            if (!token.getTokenProperties().isEmpty()) {
                ArrayNode props = node.putArray("TokenProperties");
                token.getTokenProperties().forEach(props::add);
            }
            if (!token.getRoleArns().isEmpty()) {
                ArrayNode roles = node.putArray("RoleArns");
                token.getRoleArns().forEach(roles::add);
            }
        }
        return Response.ok(response).build();
    }

    private Response getAccessToken(JsonNode request) {
        String access = service.getAccessToken(textOrNull(request, "Token"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccessToken", access);
        return Response.ok(response).build();
    }

    private Response deleteToken(JsonNode request) {
        service.deleteToken(textOrNull(request, "TokenId"));
        return ok();
    }

    private Response checkoutLicense(JsonNode request) {
        return checkoutResponse(service.checkoutLicense(request));
    }

    private Response checkoutBorrowLicense(JsonNode request) {
        return checkoutResponse(service.checkoutBorrowLicense(request));
    }

    private Response extendConsumption(JsonNode request) {
        LicenseConsumption consumption = service.extendConsumption(
                textOrNull(request, "LicenseConsumptionToken"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("LicenseConsumptionToken", consumption.getLicenseConsumptionToken());
        response.put("Expiration", consumption.getExpiration());
        return Response.ok(response).build();
    }

    private Response checkInLicense(JsonNode request) {
        service.checkInLicense(textOrNull(request, "LicenseConsumptionToken"));
        return ok();
    }

    private Response createGrant(JsonNode request) {
        return grantIdentity(service.createGrant(request));
    }

    private Response createGrantVersion(JsonNode request) {
        return grantIdentity(service.createGrantVersion(request));
    }

    private Response getGrant(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Grant", grantNode(service.getGrant(textOrNull(request, "GrantArn"))));
        return Response.ok(response).build();
    }

    private Response deleteGrant(JsonNode request) {
        return grantIdentity(service.deleteGrant(
                textOrNull(request, "GrantArn"), textOrNull(request, "Version")));
    }

    private Response acceptGrant(JsonNode request) {
        return grantIdentity(service.acceptGrant(textOrNull(request, "GrantArn")));
    }

    private Response rejectGrant(JsonNode request) {
        return grantIdentity(service.rejectGrant(textOrNull(request, "GrantArn")));
    }

    private Response listGrants(JsonNode request) {
        List<SellerGrant> found = service.listGrants(stringList(request.path("GrantArns")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Grants");
        for (SellerGrant grant : found) {
            list.add(grantNode(grant));
        }
        return Response.ok(response).build();
    }

    private Response checkoutResponse(LicenseConsumption consumption) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("CheckoutType", consumption.getCheckoutType());
        response.put("LicenseConsumptionToken", consumption.getLicenseConsumptionToken());
        response.put("IssuedAt", consumption.getIssuedAt());
        response.put("Expiration", consumption.getExpiration());
        response.put("LicenseArn", consumption.getLicenseArn());
        if (consumption.getNodeId() != null) {
            response.put("NodeId", consumption.getNodeId());
        }
        response.set("EntitlementsAllowed", objectMapper.valueToTree(consumption.getEntitlements()));
        return Response.ok(response).build();
    }

    private Response grantIdentity(SellerGrant grant) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GrantArn", grant.getGrantArn());
        response.put("Status", grant.getGrantStatus());
        response.put("Version", grant.getVersion());
        return Response.ok(response).build();
    }

    private ObjectNode licenseNode(SellerLicense license) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("LicenseArn", license.getLicenseArn());
        node.put("LicenseName", license.getLicenseName());
        node.put("ProductName", license.getProductName());
        node.put("ProductSKU", license.getProductSku());
        node.put("HomeRegion", license.getHomeRegion());
        node.put("Status", license.getStatus());
        node.put("Version", license.getVersion());
        if (license.getBeneficiary() != null) {
            node.put("Beneficiary", license.getBeneficiary());
        }
        if (license.getCreateTime() != null) {
            node.put("CreateTime", license.getCreateTime());
        }
        ObjectNode issuer = node.putObject("Issuer");
        if (license.getIssuerName() != null) {
            issuer.put("Name", license.getIssuerName());
        }
        if (license.getIssuerSignKey() != null) {
            issuer.put("SignKey", license.getIssuerSignKey());
        }
        if (license.getKeyFingerprint() != null) {
            issuer.put("KeyFingerprint", license.getKeyFingerprint());
        }
        ObjectNode validity = node.putObject("Validity");
        if (license.getValidityBegin() != null) {
            validity.put("Begin", license.getValidityBegin());
        }
        if (license.getValidityEnd() != null) {
            validity.put("End", license.getValidityEnd());
        }
        node.set("Entitlements", objectMapper.valueToTree(license.getEntitlements()));
        node.set("ConsumptionConfiguration", objectMapper.valueToTree(license.getConsumptionConfiguration()));
        if (!license.getLicenseMetadata().isEmpty()) {
            node.set("LicenseMetadata", objectMapper.valueToTree(license.getLicenseMetadata()));
        }
        return node;
    }

    private ObjectNode grantNode(SellerGrant grant) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("GrantArn", grant.getGrantArn());
        node.put("GrantName", grant.getGrantName());
        node.put("ParentArn", grant.getParentArn());
        node.put("LicenseArn", grant.getLicenseArn());
        node.put("GranteePrincipalArn", grant.getGranteePrincipalArn());
        node.put("HomeRegion", grant.getHomeRegion());
        node.put("GrantStatus", grant.getGrantStatus());
        node.put("Version", grant.getVersion());
        if (grant.getStatusReason() != null) {
            node.put("StatusReason", grant.getStatusReason());
        }
        ArrayNode ops = node.putArray("GrantedOperations");
        grant.getGrantedOperations().forEach(ops::add);
        return node;
    }

    private Response ok() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response emptyList(String field) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(field);
        return Response.ok(response).build();
    }

    private Response getServiceSettings() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EnableCrossAccountsDiscovery", false);
        return Response.ok(response).build();
    }

    private ObjectNode toDetail(LicenseConfiguration config, boolean includeTags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (config.getLicenseConfigurationId() != null) {
            node.put("LicenseConfigurationId", config.getLicenseConfigurationId());
        }
        if (config.getLicenseConfigurationArn() != null) {
            node.put("LicenseConfigurationArn", config.getLicenseConfigurationArn());
        }
        if (config.getName() != null) {
            node.put("Name", config.getName());
        }
        if (config.getDescription() != null) {
            node.put("Description", config.getDescription());
        }
        if (config.getLicenseCountingType() != null) {
            node.put("LicenseCountingType", config.getLicenseCountingType());
        }
        if (!config.getLicenseRules().isEmpty()) {
            ArrayNode rules = node.putArray("LicenseRules");
            for (String rule : config.getLicenseRules()) {
                rules.add(rule);
            }
        }
        if (config.getLicenseCount() != null) {
            node.put("LicenseCount", config.getLicenseCount());
        }
        node.put("LicenseCountHardLimit", config.isLicenseCountHardLimit());
        node.put("DisassociateWhenNotFound", config.isDisassociateWhenNotFound());
        node.put("ConsumedLicenses", config.getConsumedLicenses());
        if (config.getStatus() != null) {
            node.put("Status", config.getStatus());
        }
        if (config.getOwnerAccountId() != null) {
            node.put("OwnerAccountId", config.getOwnerAccountId());
        }
        if (config.getLicenseExpiry() != null) {
            node.put("LicenseExpiry", config.getLicenseExpiry());
        }
        if (includeTags) {
            node.set("Tags", tagsArray(config.getTags()));
        }
        return node;
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        if (tags == null) {
            return array;
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue() != null ? entry.getValue() : "");
            array.add(tag);
        }
        return array;
    }

    private static Map<String, String> readTagMap(JsonNode tagList) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagList == null || !tagList.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagList) {
            String key = textOrNull(tag, "Key");
            if (key != null) {
                String value = textOrNull(tag, "Value");
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Long longOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asLong();
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asBoolean();
    }
}
