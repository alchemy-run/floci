package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.CreateRequest;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.ModifyRequest;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.Page;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService.UsageLimits;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query-protocol actions of ElastiCache serverless caches and serverless cache snapshots, plus
 * the tag actions for their ARNs. {@link ElastiCacheQueryHandler} routes these actions here.
 */
@ApplicationScoped
public class ElastiCacheServerlessQueryHandler {

    static final Set<String> ACTIONS = Set.of(
            "CreateServerlessCache", "DescribeServerlessCaches", "ModifyServerlessCache", "DeleteServerlessCache",
            "CreateServerlessCacheSnapshot", "DescribeServerlessCacheSnapshots", "DeleteServerlessCacheSnapshot",
            "CopyServerlessCacheSnapshot", "ExportServerlessCacheSnapshot");
    private static final Set<String> TAG_ACTIONS = Set.of(
            "ListTagsForResource", "AddTagsToResource", "RemoveTagsFromResource");
    private static final Set<String> RESOURCE_TYPES = Set.of("serverlesscache", "serverlesscachesnapshot");

    private final ElastiCacheServerlessService service;
    private final RegionResolver regionResolver;

    @Inject
    public ElastiCacheServerlessQueryHandler(ElastiCacheServerlessService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    /** Whether this handler answers {@code action}; tag actions only for serverless ARNs. */
    public boolean handles(String action, MultivaluedMap<String, String> params) {
        if (ACTIONS.contains(action)) {
            return true;
        }
        if (!TAG_ACTIONS.contains(action)) {
            return false;
        }
        String[] arn = splitArn(params.getFirst("ResourceName"));
        return arn.length == 7 && "elasticache".equals(arn[2]) && RESOURCE_TYPES.contains(arn[5]);
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        try {
            String result = switch (action) {
                case "CreateServerlessCache" -> serverlessCacheXml(service.createServerlessCache(
                        createRequest(params), region));
                case "DescribeServerlessCaches" -> describeServerlessCaches(params, region);
                case "ModifyServerlessCache" -> serverlessCacheXml(service.modifyServerlessCache(
                        modifyRequest(params), region));
                case "DeleteServerlessCache" -> serverlessCacheXml(service.deleteServerlessCache(
                        params.getFirst("ServerlessCacheName"), params.getFirst("FinalSnapshotName"), region));
                case "CreateServerlessCacheSnapshot" -> snapshotXml(service.createServerlessCacheSnapshot(
                        params.getFirst("ServerlessCacheSnapshotName"), params.getFirst("ServerlessCacheName"),
                        params.getFirst("KmsKeyId"), parseTags(params), region));
                case "DescribeServerlessCacheSnapshots" -> describeSnapshots(params, region);
                case "DeleteServerlessCacheSnapshot" -> snapshotXml(service.deleteServerlessCacheSnapshot(
                        params.getFirst("ServerlessCacheSnapshotName"), region));
                case "CopyServerlessCacheSnapshot" -> snapshotXml(service.copyServerlessCacheSnapshot(
                        params.getFirst("SourceServerlessCacheSnapshotName"),
                        params.getFirst("TargetServerlessCacheSnapshotName"),
                        params.getFirst("KmsKeyId"), parseTags(params), region));
                case "ExportServerlessCacheSnapshot" -> snapshotXml(service.exportServerlessCacheSnapshot(
                        params.getFirst("ServerlessCacheSnapshotName"), params.getFirst("S3BucketName"), region));
                case "ListTagsForResource" -> listTags(params, region);
                case "AddTagsToResource" -> addTags(params, region);
                case "RemoveTagsFromResource" -> removeTags(params, region);
                default -> throw new AwsException("UnsupportedOperation",
                        "Operation " + action + " is not supported.", 400);
            };
            return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.EC, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private String describeServerlessCaches(MultivaluedMap<String, String> params, String region) {
        Page<ServerlessCache> page = service.describeServerlessCaches(params.getFirst("ServerlessCacheName"),
                intParam(params, "MaxResults"), params.getFirst("NextToken"), region);
        XmlBuilder xml = new XmlBuilder();
        xml.elem("NextToken", page.nextToken());
        xml.start("ServerlessCaches");
        for (ServerlessCache cache : page.items()) {
            xml.raw(serverlessCacheXml("member", cache));
        }
        return xml.end("ServerlessCaches").build();
    }

    private String describeSnapshots(MultivaluedMap<String, String> params, String region) {
        Page<ServerlessCacheSnapshot> page = service.describeServerlessCacheSnapshots(
                params.getFirst("ServerlessCacheName"), params.getFirst("ServerlessCacheSnapshotName"),
                params.getFirst("SnapshotType"), intParam(params, "MaxResults"), params.getFirst("NextToken"),
                region);
        XmlBuilder xml = new XmlBuilder();
        xml.elem("NextToken", page.nextToken());
        xml.start("ServerlessCacheSnapshots");
        for (ServerlessCacheSnapshot snapshot : page.items()) {
            xml.raw(snapshotXml(snapshot));
        }
        return xml.end("ServerlessCacheSnapshots").build();
    }

    private String listTags(MultivaluedMap<String, String> params, String region) {
        String[] arn = requireOwnArn(params.getFirst("ResourceName"));
        Map<String, String> tags = service.listTags(arn[5], arn[6], region);
        XmlBuilder xml = new XmlBuilder().start("TagList");
        tags.forEach((key, value) -> xml.start("Tag").elem("Key", key).elem("Value", value).end("Tag"));
        return xml.end("TagList").build();
    }

    private String addTags(MultivaluedMap<String, String> params, String region) {
        String[] arn = requireOwnArn(params.getFirst("ResourceName"));
        service.addTags(arn[5], arn[6], parseTags(params), region);
        return listTags(params, region);
    }

    private String removeTags(MultivaluedMap<String, String> params, String region) {
        String[] arn = requireOwnArn(params.getFirst("ResourceName"));
        List<String> keys = memberList(params, "TagKeys", "member");
        service.removeTags(arn[5], arn[6], keys != null ? keys : List.of(), region);
        return listTags(params, region);
    }

    /** The ARN's components, after checking it names this partition, region and account. */
    private String[] requireOwnArn(String resourceName) {
        String[] arn = splitArn(resourceName);
        if (arn.length != 7 || !"arn".equals(arn[0])) {
            throw new AwsException("InvalidARN", "Input ARN string does not have 7 components.", 400);
        }
        if (!"elasticache".equals(arn[2])) {
            throw new AwsException("InvalidARN", "service field is wrong. Expected value is elasticache", 400);
        }
        if (!regionResolver.getRegion().equals(arn[3])) {
            throw new AwsException("InvalidParameterValue",
                    "Unauthorized call. Please check the region or customer id", 400);
        }
        if (!regionResolver.getAccountId().equals(arn[4])) {
            throw new AwsException("InvalidParameterValue",
                    "The resource ARN does not belong to the caller's account.", 400);
        }
        return arn;
    }

    private static String[] splitArn(String resourceName) {
        return resourceName == null ? new String[0] : resourceName.split(":", -1);
    }

    // ── Request parsing ───────────────────────────────────────────────────────

    private static CreateRequest createRequest(MultivaluedMap<String, String> params) {
        return new CreateRequest(
                params.getFirst("ServerlessCacheName"),
                params.getFirst("Description"),
                params.getFirst("Engine"),
                params.getFirst("MajorEngineVersion"),
                usageLimits(params),
                params.getFirst("KmsKeyId"),
                memberList(params, "SecurityGroupIds", "SecurityGroupId"),
                memberList(params, "SnapshotArnsToRestore", "SnapshotArn"),
                parseTags(params),
                params.getFirst("UserGroupId"),
                memberList(params, "SubnetIds", "SubnetId"),
                intParam(params, "SnapshotRetentionLimit"),
                params.getFirst("DailySnapshotTime"),
                params.getFirst("NetworkType"));
    }

    private static ModifyRequest modifyRequest(MultivaluedMap<String, String> params) {
        String removeUserGroup = params.getFirst("RemoveUserGroup");
        return new ModifyRequest(
                params.getFirst("ServerlessCacheName"),
                params.getFirst("Description"),
                usageLimits(params),
                removeUserGroup == null || removeUserGroup.isBlank() ? null : Boolean.parseBoolean(removeUserGroup.trim()),
                params.getFirst("UserGroupId"),
                memberList(params, "SecurityGroupIds", "SecurityGroupId"),
                intParam(params, "SnapshotRetentionLimit"),
                params.getFirst("DailySnapshotTime"),
                params.getFirst("Engine"),
                params.getFirst("MajorEngineVersion"));
    }

    private static UsageLimits usageLimits(MultivaluedMap<String, String> params) {
        String data = "CacheUsageLimits.DataStorage.";
        String ecpu = "CacheUsageLimits.ECPUPerSecond.";
        boolean dataPresent = hasPrefix(params, data);
        boolean ecpuPresent = hasPrefix(params, ecpu);
        if (!dataPresent && !ecpuPresent) {
            return null;
        }
        return new UsageLimits(dataPresent, intParam(params, data + "Maximum"), intParam(params, data + "Minimum"),
                params.getFirst(data + "Unit"), ecpuPresent, intParam(params, ecpu + "Maximum"),
                intParam(params, ecpu + "Minimum"));
    }

    private static boolean hasPrefix(MultivaluedMap<String, String> params, String prefix) {
        return params.keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }

    private static Integer intParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", "The parameter " + name + " must be an integer.", 400);
        }
    }

    /** A Query-protocol list under both its modeled member name and the generic {@code member}. */
    private static List<String> memberList(MultivaluedMap<String, String> params, String name, String memberName) {
        List<String> values = new ArrayList<>();
        for (String prefix : List.of(name + "." + memberName + ".", name + ".member.")) {
            for (int i = 1; ; i++) {
                String value = params.getFirst(prefix + i);
                if (value == null) {
                    break;
                }
                values.add(value);
            }
            if (!values.isEmpty()) {
                break;
            }
        }
        return values.isEmpty() ? null : values;
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String prefix : List.of("Tags.Tag.", "Tags.member.")) {
            for (int i = 1; ; i++) {
                String key = params.getFirst(prefix + i + ".Key");
                if (key == null) {
                    break;
                }
                String value = params.getFirst(prefix + i + ".Value");
                tags.put(key, value != null ? value : "");
            }
        }
        return tags;
    }

