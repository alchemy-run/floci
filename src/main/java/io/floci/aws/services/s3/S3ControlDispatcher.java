package io.floci.aws.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsNamespaces;
import io.floci.aws.core.common.RestXmlCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes S3 Control requests to their operations by the HTTP method and URI templates of the
 * vendored API model, decodes each request into the operation's input with {@link RestXmlCodec},
 * calls the service, and encodes the output. Bucket tagging by ARN and the account's Block Public
 * Access configuration keep their own routes in {@link S3ControlController}.
 */
@ApplicationScoped
public class S3ControlDispatcher {
    /** The resource types tagging by ARN serves, as the resource part of their ARNs begins. */
    private static final Pattern TAGGABLE = Pattern.compile(
            ":(accesspoint|storage-lens|storage-lens-group|access-grants)/");

    private record Route(String operation, String method, Pattern pattern, List<String> labels, int literals) {}

    /** A decoded operation result: the response body (null for none), headers, and status. */
    public record Result(String body, Map<String, String> headers, int status) {}

    private final RestXmlCodec codec = RestXmlCodec.load("aws/s3control-rest-xml.json", AwsNamespaces.S3_CONTROL);
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, BiFunction<ObjectNode, String, JsonNode>> handlers = new HashMap<>();
    private final S3ControlResourceService resources;
    private final S3BatchJobService jobs;
    private final S3AccessGrantsService grants;
    private final ObjectMapper mapper;

    @Inject
    public S3ControlDispatcher(S3ControlResourceService resources, S3BatchJobService jobs,
                               S3AccessGrantsService grants, ObjectMapper mapper) {
        this.resources = resources;
        this.jobs = jobs;
        this.grants = grants;
        this.mapper = mapper;
        for (Iterator<Map.Entry<String, JsonNode>> it = codec.operations().fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            routes.add(route(entry.getKey(), entry.getValue()));
        }
        routes.sort(Comparator.comparingInt(Route::literals).reversed());
        for (Route route : routes) {
            BiFunction<ObjectNode, String, JsonNode> handler = handlerFor(route.operation());
            if (handler != null) {
                handlers.put(route.operation(), handler);
            }
        }
    }

    private static Route route(String operation, JsonNode spec) {
        String uri = spec.path("uri").asText();
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        StringBuilder regex = new StringBuilder();
        List<String> labels = new ArrayList<>();
        Matcher labelMatcher = Pattern.compile("\\{([^}+]+)(\\+?)}").matcher(path);
        int last = 0;
        int literals = 0;
        while (labelMatcher.find()) {
            String literal = path.substring(last, labelMatcher.start());
            regex.append(Pattern.quote(literal));
            literals += literal.length();
            labels.add(labelMatcher.group(1));
            regex.append(labelMatcher.group(2).isEmpty() ? "([^/]+)" : "(.+?)");
            last = labelMatcher.end();
        }
        String tail = path.substring(last);
        regex.append(Pattern.quote(tail));
        literals += tail.length();
        return new Route(operation, spec.path("method").asText(), Pattern.compile(regex.toString()), labels, literals);
    }

    /** Whether a request names a modeled S3 Control operation. */
    public String operationFor(String method, String path) {
        for (Route route : routes) {
            if (route.method().equals(method) && route.pattern().matcher(path).matches()) {
                return route.operation();
            }
        }
        return null;
    }

    public Result dispatch(String method, String path, String body, Map<String, List<String>> query,
                           Map<String, List<String>> headers, String region) {
        for (Route route : routes) {
            if (!route.method().equals(method)) {
                continue;
            }
            Matcher matcher = route.pattern().matcher(path);
            if (!matcher.matches()) {
                continue;
            }
            Map<String, String> uri = new LinkedHashMap<>();
            for (int i = 0; i < route.labels().size(); i++) {
                uri.put(route.labels().get(i), URLDecoder.decode(matcher.group(i + 1).replace("+", "%2B"),
                        StandardCharsets.UTF_8));
            }
            ObjectNode input = codec.decode(route.operation(), body, uri, query, headers);
            BiFunction<ObjectNode, String, JsonNode> handler = handlers.get(route.operation());
            if (handler == null) {
                throw new AwsException("NotImplemented", route.operation() + " is not available in Floci.", 501);
            }
            JsonNode output = handler.apply(input, region);
            RestXmlCodec.Encoded encoded = codec.encode(route.operation(), output == null
                    ? mapper.createObjectNode() : output);
            int status = encoded.body() == null && "DELETE".equals(method) ? 204 : 200;
            return new Result(encoded.body(), encoded.headers(), status);
        }
        throw new AwsException("NoSuchResource", "The requested S3 Control resource does not exist.", 404);
    }

