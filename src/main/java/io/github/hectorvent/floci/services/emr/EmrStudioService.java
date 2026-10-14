package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Studio management metadata and prerequisite validation, not a notebook execution engine. */
@ApplicationScoped
public class EmrStudioService {

    private final StorageBackend<String, ObjectNode> studios;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Ec2Service ec2;
    private final IamService iam;
    private final IamPolicyEvaluator policies;
    private final S3Service s3;

    @Inject
    public EmrStudioService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper,
                            Ec2Service ec2, IamService iam, IamPolicyEvaluator policies, S3Service s3) {
        this.studios = storageFactory.create("emr", "emr-studios.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.ec2 = ec2;
        this.iam = iam;
        this.policies = policies;
        this.s3 = s3;
    }

    public synchronized ObjectNode create(JsonNode request, String region) {
        requireText(request, "Name");
        String authMode = requireText(request, "AuthMode");
        if (!List.of("IAM", "SSO").contains(authMode)) {
            throw invalid("AuthMode must be IAM or SSO.");
        }
        if ("SSO".equals(authMode)) {
            throw unsupported("SSO Studio authentication");
        }
        validateSupportedOptions(request);
        ObjectNode studio = mapper.createObjectNode();
        for (String field : List.of("Name", "Description", "AuthMode", "VpcId", "SubnetIds", "ServiceRole",
                "UserRole", "WorkspaceSecurityGroupId", "EngineSecurityGroupId", "DefaultS3Location",
                "TrustedIdentityPropagationEnabled")) {
            if (request.hasNonNull(field)) {
                studio.set(field, request.get(field).deepCopy());
            }
        }
        validateNetwork(studio, region);
        validateStorage(studio);
        if (studio.hasNonNull("UserRole")) {
            requireRole(requireText(studio, "UserRole"));
        }
        studio.set("Tags", tagArray(parseTags(request.path("Tags"))));
        String id = "es-" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
        studio.put("StudioId", id);
        studio.put("StudioArn", regionResolver.buildArn("elasticmapreduce", region, "studio/" + id));
        studio.put("Url", "https://" + id + ".emrstudio-prod." + region + ".amazonaws.com");
        studio.put("CreationTime", Instant.now().getEpochSecond());
        studios.put(key(region, id), studio);
        ObjectNode response = mapper.createObjectNode();
        for (String field : List.of("StudioId", "StudioArn", "Url")) {
            response.set(field, studio.get(field));
        }
        return response;
    }

    public ObjectNode describe(String id, String region) {
        return requireStudio(id, region).deepCopy();
    }

    public ObjectNode list(JsonNode request, String region) {
        JsonNode marker = request.path("Marker");
        if (!marker.isMissingNode() && !marker.isNull() && !marker.isTextual()) {
            throw invalid("Marker must be a string.");
        }
        List<ObjectNode> values = studios.scan(k -> k.startsWith(region + ":"));
        PaginatedResult<ObjectNode> page = Pagination.paginate(values, node -> node.path("StudioId").asText(),
                null, marker.asText(null), 50, "InvalidRequestException");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("Studios");
        for (ObjectNode studio : page.items()) {
            ObjectNode summary = summaries.addObject();
            for (String field : List.of("StudioId", "Name", "VpcId", "Description", "Url", "AuthMode", "CreationTime")) {
                if (studio.has(field)) {
                    summary.set(field, studio.get(field).deepCopy());
                }
            }
        }
        if (page.nextToken() != null) {
            response.put("Marker", page.nextToken());
        }
        return response;
    }

    public synchronized void update(JsonNode request, String region) {
        String id = requireText(request, "StudioId");
        ObjectNode studio = describe(id, region);
        validateSupportedOptions(request);
        for (String field : List.of("Name", "Description", "SubnetIds", "DefaultS3Location")) {
            if (request.has(field)) {
                studio.set(field, request.get(field).deepCopy());
            }
        }
        requireText(studio, "Name");
        if (request.has("SubnetIds")) {
            validateNetwork(studio, region);
        }
        if (request.has("DefaultS3Location")) {
            validateStorage(studio);
        }
        studios.put(key(region, id), studio);
    }

    public synchronized void delete(String id, String region) {
        requireStudio(id, region);
        studios.delete(key(region, id));
    }

    public synchronized void addTags(String id, JsonNode values, String region) {
        ObjectNode studio = describe(id, region);
        Map<String, String> tags = parseTags(studio.path("Tags"));
        tags.putAll(parseTags(values));
        studio.set("Tags", tagArray(tags));
        studios.put(key(region, id), studio);
    }

    public synchronized void removeTags(String id, List<String> keys, String region) {
        ObjectNode studio = describe(id, region);
        Map<String, String> tags = parseTags(studio.path("Tags"));
        keys.forEach(tags::remove);
        studio.set("Tags", tagArray(tags));
        studios.put(key(region, id), studio);
    }

    private ObjectNode requireStudio(String id, String region) {
        if (id == null || id.isBlank()) {
            throw invalid("StudioId is required.");
        }
        return studios.get(key(region, id)).orElseThrow(() -> invalid("Studio does not exist."));
    }

    private void validateNetwork(ObjectNode studio, String region) {
        String vpcId = requireText(studio, "VpcId");
        JsonNode subnetIds = studio.path("SubnetIds");
        if (!subnetIds.isArray() || subnetIds.isEmpty() || subnetIds.size() > 5) {
            throw invalid("SubnetIds must contain between 1 and 5 subnets.");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode subnetId : subnetIds) {
            if (!subnetId.isTextual() || subnetId.asText().isBlank()) {
                throw invalid("SubnetIds must contain subnet IDs.");
            }
            ids.add(subnetId.asText());
        }
        String workspaceId = requireText(studio, "WorkspaceSecurityGroupId");
        String engineId = requireText(studio, "EngineSecurityGroupId");
        try {
            ec2.describeVpcs(region, List.of(vpcId), Map.of());
            for (Subnet subnet : ec2.describeSubnets(region, ids, Map.of())) {
                if (!vpcId.equals(subnet.getVpcId())) {
                    throw invalid("All subnets must belong to the Studio VPC.");
                }
            }
            List<SecurityGroup> groups = ec2.describeSecurityGroups(region,
                    List.of(workspaceId, engineId), List.of(), Map.of());
            for (SecurityGroup group : groups) {
                if (!vpcId.equals(group.getVpcId())) {
                    throw invalid("Security groups must belong to the Studio VPC.");
                }
            }
            SecurityGroup engine = groups.stream().filter(group -> engineId.equals(group.getGroupId()))
                    .findFirst().orElseThrow(() -> invalid("Engine security group does not exist."));
            if (engine.getIpPermissions().stream().noneMatch(rule -> allowsWorkspace(rule, workspaceId))) {
                throw invalid("Engine security group must allow TCP port 18888 from the workspace security group.");
            }
        } catch (AwsException e) {
            throw invalid(e.getMessage());
        }
    }

    private static boolean allowsWorkspace(IpPermission rule, String workspaceId) {
        boolean protocol = "-1".equals(rule.getIpProtocol())
                || (("tcp".equals(rule.getIpProtocol()) || "6".equals(rule.getIpProtocol()))
                && rule.getFromPort() != null && rule.getToPort() != null
                && rule.getFromPort() <= 18888 && rule.getToPort() >= 18888);
        return protocol && rule.getUserIdGroupPairs().stream().anyMatch(pair -> workspaceId.equals(pair.getGroupId()));
    }

    private void validateStorage(ObjectNode studio) {
        IamRole role = requireRole(requireText(studio, "ServiceRole"));
        validateTrust(role);
        String location = requireText(studio, "DefaultS3Location");
        URI uri;
        try {
            uri = URI.create(location);
        } catch (IllegalArgumentException e) {
            throw invalid("DefaultS3Location must be an S3 URI.");
        }
        if (!"s3".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid("DefaultS3Location must be an S3 URI.");
        }
        String bucket = uri.getHost();
        S3Service.BucketPolicyInfo bucketInfo = s3.findBucketPolicyInfo(bucket)
                .orElseThrow(() -> invalid("The S3 bucket does not exist."));
        List<String> documents = new ArrayList<>(role.getInlinePolicies().values());
        role.getAttachedPolicyArns().forEach(arn -> documents.add(iam.getPolicy(arn).getDefaultDocument()));
        String boundary = role.getPermissionsBoundaryArn() == null ? null
                : iam.getPolicy(role.getPermissionsBoundaryArn()).getDefaultDocument();
        CallerContext caller = new CallerContext(documents, null, boundary, null, role.getArn());
        List<String> bucketPolicies = bucketInfo.policy() == null ? List.of() : List.of(bucketInfo.policy());
        String bucketArn = AwsArnUtils.Arn.of("s3", "", "", bucket).toString();
        String path = uri.getPath() == null ? "" : uri.getPath();
        String objectArn = bucketArn + (path.endsWith("/") ? path : path + "/") + "*";
        IamPolicyEvaluator.ResourceAccountRelationship relationship =
                regionResolver.getAccountId().equals(bucketInfo.ownerAccountId())
                        ? IamPolicyEvaluator.ResourceAccountRelationship.SAME_ACCOUNT
                        : IamPolicyEvaluator.ResourceAccountRelationship.CROSS_ACCOUNT;
        for (String action : List.of("s3:ListBucket", "s3:GetBucketLocation", "s3:GetEncryptionConfiguration",
                "s3:GetObject", "s3:PutObject", "s3:DeleteObject")) {
            String resource = action.endsWith("Object") ? objectArn : bucketArn;
            IamPolicyEvaluator.ResourcePolicyDecision bucketDecision = policies.evaluateResourcePolicy(
                    bucketPolicies, role.getArn(), action, resource, Map.of());
            if (policies.evaluateResolvedResourcePolicy(caller, bucketDecision, relationship,
                    action, resource, Map.of()) != IamPolicyEvaluator.Decision.ALLOW) {
                throw invalid("The service role does not have permission to access the 'S3 Location' " + location);
            }
        }
    }

    private IamRole requireRole(String arn) {
        String[] parts = arn.split(":", 6);
        if (parts.length != 6 || !"iam".equals(parts[2]) || !parts[3].isEmpty()
                || !regionResolver.getAccountId().equals(parts[4]) || !parts[5].startsWith("role/")) {
            throw invalid("Service role must be an IAM role in this account.");
        }
        IamRole role = iam.findRole(regionResolver.getAccountId(), arn.substring(arn.lastIndexOf('/') + 1))
                .filter(value -> arn.equals(value.getArn()))
                .orElseThrow(() -> invalid("Amazon EMR does not have permissions to assume role."));
        return role;
    }

    private void validateTrust(IamRole role) {
        try {
            JsonNode document = mapper.readTree(role.getAssumeRolePolicyDocument());
            if (document == null || !document.isObject()) {
                throw invalid("Amazon EMR does not have permissions to assume role.");
            }
            JsonNode statements = document.path("Statement");
            List<JsonNode> entries = new ArrayList<>();
            if (statements.isArray()) {
                statements.forEach(entries::add);
            } else if (statements.isObject()) {
                entries.add(statements);
            }
            for (JsonNode entry : entries) {
                if (entry.isObject() && !entry.has("Resource") && !entry.has("NotResource")) {
                    ((ObjectNode) entry).put("Resource", role.getArn());
                }
            }
            if (policies.evaluateResourcePolicy(List.of(document.toString()), "elasticmapreduce.amazonaws.com",
                    "sts:AssumeRole", role.getArn(), Map.of("aws:SourceAccount", List.of(regionResolver.getAccountId())))
                    != IamPolicyEvaluator.ResourcePolicyDecision.ALLOW) {
                throw invalid("Amazon EMR does not have permissions to assume role.");
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid("Amazon EMR does not have permissions to assume role.");
        }
    }

    private Map<String, String> parseTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node.isMissingNode()) {
            return tags;
        }
        if (!node.isArray()) {
            throw invalid("Tags must be an array.");
        }
        for (JsonNode tag : node) {
            String name = requireText(tag, "Key");
            JsonNode value = tag.path("Value");
            if (!value.isTextual()) {
                throw invalid("Tag Value must be a string.");
            }
            tags.put(name, value.asText());
        }
        return tags;
    }

    private ArrayNode tagArray(Map<String, String> tags) {
        ArrayNode result = mapper.createArrayNode();
        tags.forEach((name, value) -> result.addObject().put("Key", name).put("Value", value));
        return result;
    }

    private static String key(String region, String id) {
        return region + ":" + id;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid(field + " is required.");
        }
        return value.asText();
    }

    private static void validateSupportedOptions(JsonNode request) {
        for (String field : List.of("IdpAuthUrl", "IdpRelayStateParameterName", "IdcUserAssignment",
                "IdcInstanceArn", "EncryptionKeyArn")) {
            if (request.hasNonNull(field)) {
                throw unsupported("Studio " + field);
            }
        }
        JsonNode trusted = request.path("TrustedIdentityPropagationEnabled");
        if (!trusted.isMissingNode() && !trusted.isNull()) {
            if (!trusted.isBoolean()) {
                throw invalid("TrustedIdentityPropagationEnabled must be a boolean.");
            }
            if (trusted.booleanValue()) {
                throw unsupported("Studio trusted identity propagation");
            }
        }
    }

    private static AwsException unsupported(String feature) {
        return new AwsException("NotImplementedException", feature + " is not implemented by Floci.", 501);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
