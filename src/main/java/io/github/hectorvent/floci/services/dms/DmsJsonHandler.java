package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.dms.model.DmsConnection;
import io.github.hectorvent.floci.services.dms.model.DmsEndpoint;
import io.github.hectorvent.floci.services.dms.model.DmsEvent;
import io.github.hectorvent.floci.services.dms.model.ReplicationInstance;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.dms.model.ResourceTag;
import io.github.hectorvent.floci.services.dms.model.SchemaRefresh;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Set;
import java.util.function.Function;

@ApplicationScoped
public class DmsJsonHandler {

    /** Secret members of the engine settings structures; DMS never returns them. */
    private static final Set<String> SENSITIVE_SETTINGS = Set.of(
            "Password", "AsmPassword", "SecurityDbEncryption", "SaslPassword", "SslClientKeyPassword",
            "AuthPassword");

    private final DmsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public DmsJsonHandler(DmsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateReplicationSubnetGroup" -> single("ReplicationSubnetGroup",
                    subnetGroup(service.createReplicationSubnetGroup(request, region)));
            case "ModifyReplicationSubnetGroup" -> single("ReplicationSubnetGroup",
                    subnetGroup(service.modifyReplicationSubnetGroup(request, region)));
            case "DescribeReplicationSubnetGroups" -> page("ReplicationSubnetGroups",
                    service.describeReplicationSubnetGroups(request, region), this::subnetGroup);
            case "DeleteReplicationSubnetGroup" -> {
                service.deleteReplicationSubnetGroup(request, region);
                yield empty();
            }
            case "CreateEndpoint" -> single("Endpoint", endpoint(service.createEndpoint(request, region)));
            case "ModifyEndpoint" -> single("Endpoint", endpoint(service.modifyEndpoint(request, region)));
            case "DeleteEndpoint" -> single("Endpoint", endpoint(service.deleteEndpoint(request, region)));
            case "DescribeEndpoints" -> page("Endpoints", service.describeEndpoints(request, region), this::endpoint);
            case "DescribeEndpointSettings" -> page("EndpointSettings",
                    service.describeEndpointSettings(request), this::endpointSetting);
            case "CreateReplicationInstance" -> single("ReplicationInstance",
                    instance(service.createReplicationInstance(request, region)));
            case "ModifyReplicationInstance" -> single("ReplicationInstance",
                    instance(service.modifyReplicationInstance(request, region)));
            case "RebootReplicationInstance" -> single("ReplicationInstance",
                    instance(service.rebootReplicationInstance(request, region)));
            case "DeleteReplicationInstance" -> single("ReplicationInstance",
                    instance(service.deleteReplicationInstance(request, region)));
            case "DescribeReplicationInstances" -> page("ReplicationInstances",
                    service.describeReplicationInstances(request, region), this::instance);
            case "DescribeReplicationInstanceTaskLogs" -> {
                ReplicationInstance instance = service.describeReplicationInstanceTaskLogs(request, region);
                ObjectNode response = objectMapper.createObjectNode();
                response.put("ReplicationInstanceArn", instance.getReplicationInstanceArn());
                response.putArray("ReplicationInstanceTaskLogs");
                yield Response.ok(response).build();
            }
            case "DescribeOrderableReplicationInstances" -> page("OrderableReplicationInstances",
                    service.describeOrderableReplicationInstances(request, region), this::orderable);
            case "TestConnection" -> single("Connection", connection(service.testConnection(request, region)));
            case "DescribeConnections" -> page("Connections",
                    service.describeConnections(request, region), this::connection);
            case "RefreshSchemas" -> single("RefreshSchemasStatus",
                    refreshStatus(service.refreshSchemas(request, region)));
            case "DescribeRefreshSchemasStatus" -> single("RefreshSchemasStatus",
                    refreshStatus(service.describeRefreshSchemasStatus(request, region)));
            case "DescribeSchemas" -> page("Schemas", service.describeSchemas(request, region),
                    schema -> objectMapper.getNodeFactory().textNode(schema));
            case "DescribeReplicationTasks" -> page("ReplicationTasks",
                    service.describeReplicationTasks(request), ignored -> objectMapper.createObjectNode());
            case "StartReplicationTask" -> {
                service.startReplicationTask(request);
                yield empty();
            }
            case "StopReplicationTask" -> {
                service.stopReplicationTask(request);
                yield empty();
            }
            case "DescribeTableStatistics" -> {
                service.describeTableStatistics(request);
                yield empty();
            }
            case "ReloadTables" -> {
                service.reloadTables(request);
                yield empty();
            }
            case "DescribeReplications" -> page("Replications",
                    service.describeReplications(request), ignored -> objectMapper.createObjectNode());
            case "StartReplication" -> {
                service.startReplication(request);
                yield empty();
            }
            case "StopReplication" -> {
                service.stopReplication(request);
                yield empty();
            }
            case "DescribeEvents" -> page("Events", service.describeEvents(request, region), this::event);
            case "ListTagsForResource" -> {
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode tagList = response.putArray("TagList");
                service.listTagsForResource(request, region).forEach(tag -> tagList.add(tagNode(tag)));
                yield Response.ok(response).build();
            }
            case "AddTagsToResource" -> {
                service.addTagsToResource(request, region);
                yield empty();
            }
            case "RemoveTagsFromResource" -> {
                service.removeTagsFromResource(request, region);
                yield empty();
            }
            default -> null;
        };
    }

    private Response empty() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response single(String member, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(member, value);
        return Response.ok(response).build();
    }

    /**
     * Marker is present only while another page remains: an SDK paginator treats any Marker at all
     * as "ask again", so emitting one on the final page loops forever.
     */
    private <T> Response page(String member, PaginatedResult<T> page, Function<T, JsonNode> render) {
        ObjectNode response = objectMapper.createObjectNode();
        if (page.nextToken() != null) {
            response.put("Marker", page.nextToken());
        }
        ArrayNode items = response.putArray(member);
        page.items().forEach(item -> items.add(render.apply(item)));
        return Response.ok(response).build();
    }

    private ObjectNode tagNode(ResourceTag tag) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Key", tag.key());
        node.put("Value", tag.value());
        if (tag.resourceArn() != null) {
            node.put("ResourceArn", tag.resourceArn());
        }
        return node;
    }

    private ObjectNode subnetGroup(ReplicationSubnetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ReplicationSubnetGroupIdentifier", group.getReplicationSubnetGroupIdentifier());
        node.put("ReplicationSubnetGroupDescription", group.getReplicationSubnetGroupDescription());
        node.put("VpcId", group.getVpcId());
        node.put("SubnetGroupStatus", group.getSubnetGroupStatus());
        ArrayNode subnets = node.putArray("Subnets");
        group.getSubnetIds().forEach(subnetId -> {
            ObjectNode subnet = subnets.addObject();
            subnet.put("SubnetIdentifier", subnetId);
            subnet.putObject("SubnetAvailabilityZone")
                    .put("Name", group.getSubnetAvailabilityZones().get(subnetId));
            subnet.put("SubnetStatus", "Active");
        });
        ArrayNode networkTypes = node.putArray("SupportedNetworkTypes");
        group.getSupportedNetworkTypes().forEach(networkTypes::add);
        // Always false: a read-only group is one DMS manages for a zero-ETL integration, which
        // Floci does not emulate, so every group here is caller-owned and modifiable.
        node.put("IsReadOnly", false);
        return node;
    }

    private ObjectNode endpoint(DmsEndpoint endpoint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EndpointIdentifier", endpoint.getEndpointIdentifier());
        node.put("EndpointType", endpoint.getEndpointType());
        node.put("EngineName", endpoint.getEngineName());
        putIfPresent(node, "Username", endpoint.getUsername());
        putIfPresent(node, "ServerName", endpoint.getServerName());
        if (endpoint.getPort() != null) {
            node.put("Port", endpoint.getPort());
        }
        putIfPresent(node, "DatabaseName", endpoint.getDatabaseName());
        putIfPresent(node, "ExtraConnectionAttributes", endpoint.getExtraConnectionAttributes());
        node.put("Status", endpoint.getStatus());
        putIfPresent(node, "KmsKeyId", endpoint.getKmsKeyId());
        node.put("EndpointArn", endpoint.getEndpointArn());
        putIfPresent(node, "CertificateArn", endpoint.getCertificateArn());
        putIfPresent(node, "SslMode", endpoint.getSslMode());
        putIfPresent(node, "ServiceAccessRoleArn", endpoint.getServiceAccessRoleArn());
        putIfPresent(node, "ExternalTableDefinition", endpoint.getExternalTableDefinition());
        node.put("IsReadOnly", false);
        endpoint.getSettings().forEach((member, settings) -> {
            JsonNode rendered = settings.deepCopy();
            if (rendered instanceof ObjectNode object) {
                object.remove(SENSITIVE_SETTINGS);
            }
            node.set(member, rendered);
        });
        return node;
    }

    private ObjectNode instance(ReplicationInstance instance) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ReplicationInstanceIdentifier", instance.getReplicationInstanceIdentifier());
        node.put("ReplicationInstanceClass", instance.getReplicationInstanceClass());
        node.put("ReplicationInstanceStatus", instance.getStatus());
        node.put("AllocatedStorage", instance.getAllocatedStorage());
        node.put("InstanceCreateTime", epochSeconds(instance.getInstanceCreateTimeMillis()));
        ArrayNode securityGroups = node.putArray("VpcSecurityGroups");
        instance.getVpcSecurityGroupIds().forEach(groupId -> securityGroups.addObject()
                .put("VpcSecurityGroupId", groupId)
                .put("Status", "active"));
        putIfPresent(node, "AvailabilityZone", instance.getAvailabilityZone());
        if (instance.getSubnetGroup() != null) {
            node.set("ReplicationSubnetGroup", subnetGroup(instance.getSubnetGroup()));
        }
        putIfPresent(node, "PreferredMaintenanceWindow", instance.getPreferredMaintenanceWindow());
        ObjectNode pending = node.putObject("PendingModifiedValues");
        putIfPresent(pending, "ReplicationInstanceClass", instance.getPendingReplicationInstanceClass());
        if (instance.getPendingAllocatedStorage() != null) {
            pending.put("AllocatedStorage", instance.getPendingAllocatedStorage());
        }
        if (instance.getPendingMultiAZ() != null) {
            pending.put("MultiAZ", instance.getPendingMultiAZ());
        }
        putIfPresent(pending, "EngineVersion", instance.getPendingEngineVersion());
        putIfPresent(pending, "NetworkType", instance.getPendingNetworkType());
        node.put("MultiAZ", instance.isMultiAZ());
        node.put("EngineVersion", instance.getEngineVersion());
        node.put("AutoMinorVersionUpgrade", instance.isAutoMinorVersionUpgrade());
        putIfPresent(node, "KmsKeyId", instance.getKmsKeyId());
        node.put("ReplicationInstanceArn", instance.getReplicationInstanceArn());
        // No network interface backs a Floci replication instance, so it has no addresses.
        node.putArray("ReplicationInstancePublicIpAddresses");
        node.putArray("ReplicationInstancePrivateIpAddresses");
        node.putArray("ReplicationInstanceIpv6Addresses");
        node.put("PubliclyAccessible", instance.isPubliclyAccessible());
        putIfPresent(node, "SecondaryAvailabilityZone", instance.getSecondaryAvailabilityZone());
        putIfPresent(node, "DnsNameServers", instance.getDnsNameServers());
        putIfPresent(node, "NetworkType", instance.getNetworkType());
        return node;
    }

    private ObjectNode connection(DmsConnection connection) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ReplicationInstanceArn", connection.getReplicationInstanceArn());
        node.put("EndpointArn", connection.getEndpointArn());
        node.put("Status", connection.getStatus());
        putIfPresent(node, "LastFailureMessage", connection.getLastFailureMessage());
        node.put("EndpointIdentifier", connection.getEndpointIdentifier());
        node.put("ReplicationInstanceIdentifier", connection.getReplicationInstanceIdentifier());
        return node;
    }

    private ObjectNode refreshStatus(SchemaRefresh refresh) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EndpointArn", refresh.getEndpointArn());
        node.put("ReplicationInstanceArn", refresh.getReplicationInstanceArn());
        node.put("Status", refresh.getStatus());
        if (refresh.getLastRefreshDateMillis() != null) {
            node.put("LastRefreshDate", epochSeconds(refresh.getLastRefreshDateMillis()));
        }
        putIfPresent(node, "LastFailureMessage", refresh.getLastFailureMessage());
        return node;
    }

    private ObjectNode event(DmsEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SourceIdentifier", event.sourceIdentifier());
        node.put("SourceType", event.sourceType());
        node.put("Message", event.message());
        ArrayNode categories = node.putArray("EventCategories");
        event.eventCategories().forEach(categories::add);
        node.put("Date", epochSeconds(event.dateMillis()));
        return node;
    }

    private ObjectNode endpointSetting(DmsCatalog.EndpointSetting setting) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", setting.name());
        node.put("Type", setting.type());
        if (setting.enumValues() != null) {
            ArrayNode values = node.putArray("EnumValues");
            setting.enumValues().forEach(values::add);
        }
        node.put("Sensitive", setting.sensitive());
        putIfPresent(node, "Units", setting.units());
        putIfPresent(node, "Applicability", setting.applicability());
        if (setting.intValueMin() != null) {
            node.put("IntValueMin", setting.intValueMin());
        }
        if (setting.intValueMax() != null) {
            node.put("IntValueMax", setting.intValueMax());
        }
        putIfPresent(node, "DefaultValue", setting.defaultValue());
        return node;
    }

    private ObjectNode orderable(DmsService.OrderableInstance entry) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EngineVersion", entry.engineVersion());
        node.put("ReplicationInstanceClass", entry.instanceClass());
        node.put("StorageType", DmsCatalog.STORAGE_TYPE);
        node.put("MinAllocatedStorage", DmsCatalog.MIN_ALLOCATED_STORAGE);
        node.put("MaxAllocatedStorage", DmsCatalog.MAX_ALLOCATED_STORAGE);
        node.put("DefaultAllocatedStorage", entry.includedStorage());
        node.put("IncludedAllocatedStorage", entry.includedStorage());
        ArrayNode zones = node.putArray("AvailabilityZones");
        entry.availabilityZones().forEach(zones::add);
        node.put("ReleaseStatus", "prod");
        return node;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static double epochSeconds(long millis) {
        return millis / 1000.0;
    }
}