    // ── XML ───────────────────────────────────────────────────────────────────

    static String serverlessCacheXml(ServerlessCache cache) {
        return serverlessCacheXml("ServerlessCache", cache);
    }

    static String serverlessCacheXml(String element, ServerlessCache cache) {
        XmlBuilder xml = new XmlBuilder().start(element)
                .elem("ServerlessCacheName", cache.getServerlessCacheName())
                .elem("Description", cache.getDescription())
                .elem("CreateTime", cache.getCreateTime() != null ? cache.getCreateTime().toString() : null)
                .elem("Status", cache.getStatus())
                .elem("Engine", cache.getEngine())
                .elem("MajorEngineVersion", cache.getMajorEngineVersion())
                .elem("FullEngineVersion", cache.getFullEngineVersion());
        boolean dataStorage = cache.getDataStorageMaximum() != null || cache.getDataStorageMinimum() != null;
        boolean ecpu = cache.getEcpuPerSecondMaximum() != null || cache.getEcpuPerSecondMinimum() != null;
        if (dataStorage || ecpu) {
            xml.start("CacheUsageLimits");
            if (dataStorage) {
                xml.start("DataStorage");
                optionalInt(xml, "Maximum", cache.getDataStorageMaximum());
                optionalInt(xml, "Minimum", cache.getDataStorageMinimum());
                xml.elem("Unit", cache.getDataStorageUnit() != null ? cache.getDataStorageUnit() : "GB")
                        .end("DataStorage");
            }
            if (ecpu) {
                xml.start("ECPUPerSecond");
                optionalInt(xml, "Maximum", cache.getEcpuPerSecondMaximum());
                optionalInt(xml, "Minimum", cache.getEcpuPerSecondMinimum());
                xml.end("ECPUPerSecond");
            }
            xml.end("CacheUsageLimits");
        }
        xml.elem("KmsKeyId", cache.getKmsKeyId())
                .elem("StorageEncryptionType", cache.getKmsKeyId() != null ? "sse-kms" : "sse-elasticache");
        xml.start("SecurityGroupIds");
        cache.getSecurityGroupIds().forEach(id -> xml.elem("SecurityGroupId", id));
        xml.end("SecurityGroupIds");
        endpoint(xml, "Endpoint", cache.getEndpoint());
        endpoint(xml, "ReaderEndpoint", cache.getReaderEndpoint());
        xml.elem("ARN", cache.getArn())
                .elem("UserGroupId", cache.getUserGroupId());
        xml.start("SubnetIds");
        cache.getSubnetIds().forEach(id -> xml.elem("SubnetId", id));
        xml.end("SubnetIds");
        xml.elem("SnapshotRetentionLimit", (long) cache.getSnapshotRetentionLimit())
                .elem("DailySnapshotTime", cache.getDailySnapshotTime())
                .elem("NetworkType", cache.getNetworkType());
        return xml.end(element).build();
    }