    private interface VoidHandler {
        void accept(ObjectNode in, String region);
    }

    /** An operation with no output. */
    private static BiFunction<ObjectNode, String, JsonNode> run(VoidHandler handler) {
        return (in, region) -> {
            handler.accept(in, region);
            return null;
        };
    }

    /** An operation whose output is an empty result element. */
    private BiFunction<ObjectNode, String, JsonNode> empty(VoidHandler handler) {
        return (in, region) -> {
            handler.accept(in, region);
            return mapper.createObjectNode();
        };
    }

    private static String text(ObjectNode in, String field) {
        return in.path(field).asText(null);
    }

    /** The handler for one S3 Control operation, or null for an operation the model has and Floci doesn't. */
    private BiFunction<ObjectNode, String, JsonNode> handlerFor(String operation) {
        return switch (operation) {
            // Access points
            case "CreateAccessPoint" -> resources::createAccessPoint;
            case "GetAccessPoint" -> (in, r) -> resources.getAccessPoint(text(in, "Name"), r);
            case "DeleteAccessPoint" -> run((in, r) -> resources.deleteAccessPoint(text(in, "Name"), r));
            case "ListAccessPoints" -> resources::listAccessPoints;
            case "ListAccessPointsForDirectoryBuckets" -> this::listDirectoryAccessPoints;
            case "PutAccessPointPolicy" -> run((in, r) -> resources.putAccessPointPolicy(text(in, "Name"),
                    text(in, "Policy"), r));
            case "GetAccessPointPolicy" -> (in, r) -> resources.getAccessPointPolicy(text(in, "Name"), r);
            case "DeleteAccessPointPolicy" -> run((in, r) -> resources.deleteAccessPointPolicy(text(in, "Name"), r));
            case "GetAccessPointPolicyStatus" -> (in, r) -> resources.getAccessPointPolicyStatus(text(in, "Name"), r);
            case "PutAccessPointScope" -> run((in, r) -> resources.putAccessPointScope(text(in, "Name"),
                    in.path("Scope"), r));
            case "GetAccessPointScope" -> (in, r) -> resources.getAccessPointScope(text(in, "Name"), r);
            case "DeleteAccessPointScope" -> run((in, r) -> resources.deleteAccessPointScope(text(in, "Name"), r));

            // Object Lambda access points
            case "CreateAccessPointForObjectLambda" -> resources::createObjectLambdaAccessPoint;
            case "GetAccessPointForObjectLambda" -> (in, r) ->
                    resources.getObjectLambdaAccessPoint(text(in, "Name"), r);
            case "GetAccessPointConfigurationForObjectLambda" -> (in, r) ->
                    resources.getObjectLambdaConfiguration(text(in, "Name"), r);
            case "PutAccessPointConfigurationForObjectLambda" -> run((in, r) ->
                    resources.putObjectLambdaConfiguration(text(in, "Name"), in.path("Configuration"), r));
            case "DeleteAccessPointForObjectLambda" -> run((in, r) ->
                    resources.deleteObjectLambdaAccessPoint(text(in, "Name"), r));
            case "ListAccessPointsForObjectLambda" -> resources::listObjectLambdaAccessPoints;
            case "PutAccessPointPolicyForObjectLambda" -> run((in, r) ->
                    resources.putObjectLambdaPolicy(text(in, "Name"), text(in, "Policy"), r));
            case "GetAccessPointPolicyForObjectLambda" -> (in, r) ->
                    resources.getObjectLambdaPolicy(text(in, "Name"), r);
            case "DeleteAccessPointPolicyForObjectLambda" -> run((in, r) ->
                    resources.deleteObjectLambdaPolicy(text(in, "Name"), r));
            case "GetAccessPointPolicyStatusForObjectLambda" -> (in, r) ->
                    resources.getObjectLambdaPolicyStatus(text(in, "Name"), r);

            // Multi-Region Access Points
            case "CreateMultiRegionAccessPoint" -> (in, r) -> resources.createMultiRegionAccessPoint(in);
            case "DeleteMultiRegionAccessPoint" -> (in, r) -> resources.deleteMultiRegionAccessPoint(in);
            case "PutMultiRegionAccessPointPolicy" -> (in, r) -> resources.putMultiRegionAccessPointPolicy(in);
            case "DescribeMultiRegionAccessPointOperation" -> (in, r) ->
                    resources.describeAsyncOperation(text(in, "RequestTokenARN"));
            case "GetMultiRegionAccessPoint" -> (in, r) -> resources.getMultiRegionAccessPoint(text(in, "Name"));
            case "ListMultiRegionAccessPoints" -> (in, r) -> resources.listMultiRegionAccessPoints(in);
            case "GetMultiRegionAccessPointPolicy" -> (in, r) ->
                    resources.getMultiRegionAccessPointPolicy(text(in, "Name"));
            case "GetMultiRegionAccessPointPolicyStatus" -> (in, r) ->
                    resources.getMultiRegionAccessPointPolicyStatus(text(in, "Name"));
            case "GetMultiRegionAccessPointRoutes" -> (in, r) -> resources.getRoutes(text(in, "Mrap"));
            case "SubmitMultiRegionAccessPointRoutes" -> (in, r) -> resources.submitRoutes(text(in, "Mrap"),
                    in.path("RouteUpdates"));

            // Storage Lens configurations and groups
            case "PutStorageLensConfiguration" -> run((in, r) ->
                    resources.putStorageLens(text(in, "ConfigId"), in, r));
            case "GetStorageLensConfiguration" -> (in, r) -> resources.getStorageLens(text(in, "ConfigId"), r);
            case "DeleteStorageLensConfiguration" -> run((in, r) ->
                    resources.deleteStorageLens(text(in, "ConfigId"), r));
            case "ListStorageLensConfigurations" -> resources::listStorageLens;
            case "PutStorageLensConfigurationTagging" -> empty((in, r) ->
                    resources.putStorageLensTags(text(in, "ConfigId"), in.path("Tags"), r));
            case "GetStorageLensConfigurationTagging" -> (in, r) ->
                    resources.getStorageLensTags(text(in, "ConfigId"), r);
            case "DeleteStorageLensConfigurationTagging" -> empty((in, r) ->
                    resources.deleteStorageLensTags(text(in, "ConfigId"), r));
            case "CreateStorageLensGroup" -> run(resources::createStorageLensGroup);
            case "GetStorageLensGroup" -> (in, r) -> resources.getStorageLensGroup(text(in, "Name"), r);
            case "UpdateStorageLensGroup" -> run((in, r) -> resources.updateStorageLensGroup(text(in, "Name"),
                    in.path("StorageLensGroup"), r));
            case "DeleteStorageLensGroup" -> run((in, r) -> resources.deleteStorageLensGroup(text(in, "Name"), r));
            case "ListStorageLensGroups" -> resources::listStorageLensGroups;

            // Batch Operations jobs
            case "CreateJob" -> jobs::create;
            case "DescribeJob" -> (in, r) -> jobs.describe(text(in, "JobId"), r);
            case "ListJobs" -> jobs::list;
            case "UpdateJobPriority" -> (in, r) -> jobs.updatePriority(text(in, "JobId"),
                    in.path("Priority").asLong(-1), r);
            case "UpdateJobStatus" -> (in, r) -> jobs.updateStatus(text(in, "JobId"), text(in, "RequestedJobStatus"),
                    text(in, "StatusUpdateReason"), r);
            case "GetJobTagging" -> (in, r) -> jobs.tagging(text(in, "JobId"), r);
            case "PutJobTagging" -> empty((in, r) -> jobs.putTagging(text(in, "JobId"), in.path("Tags"), r));
            case "DeleteJobTagging" -> empty((in, r) -> jobs.deleteTagging(text(in, "JobId"), r));

            // Access Grants
            case "CreateAccessGrantsInstance" -> grants::createInstance;
            case "GetAccessGrantsInstance" -> (in, r) -> grants.getInstance(r);
            case "DeleteAccessGrantsInstance" -> run((in, r) -> grants.deleteInstance(r));
            case "ListAccessGrantsInstances" -> grants::listInstances;
            case "GetAccessGrantsInstanceForPrefix" -> (in, r) -> grants.instanceForPrefix(text(in, "S3Prefix"), r);
            case "AssociateAccessGrantsIdentityCenter" -> run((in, r) ->
                    grants.associateIdentityCenter(text(in, "IdentityCenterArn"), r));
            case "DissociateAccessGrantsIdentityCenter" -> run((in, r) -> grants.dissociateIdentityCenter(r));
            case "PutAccessGrantsInstanceResourcePolicy" -> (in, r) -> grants.putResourcePolicy(text(in, "Policy"),
                    text(in, "Organization"), r);
            case "GetAccessGrantsInstanceResourcePolicy" -> (in, r) -> grants.getResourcePolicy(r);
            case "DeleteAccessGrantsInstanceResourcePolicy" -> run((in, r) -> grants.deleteResourcePolicy(r));
            case "CreateAccessGrantsLocation" -> grants::createLocation;
            case "GetAccessGrantsLocation" -> (in, r) -> grants.getLocation(text(in, "AccessGrantsLocationId"), r);
            case "UpdateAccessGrantsLocation" -> (in, r) -> grants.updateLocation(text(in, "AccessGrantsLocationId"),
                    text(in, "IAMRoleArn"), r);
            case "DeleteAccessGrantsLocation" -> run((in, r) ->
                    grants.deleteLocation(text(in, "AccessGrantsLocationId"), r));
            case "ListAccessGrantsLocations" -> grants::listLocations;
            case "CreateAccessGrant" -> grants::createGrant;
            case "GetAccessGrant" -> (in, r) -> grants.getGrant(text(in, "AccessGrantId"), r);
            case "DeleteAccessGrant" -> run((in, r) -> grants.deleteGrant(text(in, "AccessGrantId"), r));
            case "ListAccessGrants" -> grants::listGrants;
            case "ListCallerAccessGrants" -> grants::listCallerGrants;
            case "GetDataAccess" -> grants::getDataAccess;

            // Tags by ARN, for every resource but buckets (which the controller tags)
            case "ListTagsForResource" -> (in, r) -> {
                ObjectNode out = mapper.createObjectNode();
                out.set("Tags", resources.listTags(tagged(text(in, "ResourceArn"), r)));
                return out;
            };
            case "TagResource" -> empty((in, r) -> resources.tag(tagged(text(in, "ResourceArn"), r),
                    in.path("Tags")));
            case "UntagResource" -> empty((in, r) -> {
                List<String> keys = new ArrayList<>();
                in.path("TagKeys").forEach(k -> keys.add(k.asText()));
                resources.untag(tagged(text(in, "ResourceArn"), r), keys);
            });

            // S3 on Outposts buckets: Floci runs no Outposts.
            case "ListRegionalBuckets" -> this::listRegionalBuckets;
            case "CreateBucket", "GetBucket", "DeleteBucket", "GetBucketLifecycleConfiguration",
                    "PutBucketLifecycleConfiguration", "DeleteBucketLifecycleConfiguration", "GetBucketPolicy",
                    "PutBucketPolicy", "DeleteBucketPolicy", "GetBucketReplication", "PutBucketReplication",
                    "DeleteBucketReplication", "GetBucketTagging", "PutBucketTagging", "DeleteBucketTagging",
                    "GetBucketVersioning", "PutBucketVersioning" -> (in, r) -> {
                throw noOutpost(in.hasNonNull("OutpostId") ? text(in, "OutpostId") : text(in, "Bucket"));
            };
            default -> null;
        };
    }

