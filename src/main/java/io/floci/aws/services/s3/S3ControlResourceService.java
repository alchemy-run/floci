package io.floci.aws.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * S3 Control's account resources: access points (with policies and scopes), Object Lambda access
 * points, Multi-Region Access Points (created, deleted and given policies through asynchronous
 * requests, with failover routes), and S3 Storage Lens configurations, each with tags. Requests and
 * responses are JSON trees in the API model's member names, decoded and encoded by
 * {@link io.floci.aws.core.common.RestXmlCodec}.
 */
@ApplicationScoped
public class S3ControlResourceService implements Resettable {
    static final long MRAP_CREATING_MILLIS = 1_000;
    private static final Pattern ACCESS_POINT_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{1,48}[a-z0-9])");
    private static final Pattern MRAP_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{1,48}[a-z0-9])");
    private static final Pattern LENS_GROUP_NAME = Pattern.compile("[a-zA-Z0-9\\-_]{1,64}");
    private static final Pattern LENS_ID = Pattern.compile("[a-zA-Z0-9\\-_.]{1,64}");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> accessPoints;
    private final AccountAwareStorageBackend<ObjectNode> objectLambdaPoints;
    private final AccountAwareStorageBackend<ObjectNode> multiRegionPoints;
    private final AccountAwareStorageBackend<ObjectNode> asyncRequests;
    private final AccountAwareStorageBackend<ObjectNode> lenses;
    private final AccountAwareStorageBackend<ObjectNode> lensGroups;
    private final S3Service s3;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public S3ControlResourceService(StorageFactory storageFactory, S3Service s3, RegionResolver regionResolver,
                                    ObjectMapper mapper) {
        this.accessPoints = store(storageFactory, "s3control-access-points.json");
        this.objectLambdaPoints = store(storageFactory, "s3control-object-lambda-access-points.json");
        this.multiRegionPoints = store(storageFactory, "s3control-multi-region-access-points.json");
        this.asyncRequests = store(storageFactory, "s3control-async-requests.json");
        this.lenses = store(storageFactory, "s3control-storage-lens.json");
        this.lensGroups = store(storageFactory, "s3control-storage-lens-groups.json");
        this.s3 = s3;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    private static AccountAwareStorageBackend<ObjectNode> store(StorageFactory factory, String file) {
        return factory.create("s3", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    // ── Access points ──────────────────────────────────────────────────────

    public synchronized ObjectNode createAccessPoint(ObjectNode in, String region) {
        String name = in.path("Name").asText(null);
        if (name == null || !ACCESS_POINT_NAME.matcher(name).matches() || name.contains("--")) {
            throw invalid("InvalidURI", "The access point name " + name + " is not valid: 3-50 lowercase letters, "
                    + "digits and hyphens, starting and ending with a letter or digit.");
        }
        String bucket = in.path("Bucket").asText(null);
        if (bucket == null || !s3.bucketExists(bucket)) {
            throw new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404);
        }
        String key = key(region, name);
        if (accessPoints.get(key).isPresent()) {
            throw new AwsException("AccessPointAlreadyOwnedByYou", "Your previous request to create the named "
                    + "accesspoint succeeded and you already own it.", 409);
        }
        ObjectNode accessPoint = mapper.createObjectNode();
        accessPoint.put("Name", name);
        accessPoint.put("Bucket", bucket);
        accessPoint.put("NetworkOrigin", in.has("VpcConfiguration") ? "VPC" : "Internet");
        if (in.has("VpcConfiguration")) {
            accessPoint.set("VpcConfiguration", in.get("VpcConfiguration").deepCopy());
        }
        accessPoint.set("PublicAccessBlockConfiguration", publicAccessBlock(in.path("PublicAccessBlockConfiguration")));
        accessPoint.put("CreationDate", Instant.now().toString());
        accessPoint.put("Alias", name + "-" + random(34) + "-s3alias");
        accessPoint.put("AccessPointArn", arn("s3", region, "accesspoint/" + name));
        accessPoint.put("BucketAccountId", in.path("BucketAccountId").asText(regionResolver.getAccountId()));
        if (in.has("Scope")) {
            accessPoint.set("Scope", in.get("Scope").deepCopy());
        }
        accessPoint.set("Tags", tagMap(in.path("Tags")));
        accessPoints.put(key, accessPoint);
        ObjectNode out = mapper.createObjectNode();
        out.put("AccessPointArn", accessPoint.path("AccessPointArn").asText());
        out.put("Alias", accessPoint.path("Alias").asText());
        return out;
    }

    public ObjectNode getAccessPoint(String name, String region) {
        ObjectNode accessPoint = requireAccessPoint(name, region).deepCopy();
        accessPoint.remove(List.of("Policy", "Tags", "Scope"));
        ObjectNode endpoints = accessPoint.putObject("Endpoints");
        String host = "s3-accesspoint." + region + "." + AwsRegions.dnsSuffixFor(region);
        endpoints.put("ipv4", host);
        endpoints.put("dualstack", host.replace("s3-accesspoint.", "s3-accesspoint.dualstack."));
        return accessPoint;
    }

    public synchronized void deleteAccessPoint(String name, String region) {
        requireAccessPoint(name, region);
        for (ObjectNode olap : objectLambdaPoints.scan(k -> k.startsWith(region + "::"))) {
            if (olap.path("Configuration").path("SupportingAccessPoint").asText()
                    .equals(arn("s3", region, "accesspoint/" + name))) {
                throw new AwsException("InvalidRequest", "The access point is the supporting access point of Object "
                        + "Lambda access point " + olap.path("Name").asText() + ".", 400);
            }
        }
        accessPoints.delete(key(region, name));
    }

    public ObjectNode listAccessPoints(ObjectNode in, String region) {
        String bucket = in.path("Bucket").asText(null);
        List<ObjectNode> all = accessPoints.scan(k -> k.startsWith(region + "::")).stream()
                .filter(ap -> bucket == null || bucket.equals(ap.path("Bucket").asText()))
                .sorted(Comparator.comparing(ap -> ap.path("Name").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("AccessPointList");
        page(all, in, out).forEach(ap -> {
            ObjectNode summary = list.addObject();
            for (String field : List.of("Name", "NetworkOrigin", "VpcConfiguration", "Bucket", "AccessPointArn",
                    "Alias", "BucketAccountId")) {
                if (ap.has(field)) {
                    summary.set(field, ap.get(field).deepCopy());
                }
            }
        });
        return out;
    }

    public synchronized void putAccessPointPolicy(String name, String policy, String region) {
        ObjectNode accessPoint = requireAccessPoint(name, region);
        validPolicy(policy);
        accessPoint.put("Policy", policy);
        accessPoints.put(key(region, name), accessPoint);
    }

    public ObjectNode getAccessPointPolicy(String name, String region) {
        ObjectNode accessPoint = requireAccessPoint(name, region);
        if (!accessPoint.has("Policy")) {
            throw new AwsException("NoSuchAccessPointPolicy", "The specified accesspoint policy does not exist.", 404);
        }
        return mapper.createObjectNode().put("Policy", accessPoint.path("Policy").asText());
    }

    public synchronized void deleteAccessPointPolicy(String name, String region) {
        ObjectNode accessPoint = requireAccessPoint(name, region);
        accessPoint.remove("Policy");
        accessPoints.put(key(region, name), accessPoint);
    }

    public ObjectNode getAccessPointPolicyStatus(String name, String region) {
        ObjectNode accessPoint = requireAccessPoint(name, region);
        if (!accessPoint.has("Policy")) {
            throw new AwsException("NoSuchAccessPointPolicy", "The specified accesspoint policy does not exist.", 404);
        }
        ObjectNode out = mapper.createObjectNode();
        out.putObject("PolicyStatus").put("IsPublic", isPublic(accessPoint.path("Policy").asText()));
        return out;
    }

    public synchronized void putAccessPointScope(String name, JsonNode scope, String region) {
        ObjectNode accessPoint = requireAccessPoint(name, region);
        accessPoint.set("Scope", scope.deepCopy());
        accessPoints.put(key(region, name), accessPoint);
    }

    public ObjectNode getAccessPointScope(String name, String region) {
        ObjectNode accessPoint = requireAccessPoint(name, region);
        ObjectNode out = mapper.createObjectNode();
        out.set("Scope", accessPoint.has("Scope") ? accessPoint.get("Scope").deepCopy() : mapper.createObjectNode());
        return out;
    }

    public synchronized void deleteAccessPointScope(String name, String region) {
        ObjectNode accessPoint = requireAccessPoint(name, region);
        accessPoint.remove("Scope");
        accessPoints.put(key(region, name), accessPoint);
    }

    ObjectNode requireAccessPoint(String name, String region) {
        return accessPoints.get(key(region, name)).orElseThrow(() -> new AwsException("NoSuchAccessPoint",
                "The specified accesspoint does not exist", 404));
    }

    // ── Object Lambda access points ────────────────────────────────────────

    public synchronized ObjectNode createObjectLambdaAccessPoint(ObjectNode in, String region) {
        String name = in.path("Name").asText(null);
        if (name == null || !ACCESS_POINT_NAME.matcher(name).matches()) {
            throw invalid("InvalidURI", "The Object Lambda access point name " + name + " is not valid.");
        }
        JsonNode configuration = in.path("Configuration");
        supportingAccessPoint(configuration, region);
        checkTransformations(configuration);
        String key = key(region, name);
        if (objectLambdaPoints.get(key).isPresent()) {
            throw new AwsException("AccessPointAlreadyOwnedByYou", "Your previous request to create the named "
                    + "accesspoint succeeded and you already own it.", 409);
        }
        ObjectNode olap = mapper.createObjectNode();
        olap.put("Name", name);
        olap.set("Configuration", configuration.deepCopy());
        olap.set("PublicAccessBlockConfiguration", publicAccessBlock(mapper.createObjectNode()));
        olap.put("CreationDate", Instant.now().toString());
        olap.putObject("Alias").put("Value", name + "-" + random(34) + "--ol-s3").put("Status", "READY");
        olap.put("ObjectLambdaAccessPointArn", arn("s3-object-lambda", region, "accesspoint/" + name));
        objectLambdaPoints.put(key, olap);
        ObjectNode out = mapper.createObjectNode();
        out.put("ObjectLambdaAccessPointArn", olap.path("ObjectLambdaAccessPointArn").asText());
        out.set("Alias", olap.get("Alias").deepCopy());
        return out;
    }

    private void supportingAccessPoint(JsonNode configuration, String region) {
        String supporting = configuration.path("SupportingAccessPoint").asText(null);
        if (supporting == null || !AwsArnUtils.isArnFor(supporting, "s3")) {
            throw invalid("InvalidRequest", "SupportingAccessPoint must be an access point ARN.");
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(supporting);
        String resource = arn.resource();
        if (!resource.startsWith("accesspoint/")) {
            throw invalid("InvalidRequest", "SupportingAccessPoint must be an access point ARN.");
        }
        String regionOfArn = arn.region().isEmpty() ? region : arn.region();
        if (accessPoints.get(key(regionOfArn, resource.substring("accesspoint/".length()))).isEmpty()) {
            throw new AwsException("NoSuchAccessPoint", "The specified accesspoint does not exist", 404);
        }
    }

    private static void checkTransformations(JsonNode configuration) {
        JsonNode transformations = configuration.path("TransformationConfigurations");
        if (!transformations.isArray() || transformations.isEmpty()) {
            throw invalid("InvalidRequest", "At least one TransformationConfiguration is required.");
        }
        for (JsonNode transformation : transformations) {
            JsonNode actions = transformation.path("Actions");
            if (!actions.isArray() || actions.isEmpty()) {
                throw invalid("InvalidRequest", "Every TransformationConfiguration needs Actions.");
            }
            String function = transformation.path("ContentTransformation").path("AwsLambda").path("FunctionArn")
                    .asText(null);
            if (function == null || !AwsArnUtils.isArnFor(function, "lambda")) {
                throw invalid("InvalidRequest", "ContentTransformation.AwsLambda.FunctionArn must be a Lambda ARN.");
            }
        }
    }

    public ObjectNode getObjectLambdaAccessPoint(String name, String region) {
        ObjectNode olap = requireObjectLambda(name, region).deepCopy();
        olap.remove(List.of("Configuration", "Policy", "ObjectLambdaAccessPointArn"));
        return olap;
    }

    public ObjectNode getObjectLambdaConfiguration(String name, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Configuration", requireObjectLambda(name, region).get("Configuration").deepCopy());
        return out;
    }

    public synchronized void putObjectLambdaConfiguration(String name, JsonNode configuration, String region) {
        ObjectNode olap = requireObjectLambda(name, region);
        supportingAccessPoint(configuration, region);
        checkTransformations(configuration);
        olap.set("Configuration", configuration.deepCopy());
        objectLambdaPoints.put(key(region, name), olap);
    }

    public synchronized void deleteObjectLambdaAccessPoint(String name, String region) {
        requireObjectLambda(name, region);
        objectLambdaPoints.delete(key(region, name));
    }

    public ObjectNode listObjectLambdaAccessPoints(ObjectNode in, String region) {
        List<ObjectNode> all = objectLambdaPoints.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(ap -> ap.path("Name").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("ObjectLambdaAccessPointList");
        page(all, in, out).forEach(ap -> list.addObject().put("Name", ap.path("Name").asText())
                .put("ObjectLambdaAccessPointArn", ap.path("ObjectLambdaAccessPointArn").asText())
                .set("Alias", ap.get("Alias").deepCopy()));
        return out;
    }

    public synchronized void putObjectLambdaPolicy(String name, String policy, String region) {
        ObjectNode olap = requireObjectLambda(name, region);
        validPolicy(policy);
        olap.put("Policy", policy);
        objectLambdaPoints.put(key(region, name), olap);
    }

    public ObjectNode getObjectLambdaPolicy(String name, String region) {
        ObjectNode olap = requireObjectLambda(name, region);
        if (!olap.has("Policy")) {
            throw new AwsException("NoSuchAccessPointPolicy", "The specified accesspoint policy does not exist.", 404);
        }
        return mapper.createObjectNode().put("Policy", olap.path("Policy").asText());
    }

    public synchronized void deleteObjectLambdaPolicy(String name, String region) {
        ObjectNode olap = requireObjectLambda(name, region);
        olap.remove("Policy");
        objectLambdaPoints.put(key(region, name), olap);
    }

    public ObjectNode getObjectLambdaPolicyStatus(String name, String region) {
        ObjectNode olap = requireObjectLambda(name, region);
        ObjectNode out = mapper.createObjectNode();
        out.putObject("PolicyStatus").put("IsPublic", olap.has("Policy") && isPublic(olap.path("Policy").asText()));
        return out;
    }

    private ObjectNode requireObjectLambda(String name, String region) {
        return objectLambdaPoints.get(key(region, name)).orElseThrow(() -> new AwsException("NoSuchAccessPoint",
                "The specified accesspoint does not exist", 404));
    }

    // ── Multi-Region Access Points ─────────────────────────────────────────

    public synchronized ObjectNode createMultiRegionAccessPoint(ObjectNode in) {
        JsonNode details = in.path("Details");
        String name = details.path("Name").asText(null);
        if (name == null || !MRAP_NAME.matcher(name).matches()) {
            throw invalid("InvalidRequest", "The Multi-Region Access Point name " + name + " is not valid.");
        }
        if (multiRegionPoints.get(name).isPresent()) {
            throw new AwsException("MultiRegionAccessPointAlreadyOwnedByYou", "You already own a Multi-Region Access "
                    + "Point named " + name + ".", 409);
        }
        JsonNode regions = details.path("Regions");
        if (!regions.isArray() || regions.isEmpty() || regions.size() > 17) {
            throw invalid("InvalidRequest", "A Multi-Region Access Point needs 1 to 17 Regions.");
        }
        ArrayNode reports = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode region : regions) {
            String bucket = region.path("Bucket").asText(null);
            if (bucket == null || !s3.bucketExists(bucket)) {
                throw new AwsException("NoSuchBucket", "The specified bucket " + bucket + " does not exist.", 404);
            }
            String bucketRegion = s3.getBucketRegion(bucket);
            if (!seen.add(bucketRegion)) {
                throw invalid("InvalidRequest", "A Multi-Region Access Point can have one bucket per Region.");
            }
            reports.addObject().put("Bucket", bucket).put("Region", bucketRegion)
                    .put("BucketAccountId", region.path("BucketAccountId").asText(regionResolver.getAccountId()));
        }
        ObjectNode mrap = mapper.createObjectNode();
        mrap.put("Name", name);
        mrap.put("Alias", random(13) + ".mrap");
        mrap.put("CreatedAt", Instant.now().toString());
        mrap.set("PublicAccessBlock", publicAccessBlock(details.path("PublicAccessBlock")));
        mrap.set("Regions", reports);
        ArrayNode routes = mrap.putArray("Routes");
        reports.forEach(r -> routes.addObject().put("Bucket", r.path("Bucket").asText())
                .put("Region", r.path("Region").asText()).put("TrafficDialPercentage", 100));
        multiRegionPoints.put(name, mrap);
        return asyncRequest("CreateMultiRegionAccessPoint", "create", in, reports, null);
    }

    public synchronized ObjectNode deleteMultiRegionAccessPoint(ObjectNode in) {
        String name = in.path("Details").path("Name").asText(null);
        ObjectNode mrap = requireMultiRegion(name);
        multiRegionPoints.delete(name);
        return asyncRequest("DeleteMultiRegionAccessPoint", "delete", in, mrap.get("Regions"), null);
    }

    public synchronized ObjectNode putMultiRegionAccessPointPolicy(ObjectNode in) {
        String name = in.path("Details").path("Name").asText(null);
        ObjectNode mrap = requireMultiRegion(name);
        String policy = in.path("Details").path("Policy").asText(null);
        validPolicy(policy);
        mrap.put("Policy", policy);
        multiRegionPoints.put(name, mrap);
        return asyncRequest("PutMultiRegionAccessPointPolicy", "put-policy", in, mrap.get("Regions"), null);
    }

    private ObjectNode asyncRequest(String operation, String verb, ObjectNode in, JsonNode regions, String error) {
        // Multi-Region Access Points are managed from us-west-2, whatever Region the caller uses.
        String token = AwsArnUtils.Arn.of("s3", "us-west-2", regionResolver.getAccountId(), // partition-literal: MRAP control plane Region
                "async-request/mrap/" + verb + "/" + UUID.randomUUID().toString().replace("-", "")).toString();
        ObjectNode request = mapper.createObjectNode();
        request.put("CreationTime", Instant.now().toString());
        request.put("Operation", operation);
        request.put("RequestTokenARN", token);
        ObjectNode parameters = request.putObject("RequestParameters");
        ObjectNode copy = in.deepCopy();
        copy.remove("AccountId");
        parameters.set(operation + "Request", copy);
        request.put("RequestStatus", error == null ? "SUCCEEDED" : "FAILED");
        ObjectNode details = request.putObject("ResponseDetails");
        ArrayNode regionReports = details.putObject("MultiRegionAccessPointDetails").putArray("Regions");
        regions.forEach(r -> regionReports.addObject().put("Name", r.path("Region").asText())
                .put("RequestStatus", error == null ? "SUCCEEDED" : "FAILED"));
        asyncRequests.put(token.substring(token.indexOf("async-request/mrap/") + "async-request/mrap/".length()),
                request);
        return mapper.createObjectNode().put("RequestTokenARN", token);
    }

    public ObjectNode describeAsyncOperation(String requestToken) {
        String key = requestToken.contains("async-request/mrap/")
                ? requestToken.substring(requestToken.indexOf("async-request/mrap/") + "async-request/mrap/".length())
                : requestToken;
        ObjectNode request = asyncRequests.get(key).orElseThrow(() -> new AwsException("NoSuchAsyncRequest",
                "The specified asynchronous request does not exist.", 404));
        ObjectNode operation = request.deepCopy();
        long age = Instant.now().toEpochMilli() - Instant.parse(request.path("CreationTime").asText()).toEpochMilli();
        if ("CreateMultiRegionAccessPoint".equals(request.path("Operation").asText()) && age < MRAP_CREATING_MILLIS
                && "SUCCEEDED".equals(request.path("RequestStatus").asText())) {
            // Creating takes a moment: the request is in progress until the access point is ready.
            operation.put("RequestStatus", "INPROGRESS");
            operation.withObject("ResponseDetails").withObject("MultiRegionAccessPointDetails")
                    .withArray("Regions").forEach(region -> ((ObjectNode) region).put("RequestStatus", "INPROGRESS"));
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("AsyncOperation", operation);
        return out;
    }

    public ObjectNode getMultiRegionAccessPoint(String name) {
        ObjectNode out = mapper.createObjectNode();
        out.set("AccessPoint", report(requireMultiRegion(name)));
        return out;
    }

    public ObjectNode listMultiRegionAccessPoints(ObjectNode in) {
        List<ObjectNode> all = multiRegionPoints.scan(k -> true).stream()
                .sorted(Comparator.comparing(m -> m.path("Name").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("AccessPoints");
        page(all, in, out).forEach(m -> list.add(report(m)));
        return out;
    }

    public ObjectNode getMultiRegionAccessPointPolicy(String name) {
        ObjectNode mrap = requireMultiRegion(name);
        if (!mrap.has("Policy")) {
            throw new AwsException("NoSuchMultiRegionAccessPointPolicy", "The Multi-Region Access Point has no policy.",
                    404);
        }
        ObjectNode out = mapper.createObjectNode();
        out.putObject("Policy").putObject("Established").put("Policy", mrap.path("Policy").asText());
        return out;
    }

    public ObjectNode getMultiRegionAccessPointPolicyStatus(String name) {
        ObjectNode mrap = requireMultiRegion(name);
        ObjectNode out = mapper.createObjectNode();
        out.putObject("Established").put("IsPublic", mrap.has("Policy") && isPublic(mrap.path("Policy").asText()));
        return out;
    }

    public ObjectNode getRoutes(String mrapId) {
        ObjectNode mrap = byAliasOrArn(mrapId);
        ObjectNode out = mapper.createObjectNode();
        out.put("Mrap", mrapId);
        out.set("Routes", mrap.get("Routes").deepCopy());
        return out;
    }

    public synchronized ObjectNode submitRoutes(String mrapId, JsonNode updates) {
        ObjectNode mrap = byAliasOrArn(mrapId);
        ArrayNode routes = (ArrayNode) mrap.get("Routes");
        if (!updates.isArray() || updates.isEmpty()) {
            throw invalid("InvalidRequest", "RouteUpdates must name at least one route.");
        }
        for (JsonNode update : updates) {
            int dial = update.path("TrafficDialPercentage").asInt(-1);
            if (dial < 0 || dial > 100) {
                throw invalid("InvalidRequest", "TrafficDialPercentage must be between 0 and 100.");
            }
            boolean matched = false;
            for (JsonNode route : routes) {
                if (update.path("Bucket").asText("").equals(route.path("Bucket").asText())
                        || update.path("Region").asText("").equals(route.path("Region").asText())) {
                    ((ObjectNode) route).put("TrafficDialPercentage", dial);
                    matched = true;
                }
            }
            if (!matched) {
                throw invalid("InvalidRequest", "The route for " + update.path("Bucket").asText()
                        + " is not part of the Multi-Region Access Point.");
            }
        }
        boolean anyActive = false;
        for (JsonNode route : routes) {
            anyActive |= route.path("TrafficDialPercentage").asInt() > 0;
        }
        if (!anyActive) {
            throw invalid("InvalidRequest", "At least one route must stay active.");
        }
        multiRegionPoints.put(mrap.path("Name").asText(), mrap);
        return mapper.createObjectNode();
    }

    private ObjectNode byAliasOrArn(String id) {
        String alias = id.contains("accesspoint/") ? id.substring(id.indexOf("accesspoint/") + "accesspoint/".length())
                : id;
        for (ObjectNode mrap : multiRegionPoints.scan(k -> true)) {
            if (mrap.path("Alias").asText().equals(alias) || mrap.path("Name").asText().equals(alias)) {
                return mrap;
            }
        }
        throw new AwsException("NoSuchMultiRegionAccessPoint", "The specified Multi-Region Access Point does not "
                + "exist.", 404);
    }

    private ObjectNode report(ObjectNode mrap) {
        ObjectNode report = mapper.createObjectNode();
        for (String field : List.of("Name", "Alias", "CreatedAt", "PublicAccessBlock", "Regions")) {
            report.set(field, mrap.get(field).deepCopy());
        }
        long age = Instant.now().toEpochMilli() - Instant.parse(mrap.path("CreatedAt").asText()).toEpochMilli();
        report.put("Status", age < MRAP_CREATING_MILLIS ? "CREATING" : "READY");
        return report;
    }

    private ObjectNode requireMultiRegion(String name) {
        if (name == null) {
            throw invalid("InvalidRequest", "Name is required.");
        }
        return multiRegionPoints.get(name).orElseThrow(() -> new AwsException("NoSuchMultiRegionAccessPoint",
                "The specified Multi-Region Access Point does not exist.", 404));
    }

    // ── Storage Lens ───────────────────────────────────────────────────────

    public synchronized void putStorageLens(String id, ObjectNode in, String region) {
        if (id == null || !LENS_ID.matcher(id).matches()) {
            throw invalid("InvalidRequest", "The Storage Lens configuration id " + id + " is not valid.");
        }
        JsonNode configuration = in.path("StorageLensConfiguration");
        if (!configuration.isObject() || !id.equals(configuration.path("Id").asText())) {
            throw invalid("InvalidRequest", "StorageLensConfiguration.Id must match the configuration id.");
        }
        if (!configuration.has("AccountLevel") || !configuration.has("IsEnabled")) {
            throw invalid("InvalidRequest", "StorageLensConfiguration needs AccountLevel and IsEnabled.");
        }
        if (configuration.has("Include") && configuration.has("Exclude")) {
            throw invalid("InvalidRequest", "A Storage Lens configuration can't have both Include and Exclude.");
        }
        ObjectNode stored = lenses.get(key(region, id)).orElse(null);
        ObjectNode lens = mapper.createObjectNode();
        ObjectNode config = configuration.deepCopy();
        config.put("StorageLensArn", arn("s3", region, "storage-lens/" + id));
        lens.set("StorageLensConfiguration", config);
        lens.put("HomeRegion", region);
        lens.set("Tags", in.has("Tags") ? tagMap(in.get("Tags")) : stored != null ? stored.get("Tags")
                : mapper.createObjectNode());
        lenses.put(key(region, id), lens);
    }

    public ObjectNode getStorageLens(String id, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("StorageLensConfiguration", requireLens(id, region).get("StorageLensConfiguration").deepCopy());
        return out;
    }

    public synchronized void deleteStorageLens(String id, String region) {
        requireLens(id, region);
        lenses.delete(key(region, id));
    }

    public ObjectNode listStorageLens(ObjectNode in, String region) {
        List<ObjectNode> all = lenses.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(l -> l.path("StorageLensConfiguration").path("Id").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("StorageLensConfigurationList");
        page(all, in, out).forEach(lens -> {
            JsonNode config = lens.get("StorageLensConfiguration");
            list.addObject().put("Id", config.path("Id").asText())
                    .put("StorageLensArn", config.path("StorageLensArn").asText())
                    .put("HomeRegion", lens.path("HomeRegion").asText())
                    .put("IsEnabled", config.path("IsEnabled").asBoolean());
        });
        return out;
    }

    public synchronized void putStorageLensTags(String id, JsonNode tags, String region) {
        ObjectNode lens = requireLens(id, region);
        lens.set("Tags", tagMap(tags));
        lenses.put(key(region, id), lens);
    }

    public ObjectNode getStorageLensTags(String id, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagList(requireLens(id, region).path("Tags")));
        return out;
    }

    public synchronized void deleteStorageLensTags(String id, String region) {
        ObjectNode lens = requireLens(id, region);
        lens.set("Tags", mapper.createObjectNode());
        lenses.put(key(region, id), lens);
    }

    private ObjectNode requireLens(String id, String region) {
        return lenses.get(key(region, id)).orElseThrow(() -> new AwsException("NoSuchConfiguration",
                "The specified configuration does not exist.", 404));
    }

    // ── Storage Lens groups ────────────────────────────────────────────────

    public synchronized void createStorageLensGroup(ObjectNode in, String region) {
        JsonNode group = in.path("StorageLensGroup");
        String name = group.path("Name").asText("");
        if (!LENS_GROUP_NAME.matcher(name).matches()) {
            throw invalid("InvalidRequest", "The Storage Lens group name " + name + " is not valid.");
        }
        if (lensGroups.get(key(region, name)).isPresent()) {
            throw new AwsException("StorageLensGroupAlreadyExists", "A Storage Lens group named " + name
                    + " already exists.", 409);
        }
        ObjectNode stored = mapper.createObjectNode();
        stored.set("StorageLensGroup", lensGroup(name, group, region));
        stored.put("HomeRegion", region);
        stored.set("Tags", tagMap(in.path("Tags")));
        lensGroups.put(key(region, name), stored);
    }

    private ObjectNode lensGroup(String name, JsonNode group, String region) {
        JsonNode filter = group.path("Filter");
        if (!filter.isObject() || filter.isEmpty()) {
            throw invalid("InvalidRequest", "A Storage Lens group needs a Filter.");
        }
        int conditions = 0;
        for (String field : List.of("MatchAnyPrefix", "MatchAnySuffix", "MatchAnyTag", "MatchObjectAge",
                "MatchObjectSize", "And", "Or")) {
            if (filter.has(field)) {
                conditions++;
            }
        }
        if (conditions == 0) {
            throw invalid("InvalidRequest", "The Storage Lens group Filter has no conditions.");
        }
        if ((filter.has("And") || filter.has("Or")) && conditions > 1) {
            throw invalid("InvalidRequest", "A Filter with And or Or can't have other top-level conditions.");
        }
        JsonNode age = filter.has("And") ? filter.path("And").path("MatchObjectAge") : filter.path("MatchObjectAge");
        if (age.has("DaysGreaterThan") && age.has("DaysLessThan")
                && age.get("DaysGreaterThan").asInt() >= age.get("DaysLessThan").asInt()) {
            throw invalid("InvalidRequest", "MatchObjectAge.DaysGreaterThan must be less than DaysLessThan.");
        }
        JsonNode size = filter.has("And") ? filter.path("And").path("MatchObjectSize")
                : filter.path("MatchObjectSize");
        if (size.has("BytesGreaterThan") && size.has("BytesLessThan")
                && size.get("BytesGreaterThan").asLong() >= size.get("BytesLessThan").asLong()) {
            throw invalid("InvalidRequest", "MatchObjectSize.BytesGreaterThan must be less than BytesLessThan.");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("Name", name);
        out.set("Filter", filter.deepCopy());
        out.put("StorageLensGroupArn", arn("s3", region, "storage-lens-group/" + name));
        return out;
    }

    public ObjectNode getStorageLensGroup(String name, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("StorageLensGroup", requireLensGroup(name, region).get("StorageLensGroup").deepCopy());
        return out;
    }

    public synchronized void updateStorageLensGroup(String name, JsonNode group, String region) {
        ObjectNode stored = requireLensGroup(name, region);
        if (group.hasNonNull("Name") && !name.equals(group.get("Name").asText())) {
            throw invalid("InvalidRequest", "StorageLensGroup.Name must match the group name.");
        }
        stored.set("StorageLensGroup", lensGroup(name, group, region));
        lensGroups.put(key(region, name), stored);
    }

    public synchronized void deleteStorageLensGroup(String name, String region) {
        requireLensGroup(name, region);
        lensGroups.delete(key(region, name));
    }

    public ObjectNode listStorageLensGroups(ObjectNode in, String region) {
        List<ObjectNode> all = lensGroups.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(g -> g.path("StorageLensGroup").path("Name").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("StorageLensGroupList");
        page(all, in, out).forEach(group -> list.addObject()
                .put("Name", group.path("StorageLensGroup").path("Name").asText())
                .put("StorageLensGroupArn", group.path("StorageLensGroup").path("StorageLensGroupArn").asText())
                .put("HomeRegion", group.path("HomeRegion").asText()));
        return out;
    }

    private ObjectNode requireLensGroup(String name, String region) {
        return lensGroups.get(key(region, name)).orElseThrow(() -> new AwsException("NoSuchStorageLensGroup",
                "The Storage Lens group " + name + " does not exist.", 404));
    }

    // ── Tags by ARN ────────────────────────────────────────────────────────

    /** The stored resource an access point, Multi-Region Access Point or Storage Lens ARN names. */
    record Tagged(AccountAwareStorageBackend<ObjectNode> store, String key, ObjectNode resource) {}

    Tagged tagged(String arn, String region) {
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        String arnRegion = parsed.region().isEmpty() ? region : parsed.region();
        if ("s3".equals(parsed.service()) && resource.startsWith("accesspoint/") && !parsed.region().isEmpty()) {
            String key = key(arnRegion, resource.substring("accesspoint/".length()));
            return accessPoints.get(key).map(ap -> new Tagged(accessPoints, key, ap)).orElse(null);
        }
        if ("s3".equals(parsed.service()) && resource.startsWith("accesspoint/")) {
            ObjectNode mrap = byAliasOrArn(arn);
            return new Tagged(multiRegionPoints, mrap.path("Name").asText(), mrap);
        }
        if ("s3".equals(parsed.service()) && resource.startsWith("storage-lens-group/")) {
            String key = key(arnRegion, resource.substring("storage-lens-group/".length()));
            return lensGroups.get(key).map(group -> new Tagged(lensGroups, key, group)).orElse(null);
        }
        if ("s3".equals(parsed.service()) && resource.startsWith("storage-lens/")) {
            String key = key(arnRegion, resource.substring("storage-lens/".length()));
            return lenses.get(key).map(lens -> new Tagged(lenses, key, lens)).orElse(null);
        }
        return null;
    }

    public ArrayNode listTags(Tagged tagged) {
        return tagList(tagged.resource().path("Tags"));
    }

    public synchronized void tag(Tagged tagged, JsonNode tags) {
        ObjectNode resource = tagged.resource();
        ObjectNode current = resource.has("Tags") ? (ObjectNode) resource.get("Tags") : resource.putObject("Tags");
        tags.forEach(tag -> current.put(tag.path("Key").asText(), tag.path("Value").asText("")));
        if (current.size() > 50) {
            throw invalid("InvalidTag", "A resource can have at most 50 tags.");
        }
        tagged.store().put(tagged.key(), resource);
    }

    public synchronized void untag(Tagged tagged, List<String> keys) {
        ObjectNode resource = tagged.resource();
        if (resource.has("Tags")) {
            keys.forEach(((ObjectNode) resource.get("Tags"))::remove);
        }
        tagged.store().put(tagged.key(), resource);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectNode publicAccessBlock(JsonNode requested) {
        ObjectNode block = mapper.createObjectNode();
        for (String flag : List.of("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy",
                "RestrictPublicBuckets")) {
            block.put(flag, requested.path(flag).asBoolean(true));
        }
        return block;
    }

    private void validPolicy(String policy) {
        try {
            JsonNode node = mapper.readTree(policy == null ? "" : policy);
            if (node == null || !node.isObject() || !node.has("Statement")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new AwsException("MalformedPolicy", "Policies must be valid JSON with a Statement.", 400);
        }
    }

    /** A policy is public when an Allow statement grants everyone ({@code *}) without a condition. */
    private boolean isPublic(String policy) {
        try {
            JsonNode statements = mapper.readTree(policy).path("Statement");
            for (JsonNode statement : statements.isArray() ? statements : mapper.createArrayNode().add(statements)) {
                JsonNode principal = statement.path("Principal");
                boolean everyone = "*".equals(principal.asText()) || "*".equals(principal.path("AWS").asText())
                        || principal.path("AWS").isArray() && principal.path("AWS").toString().contains("\"*\"");
                if ("Allow".equals(statement.path("Effect").asText()) && everyone && !statement.has("Condition")) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = mapper.createObjectNode();
        if (tags.isArray()) {
            tags.forEach(tag -> map.put(tag.path("Key").asText(), tag.path("Value").asText("")));
        }
        return map;
    }

    private ArrayNode tagList(JsonNode tags) {
        ArrayNode list = mapper.createArrayNode();
        tags.fields().forEachRemaining(tag -> list.addObject().put("Key", tag.getKey())
                .put("Value", tag.getValue().asText()));
        return list;
    }

    private List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out) {
        int max = in.path("MaxResults").asInt(1000);
        if (max < 1 || max > 1000) {
            max = 1000;
        }
        int start = 0;
        String token = in.path("NextToken").asText(null);
        if (token != null && !token.isEmpty()) {
            try {
                start = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw invalid("InvalidToken", "The continuation token provided is incorrect.");
            }
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("NextToken", Integer.toString(end));
        }
        return new ArrayList<>(all.subList(Math.min(start, end), end));
    }

    private String arn(String service, String region, String resource) {
        return AwsArnUtils.Arn.of(service, region, regionResolver.getAccountId(), resource).toString();
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }

    static AwsException invalid(String code, String message) {
        return new AwsException(code, message, 400);
    }

    @Override
    public void clear() {
        accessPoints.clear();
        objectLambdaPoints.clear();
        multiRegionPoints.clear();
        asyncRequests.clear();
        lenses.clear();
        lensGroups.clear();
    }
}