    static String snapshotXml(ServerlessCacheSnapshot snapshot) {
        return new XmlBuilder().start("ServerlessCacheSnapshot")
                .elem("ServerlessCacheSnapshotName", snapshot.getServerlessCacheSnapshotName())
                .elem("ARN", snapshot.getArn())
                .elem("KmsKeyId", snapshot.getKmsKeyId())
                .elem("SnapshotType", snapshot.getSnapshotType())
                .elem("Status", snapshot.getStatus())
                .elem("CreateTime", snapshot.getCreateTime() != null ? snapshot.getCreateTime().toString() : null)
                .elem("ExpiryTime", snapshot.getExpiryTime() != null ? snapshot.getExpiryTime().toString() : null)
                .elem("BytesUsedForCache", String.valueOf(snapshot.getBytesUsedForCache()))
                .start("ServerlessCacheConfiguration")
                .elem("ServerlessCacheName", snapshot.getServerlessCacheName())
                .elem("Engine", snapshot.getEngine())
                .elem("MajorEngineVersion", snapshot.getMajorEngineVersion())
                .end("ServerlessCacheConfiguration")
                .end("ServerlessCacheSnapshot")
                .build();
    }

    private static void endpoint(XmlBuilder xml, String element, Endpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        xml.start(element).elem("Address", endpoint.address()).elem("Port", (long) endpoint.port()).end(element);
    }

    private static void optionalInt(XmlBuilder xml, String element, Integer value) {
        if (value != null) {
            xml.elem(element, (long) value);
        }
    }
}