    private ObjectNode listDirectoryAccessPoints(ObjectNode in, String region) {
        ObjectNode filtered = in.deepCopy();
        String bucket = text(in, "DirectoryBucket");
        filtered.remove("DirectoryBucket");
        ObjectNode out = resources.listAccessPoints(filtered, region);
        ArrayNode kept = mapper.createArrayNode();
        out.withArray("AccessPointList").forEach(ap -> {
            String apBucket = ap.path("Bucket").asText();
            if (apBucket.endsWith("--x-s3") && (bucket == null || bucket.equals(apBucket))) {
                kept.add(ap);
            }
        });
        out.set("AccessPointList", kept);
        return out;
    }

    private ObjectNode listRegionalBuckets(ObjectNode in, String region) {
        if (in.hasNonNull("OutpostId")) {
            throw noOutpost(text(in, "OutpostId"));
        }
        ObjectNode out = mapper.createObjectNode();
        out.putArray("RegionalBucketList");
        return out;
    }

    private S3ControlResourceService.Tagged tagged(String arn, String region) {
        S3ControlResourceService.Tagged tagged = resources.tagged(arn, region);
        if (tagged == null) {
            tagged = grants.tagged(arn, region);
        }
        if (tagged == null && TAGGABLE.matcher(arn == null ? "" : arn).find()) {
            throw new AwsException("NoSuchResource", "The resource " + arn + " does not exist.", 404);
        }
        if (tagged == null) {
            throw new AwsException("InvalidRequest", "Unsupported resource type. Tags by ARN apply to buckets, "
                    + "access points, Multi-Region Access Points, Storage Lens configurations and groups, and "
                    + "Access Grants instances, locations and grants.", 400);
        }
        return tagged;
    }

    private static AwsException noOutpost(String id) {
        return new AwsException("NoSuchOutpost", "S3 on Outposts needs an Outpost, and Floci has none"
                + (id == null ? "." : ": " + id + "."), 404);
    }
}
