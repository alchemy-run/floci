package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CloudControlService {

    static final String TYPE_SSM_PARAMETER = "AWS::SSM::Parameter";
    private static final Set<String> KNOWN_TYPES = Set.of(
            "AWS::S3::Bucket",
            "AWS::EC2::VPC",
            "AWS::EC2::Subnet",
            "AWS::EC2::SecurityGroup",
            "AWS::IAM::Role",
            "AWS::IAM::User",
            TYPE_SSM_PARAMETER
    );

    private final S3Service s3Service;
    private final Ec2Service ec2Service;
    private final IamService iamService;
    private final SsmService ssmService;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ProgressEvent> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientTokenRecord> clientTokens = new ConcurrentHashMap<>();

    @Inject
    public CloudControlService(S3Service s3Service, Ec2Service ec2Service,
                               IamService iamService, SsmService ssmService, ObjectMapper mapper) {
        this.s3Service = s3Service;
        this.ec2Service = ec2Service;
        this.iamService = iamService;
        this.ssmService = ssmService;
        this.mapper = mapper;
    }

    public List<ResourceDescription> listResources(String region, String typeName) {
        List<ResourceDescription> resources = switch (typeName) {
            case "AWS::S3::Bucket" -> s3Buckets();
            case "AWS::EC2::VPC" -> vpcs(region);
            case "AWS::EC2::Subnet" -> subnets(region);
            case "AWS::EC2::SecurityGroup" -> securityGroups(region);
            case "AWS::IAM::Role" -> roles();
            case "AWS::IAM::User" -> users();
            case TYPE_SSM_PARAMETER -> ssmParameters(region);
            default -> List.of();
        };
        List<ResourceDescription> sorted = new ArrayList<>(resources);
        sorted.sort(Comparator.comparing(ResourceDescription::identifier, Comparator.nullsLast(String::compareTo)));
        return sorted;
    }

    public ResourceDescription getResource(String region, String typeName, String identifier) {
        requireTypeName(typeName);
        if (identifier == null || identifier.isBlank()) {
            throw invalid("Identifier is required.");
        }
        if (TYPE_SSM_PARAMETER.equals(typeName)) {
            return getSsmParameter(region, identifier);
        }
        if (!KNOWN_TYPES.contains(typeName)) {
            throw typeNotFound(typeName);
        }
        return listResources(region, typeName).stream()
                .filter(resource -> identifier.equals(resource.identifier()))
                .findFirst()
                .orElseThrow(() -> notFound(typeName, identifier));
    }

    public ProgressEvent createResource(String region, String typeName, JsonNode desiredState, String clientToken) {
        requireTypeName(typeName);
        if (desiredState == null || desiredState.isNull() || !desiredState.isObject()) {
            throw invalid("DesiredState must be a JSON object.");
        }
        String fingerprint = "CREATE|" + typeName + "|" + desiredState;
        ProgressEvent replayed = replayClientToken(clientToken, fingerprint);
        if (replayed != null) {
            return replayed;
        }
        if (!KNOWN_TYPES.contains(typeName)) {
            throw typeNotFound(typeName);
        }
        if (!TYPE_SSM_PARAMETER.equals(typeName)) {
            throw unsupported("CREATE", typeName);
        }
        ResourceDescription created = createSsmParameter(region, typeName, desiredState);
        return store("CREATE", typeName, created, clientToken, fingerprint);
    }

    public ProgressEvent updateResource(String region, String typeName, String identifier, JsonNode patch,
                                        String clientToken) {
        requireTypeName(typeName);
        if (identifier == null || identifier.isBlank()) {
            throw invalid("Identifier is required.");
        }
        String fingerprint = "UPDATE|" + typeName + "|" + identifier + "|" + patch;
        ProgressEvent replayed = replayClientToken(clientToken, fingerprint);
        if (replayed != null) {
            return replayed;
        }
        if (!KNOWN_TYPES.contains(typeName)) {
            throw typeNotFound(typeName);
        }
        if (!TYPE_SSM_PARAMETER.equals(typeName)) {
            throw unsupported("UPDATE", typeName);
        }
        ResourceDescription updated = updateSsmParameter(region, typeName, identifier, patch);
        return store("UPDATE", typeName, updated, clientToken, fingerprint);
    }

    public ProgressEvent deleteResource(String region, String typeName, String identifier, String clientToken) {
        requireTypeName(typeName);
        if (identifier == null || identifier.isBlank()) {
            throw invalid("Identifier is required.");
        }
        String fingerprint = "DELETE|" + typeName + "|" + identifier;
        ProgressEvent replayed = replayClientToken(clientToken, fingerprint);
        if (replayed != null) {
            return replayed;
        }
        if (!KNOWN_TYPES.contains(typeName)) {
            throw typeNotFound(typeName);
        }
        if (!TYPE_SSM_PARAMETER.equals(typeName)) {
            throw unsupported("DELETE", typeName);
        }
        ResourceDescription existing = getSsmParameter(region, identifier);
        try {
            ssmService.deleteParameter(identifier, region);
        } catch (AwsException e) {
            if ("ParameterNotFound".equals(e.getErrorCode())) {
                throw notFound(typeName, identifier);
            }
            throw e;
        }
        return store("DELETE", typeName, existing, clientToken, fingerprint);
    }

    public ProgressEvent getResourceRequestStatus(String requestToken) {
        if (requestToken == null || requestToken.isBlank()) {
            throw invalid("RequestToken is required.");
        }
        ProgressEvent event = requests.get(requestToken);
        if (event == null) {
            throw new AwsException("RequestTokenNotFoundException",
                    "Request token " + requestToken + " was not found.", 404);
        }
        return event;
    }

    public List<ProgressEvent> listResourceRequests(List<String> operations, List<String> statuses) {
        List<ProgressEvent> events = new ArrayList<>();
        for (ProgressEvent event : requests.values()) {
            if (operations != null && !operations.isEmpty() && !operations.contains(event.operation())) {
                continue;
            }
            if (statuses != null && !statuses.isEmpty() && !statuses.contains(event.operationStatus())) {
                continue;
            }
            events.add(event);
        }
        return events;
    }

    public ProgressEvent cancelResourceRequest(String requestToken) {
        ProgressEvent event = getResourceRequestStatus(requestToken);
        if (!"PENDING".equals(event.operationStatus()) && !"IN_PROGRESS".equals(event.operationStatus())) {
            throw new AwsException("ConcurrentModificationException",
                    "Cannot cancel a request that is not PENDING or IN_PROGRESS.", 500);
        }
        ProgressEvent cancelled = new ProgressEvent(
                event.typeName(), event.identifier(), event.requestToken(), event.operation(),
                "CANCEL_COMPLETE", epochSeconds(), event.resourceModel(), null, null);
        requests.put(requestToken, cancelled);
        return cancelled;
    }

    private ResourceDescription createSsmParameter(String region, String typeName, JsonNode desired) {
        String name = textOrNull(desired, "Name");
        if (name == null || name.isBlank()) {
            name = "/floci/" + UUID.randomUUID();
        }
        try {
            putSsmParameter(region, name, desired, false);
        } catch (AwsException e) {
            if ("ParameterAlreadyExists".equals(e.getErrorCode())) {
                throw new AwsException("AlreadyExistsException",
                        "Resource of type '" + typeName + "' with identifier '" + name + "' already exists.",
                        400);
            }
            throw e;
        }
        return getSsmParameter(region, name);
    }

    private ResourceDescription updateSsmParameter(String region, String typeName, String identifier, JsonNode patch) {
        ResourceDescription current = getSsmParameter(region, identifier);
        JsonNode observed;
        try {
            observed = mapper.readTree(current.properties());
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure", "Failed to parse resource properties.", 500);
        }
        JsonNode patched = CloudControlJsonPatch.apply(observed, patch);
        String name = textOrNull(patched, "Name");
        if (name != null && !name.equals(identifier)) {
            throw new AwsException("NotUpdatableException",
                    "Property Name is create-only and cannot be updated.", 400);
        }
        putSsmParameter(region, identifier, patched, true);
        return getSsmParameter(region, identifier);
    }

    private void putSsmParameter(String region, String name, JsonNode properties, boolean overwrite) {
        if (!properties.has("Value") || properties.get("Value").isNull()) {
            throw invalid("Property Value is required.");
        }
        String value = properties.get("Value").asText();
        String type = textOrDefault(properties, "Type", "String");
        String description = textOrNull(properties, "Description");
        String tier = textOrNull(properties, "Tier");
        String keyId = textOrNull(properties, "KeyId");
        String allowedPattern = textOrNull(properties, "AllowedPattern");
        String dataType = textOrNull(properties, "DataType");
        Map<String, String> tags = overwrite ? Map.of() : parseTags(properties.get("Tags"));
        ssmService.putParameter(name, value, type, description, overwrite, region, tags,
                tier, keyId, allowedPattern, dataType);
    }

    private ResourceDescription getSsmParameter(String region, String identifier) {
        try {
            return ssmDescription(ssmService.getParameter(identifier, region));
        } catch (AwsException e) {
            if ("ParameterNotFound".equals(e.getErrorCode())) {
                throw notFound(TYPE_SSM_PARAMETER, identifier);
            }
            throw e;
        }
    }

    private List<ResourceDescription> s3Buckets() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Bucket bucket : s3Service.listBuckets()) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("BucketName", bucket.getName());
            resources.add(new ResourceDescription(bucket.getName(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> vpcs(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Vpc vpc : ec2Service.describeVpcs(region, List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("VpcId", vpc.getVpcId());
            properties.put("CidrBlock", vpc.getCidrBlock());
            properties.put("InstanceTenancy", vpc.getInstanceTenancy());
            addTags(properties, vpc.getTags());
            resources.add(new ResourceDescription(vpc.getVpcId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> subnets(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Subnet subnet : ec2Service.describeSubnets(region, List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("SubnetId", subnet.getSubnetId());
            properties.put("VpcId", subnet.getVpcId());
            properties.put("CidrBlock", subnet.getCidrBlock());
            properties.put("AvailabilityZone", subnet.getAvailabilityZone());
            addTags(properties, subnet.getTags());
            resources.add(new ResourceDescription(subnet.getSubnetId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> securityGroups(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (SecurityGroup group : ec2Service.describeSecurityGroups(region, List.of(), List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("GroupId", group.getGroupId());
            properties.put("GroupName", group.getGroupName());
            properties.put("GroupDescription", group.getDescription());
            properties.put("VpcId", group.getVpcId());
            addTags(properties, group.getTags());
            resources.add(new ResourceDescription(group.getGroupId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> roles() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (IamRole role : iamService.listRoles("/")) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("Arn", role.getArn());
            properties.put("RoleName", role.getRoleName());
            properties.put("Path", role.getPath());
            resources.add(new ResourceDescription(role.getRoleName(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> users() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (IamUser user : iamService.listUsers("/")) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("Arn", user.getArn());
            properties.put("UserName", user.getUserName());
            properties.put("Path", user.getPath());
            resources.add(new ResourceDescription(user.getUserName(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> ssmParameters(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Parameter parameter : ssmService.describeParameters(region)) {
            resources.add(ssmDescription(parameter));
        }
        return resources;
    }

    private ResourceDescription ssmDescription(Parameter parameter) {
        ObjectNode properties = mapper.createObjectNode();
        properties.put("Name", parameter.getName());
        if (parameter.getType() != null) {
            properties.put("Type", parameter.getType());
        }
        if (parameter.getValue() != null) {
            properties.put("Value", parameter.getValue());
        }
        if (parameter.getDescription() != null) {
            properties.put("Description", parameter.getDescription());
        }
        if (parameter.getDataType() != null) {
            properties.put("DataType", parameter.getDataType());
        }
        if (parameter.getTier() != null) {
            properties.put("Tier", parameter.getTier());
        }
        if (parameter.getAllowedPattern() != null) {
            properties.put("AllowedPattern", parameter.getAllowedPattern());
        }
        if (parameter.getKeyId() != null) {
            properties.put("KeyId", parameter.getKeyId());
        }
        if (parameter.getTags() != null && !parameter.getTags().isEmpty()) {
            var tagArray = properties.putArray("Tags");
            for (Map.Entry<String, String> tag : parameter.getTags().entrySet()) {
                tagArray.addObject()
                        .put("Key", tag.getKey())
                        .put("Value", tag.getValue() == null ? "" : tag.getValue());
            }
        }
        return new ResourceDescription(parameter.getName(), propertiesString(properties));
    }

    private ProgressEvent store(String operation, String typeName, ResourceDescription resource,
                                String clientToken, String fingerprint) {
        ProgressEvent event = new ProgressEvent(
                typeName,
                resource.identifier(),
                UUID.randomUUID().toString(),
                operation,
                "SUCCESS",
                epochSeconds(),
                resource.properties(),
                null,
                null);
        requests.put(event.requestToken(), event);
        if (clientToken != null && !clientToken.isBlank()) {
            clientTokens.put(clientToken, new ClientTokenRecord(fingerprint, event.requestToken()));
        }
        return event;
    }

    private ProgressEvent replayClientToken(String clientToken, String fingerprint) {
        if (clientToken == null || clientToken.isBlank()) {
            return null;
        }
        ClientTokenRecord existing = clientTokens.get(clientToken);
        if (existing == null) {
            return null;
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new AwsException("ClientTokenConflictException",
                    "ClientToken is already associated with a different request.", 409);
        }
        return getResourceRequestStatus(existing.requestToken());
    }

    private String propertiesString(ObjectNode properties) {
        try {
            return mapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure",
                    "Failed to serialize CloudControl resource properties.", 500);
        }
    }

    private void addTags(ObjectNode properties, List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        List<Tag> validTags = tags.stream()
                .filter(tag -> tag != null && tag.getKey() != null && !tag.getKey().isBlank())
                .toList();
        if (validTags.isEmpty()) {
            return;
        }
        var tagArray = properties.putArray("Tags");
        for (Tag tag : validTags) {
            tagArray.addObject()
                    .put("Key", tag.getKey())
                    .put("Value", tag.getValue() == null ? "" : tag.getValue());
        }
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return Map.of();
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : tagsNode) {
            String key = tag.path("Key").asText(null);
            if (key == null || key.isBlank()) {
                continue;
            }
            tags.put(key, tag.path("Value").asText(""));
        }
        return tags;
    }

    private static void requireTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            throw invalid("TypeName is required.");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String text = textOrNull(node, field);
        return text == null ? fallback : text;
    }

    private static long epochSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String typeName, String identifier) {
        return new AwsException("ResourceNotFoundException",
                "Resource of type '" + typeName + "' with identifier '" + identifier + "' was not found.",
                404);
    }

    private static AwsException typeNotFound(String typeName) {
        return new AwsException("TypeNotFoundException",
                "Type " + typeName + " is not found.", 404);
    }

    private static AwsException unsupported(String action, String typeName) {
        return new AwsException("UnsupportedActionException",
                action + " is not supported for " + typeName + ".", 405);
    }

    public record ResourceDescription(String identifier, String properties) {}

    public record ProgressEvent(
            String typeName,
            String identifier,
            String requestToken,
            String operation,
            String operationStatus,
            long eventTime,
            String resourceModel,
            String statusMessage,
            String errorCode
    ) {}

    private record ClientTokenRecord(String fingerprint, String requestToken) {}
}
