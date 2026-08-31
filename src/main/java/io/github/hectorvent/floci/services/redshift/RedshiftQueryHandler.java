package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.model.ClusterCredentials;
import io.github.hectorvent.floci.services.redshift.model.ClusterEvent;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSnapshot;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.RedshiftCluster;
import io.github.hectorvent.floci.services.redshift.model.RedshiftClusterSubnetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class RedshiftQueryHandler {

    private static final Logger LOG = Logger.getLogger(RedshiftQueryHandler.class);
    private static final String NS = AwsNamespaces.REDSHIFT;

    private final RedshiftService service;
    private final AccountResolver accountResolver;
    private final IamService iamService;

    @Inject
    public RedshiftQueryHandler(RedshiftService service, AccountResolver accountResolver, IamService iamService) {
        this.service = service;
        this.accountResolver = accountResolver;
        this.iamService = iamService;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        return handle(action, params, null);
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String authorization) {
        LOG.infov("Redshift action: {0}", action);
        try {
            return switch (action) {
                case "CreateCluster" -> handleCreateCluster(params);
                case "DescribeClusters" -> handleDescribeClusters(params);
                case "ModifyCluster" -> handleModifyCluster(params);
                case "DeleteCluster" -> handleDeleteCluster(params);
                case "CreateClusterSubnetGroup" -> handleCreateSubnetGroup(params);
                case "DescribeClusterSubnetGroups" -> handleDescribeSubnetGroups(params);
                case "ModifyClusterSubnetGroup" -> handleModifySubnetGroup(params);
                case "DeleteClusterSubnetGroup" -> handleDeleteSubnetGroup(params);
                case "CreateClusterSnapshot" -> handleCreateClusterSnapshot(params);
                case "DescribeClusterSnapshots" -> handleDescribeClusterSnapshots(params);
                case "DeleteClusterSnapshot" -> handleDeleteClusterSnapshot(params);
                case "CopyClusterSnapshot" -> handleCopyClusterSnapshot(params);
                case "DescribeEvents" -> handleDescribeEvents(params);
                case "CreateTags" -> handleCreateTags(params);
                case "DeleteTags" -> handleDeleteTags(params);
                case "CreateClusterParameterGroup" -> handleCreateClusterParameterGroup(params);
                case "DescribeClusterParameterGroups" -> handleDescribeClusterParameterGroups(params);
                case "DeleteClusterParameterGroup" -> handleDeleteClusterParameterGroup(params);
                case "ModifyClusterParameterGroup" -> handleModifyClusterParameterGroup(params);
                case "ResetClusterParameterGroup" -> handleResetClusterParameterGroup(params);
                case "DescribeClusterParameters" -> handleDescribeClusterParameters(params);
                case "GetClusterCredentials" -> handleGetClusterCredentials(params);
                case "GetClusterCredentialsWithIAM" -> handleGetClusterCredentialsWithIAM(params, authorization);
                case "CreateEventSubscription" -> handleCreateEventSubscription(params);
                case "DescribeEventSubscriptions" -> handleDescribeEventSubscriptions(params);
                case "ModifyEventSubscription" -> handleModifyEventSubscription(params);
                case "DeleteEventSubscription" -> handleDeleteEventSubscription(params);
                default -> AwsQueryResponse.error("UnsupportedOperation",
                        "Operation " + action + " is not supported by Redshift.", NS, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), NS, e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in Redshift {0}", action);
            return AwsQueryResponse.error("InternalFailure",
                    "An internal error occurred while processing the request.", NS, 500);
        }
    }

    private Response handleCreateCluster(MultivaluedMap<String, String> params) {
        RedshiftCluster cluster = service.createCluster(
                params.getFirst("ClusterIdentifier"),
                params.getFirst("NodeType"),
                params.getFirst("ClusterType"),
                parseInt(params.getFirst("NumberOfNodes")),
                params.getFirst("MasterUsername"),
                params.getFirst("MasterUserPassword"),
                Boolean.parseBoolean(params.getFirst("ManageMasterPassword")),
                params.getFirst("DBName"),
                parseInt(params.getFirst("Port")),
                params.getFirst("AvailabilityZone"),
                params.getFirst("ClusterSubnetGroupName"),
                params.getFirst("ClusterParameterGroupName"),
                extractMembers(params, "VpcSecurityGroupIds.member."),
                extractMembers(params, "IamRoles.member."),
                parseBoolean(params.getFirst("PubliclyAccessible")),
                parseBoolean(params.getFirst("Encrypted")),
                params.getFirst("KmsKeyId"),
                params.getFirst("PreferredMaintenanceWindow"),
                parseInt(params.getFirst("AutomatedSnapshotRetentionPeriod")),
                parseBoolean(params.getFirst("AllowVersionUpgrade")),
                parseBoolean(params.getFirst("EnhancedVpcRouting")),
                extractTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateCluster", NS, clusterXml(cluster))).build();
    }

    private Response handleDescribeClusters(MultivaluedMap<String, String> params) {
        Collection<RedshiftCluster> listed = service.listClusters(params.getFirst("ClusterIdentifier"));
        XmlBuilder xml = new XmlBuilder().start("Clusters");
        for (RedshiftCluster cluster : listed) {
            xml.start("Cluster").raw(clusterInnerXml(cluster)).end("Cluster");
        }
        xml.end("Clusters");
        return Response.ok(AwsQueryResponse.envelope("DescribeClusters", NS, xml.build())).build();
    }

    private Response handleModifyCluster(MultivaluedMap<String, String> params) {
        RedshiftCluster cluster = service.modifyCluster(
                params.getFirst("ClusterIdentifier"),
                params.getFirst("NodeType"),
                parseInt(params.getFirst("NumberOfNodes")),
                params.getFirst("ClusterSubnetGroupName"),
                params.getFirst("ClusterParameterGroupName"),
                emptyToNull(extractMembers(params, "VpcSecurityGroupIds.member.")),
                parseBoolean(params.getFirst("PubliclyAccessible")),
                parseBoolean(params.getFirst("Encrypted")),
                params.getFirst("KmsKeyId"),
                params.getFirst("PreferredMaintenanceWindow"),
                parseInt(params.getFirst("AutomatedSnapshotRetentionPeriod")),
                parseBoolean(params.getFirst("AllowVersionUpgrade")),
                parseBoolean(params.getFirst("EnhancedVpcRouting")),
                emptyToNull(extractMembers(params, "IamRoles.member.")));
        return Response.ok(AwsQueryResponse.envelope("ModifyCluster", NS, clusterXml(cluster))).build();
    }

    private Response handleDeleteCluster(MultivaluedMap<String, String> params) {
        RedshiftCluster cluster = service.deleteCluster(
                params.getFirst("ClusterIdentifier"),
                Boolean.parseBoolean(params.getFirst("SkipFinalClusterSnapshot")),
                params.getFirst("FinalClusterSnapshotIdentifier"));
        return Response.ok(AwsQueryResponse.envelope("DeleteCluster", NS, clusterXml(cluster))).build();
    }

    private Response handleCreateSubnetGroup(MultivaluedMap<String, String> params) {
        RedshiftClusterSubnetGroup group = service.createSubnetGroup(
                params.getFirst("ClusterSubnetGroupName"),
                params.getFirst("Description"),
                extractMembers(params, "SubnetIds.member."),
                extractTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateClusterSubnetGroup", NS,
                subnetGroupXml(group))).build();
    }

    private Response handleDescribeSubnetGroups(MultivaluedMap<String, String> params) {
        Collection<RedshiftClusterSubnetGroup> listed =
                service.listSubnetGroups(params.getFirst("ClusterSubnetGroupName"));
        XmlBuilder xml = new XmlBuilder().start("ClusterSubnetGroups");
        for (RedshiftClusterSubnetGroup group : listed) {
            xml.start("ClusterSubnetGroup").raw(subnetGroupInnerXml(group)).end("ClusterSubnetGroup");
        }
        xml.end("ClusterSubnetGroups");
        return Response.ok(AwsQueryResponse.envelope("DescribeClusterSubnetGroups", NS, xml.build())).build();
    }

    private Response handleModifySubnetGroup(MultivaluedMap<String, String> params) {
        RedshiftClusterSubnetGroup group = service.modifySubnetGroup(
                params.getFirst("ClusterSubnetGroupName"),
                params.getFirst("Description"),
                extractMembers(params, "SubnetIds.member."));
        return Response.ok(AwsQueryResponse.envelope("ModifyClusterSubnetGroup", NS,
                subnetGroupXml(group))).build();
    }

    private Response handleDeleteSubnetGroup(MultivaluedMap<String, String> params) {
        service.deleteSubnetGroup(params.getFirst("ClusterSubnetGroupName"));
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteClusterSubnetGroup", NS)).build();
    }

    private Response handleCreateClusterSnapshot(MultivaluedMap<String, String> params) {
        ClusterSnapshot snapshot = service.createSnapshot(
                params.getFirst("ClusterIdentifier"),
                params.getFirst("SnapshotIdentifier"),
                "manual");
        return Response.ok(AwsQueryResponse.envelope("CreateClusterSnapshot", NS,
                snapshotXml(snapshot))).build();
    }

    private Response handleDescribeClusterSnapshots(MultivaluedMap<String, String> params) {
        Collection<ClusterSnapshot> listed = service.listSnapshots(
                params.getFirst("ClusterIdentifier"),
                params.getFirst("SnapshotIdentifier"),
                params.getFirst("SnapshotType"));
        XmlBuilder xml = new XmlBuilder().start("Snapshots");
        for (ClusterSnapshot snapshot : listed) {
            xml.start("Snapshot").raw(snapshotInnerXml(snapshot)).end("Snapshot");
        }
        xml.end("Snapshots");
        return Response.ok(AwsQueryResponse.envelope("DescribeClusterSnapshots", NS, xml.build())).build();
    }

    private Response handleDeleteClusterSnapshot(MultivaluedMap<String, String> params) {
        String snapshotIdentifier = params.getFirst("SnapshotIdentifier");
        if (snapshotIdentifier == null || snapshotIdentifier.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "SnapshotIdentifier is required.", NS, 400);
        }
        ClusterSnapshot snapshot = service.deleteSnapshot(snapshotIdentifier);
        return Response.ok(AwsQueryResponse.envelope("DeleteClusterSnapshot", NS,
                snapshotXml(snapshot))).build();
    }

    private Response handleCopyClusterSnapshot(MultivaluedMap<String, String> params) {
        String source = params.getFirst("SourceSnapshotIdentifier");
        if (source == null || source.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "SourceSnapshotIdentifier is required.", NS, 400);
        }
        ClusterSnapshot snapshot = service.copySnapshot(source, params.getFirst("TargetSnapshotIdentifier"));
        return Response.ok(AwsQueryResponse.envelope("CopyClusterSnapshot", NS,
                snapshotXml(snapshot))).build();
    }

    private Response handleDescribeEvents(MultivaluedMap<String, String> params) {
        Collection<ClusterEvent> listed = service.listEvents();
        XmlBuilder xml = new XmlBuilder().start("Events");
        for (ClusterEvent event : listed) {
            xml.start("Event")
                    .elem("SourceIdentifier", event.getSourceIdentifier())
                    .elem("SourceType", event.getSourceType())
                    .elem("Message", event.getMessage())
                    .elem("Severity", event.getSeverity())
                    .elem("EventId", event.getEventId())
                    .elem("Date", event.getDate() != null ? event.getDate().toString() : null)
                    .end("Event");
        }
        xml.end("Events");
        return Response.ok(AwsQueryResponse.envelope("DescribeEvents", NS, xml.build())).build();
    }

    private Response handleCreateEventSubscription(MultivaluedMap<String, String> params) {
        EventSubscription subscription = service.createEventSubscription(
                params.getFirst("SubscriptionName"),
                params.getFirst("SnsTopicArn"),
                params.getFirst("SourceType"),
                extractMembers(params, "SourceIds.member."),
                extractMembers(params, "EventCategories.member."),
                params.getFirst("Severity"),
                parseBoolean(params.getFirst("Enabled")),
                extractTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateEventSubscription", NS,
                eventSubscriptionXml(subscription))).build();
    }

    private Response handleDescribeEventSubscriptions(MultivaluedMap<String, String> params) {
        Collection<EventSubscription> listed =
                service.listEventSubscriptions(params.getFirst("SubscriptionName"));
        XmlBuilder xml = new XmlBuilder().start("EventSubscriptionsList");
        for (EventSubscription subscription : listed) {
            xml.start("EventSubscription").raw(eventSubscriptionInnerXml(subscription)).end("EventSubscription");
        }
        xml.end("EventSubscriptionsList");
        return Response.ok(AwsQueryResponse.envelope("DescribeEventSubscriptions", NS, xml.build())).build();
    }

    private Response handleModifyEventSubscription(MultivaluedMap<String, String> params) {
        EventSubscription subscription = service.modifyEventSubscription(
                params.getFirst("SubscriptionName"),
                params.getFirst("SnsTopicArn"),
                params.getFirst("SourceType"),
                hasMemberParam(params, "SourceIds") ? extractMembers(params, "SourceIds.member.") : null,
                hasMemberParam(params, "EventCategories")
                        ? extractMembers(params, "EventCategories.member.") : null,
                params.getFirst("Severity"),
                parseBoolean(params.getFirst("Enabled")));
        return Response.ok(AwsQueryResponse.envelope("ModifyEventSubscription", NS,
                eventSubscriptionXml(subscription))).build();
    }

    private Response handleDeleteEventSubscription(MultivaluedMap<String, String> params) {
        service.deleteEventSubscription(params.getFirst("SubscriptionName"));
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteEventSubscription", NS)).build();
    }

    private Response handleCreateTags(MultivaluedMap<String, String> params) {
        service.createTags(params.getFirst("ResourceName"), extractTags(params));
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("CreateTags", NS)).build();
    }

    private Response handleDeleteTags(MultivaluedMap<String, String> params) {
        List<String> keys = extractMembers(params, "TagKeys.member.");
        if (keys.isEmpty()) {
            keys = extractMembers(params, "TagKeys.TagKey.");
        }
        if (keys.isEmpty()) {
            keys = extractMembers(params, "TagKeys.");
        }
        service.deleteTags(params.getFirst("ResourceName"), keys);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteTags", NS)).build();
    }

    private Response handleGetClusterCredentials(MultivaluedMap<String, String> params) {
        ClusterCredentials credentials = service.getClusterCredentials(
                params.getFirst("ClusterIdentifier"),
                params.getFirst("DbUser"),
                Boolean.parseBoolean(params.getFirst("AutoCreate")),
                parseInt(params.getFirst("DurationSeconds")));
        return Response.ok(AwsQueryResponse.envelope("GetClusterCredentials", NS,
                credentialsXml(credentials, false))).build();
    }

    private Response handleGetClusterCredentialsWithIAM(MultivaluedMap<String, String> params,
                                                        String authorization) {
        ClusterCredentials credentials = service.getClusterCredentialsWithIAM(
                params.getFirst("ClusterIdentifier"),
                iamMappedDbUser(authorization),
                parseInt(params.getFirst("DurationSeconds")));
        return Response.ok(AwsQueryResponse.envelope("GetClusterCredentialsWithIAM", NS,
                credentialsXml(credentials, true))).build();
    }

    private String iamMappedDbUser(String authorization) {
        String accessKeyId = accountResolver.extractAccessKeyId(authorization);
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return "IAM:root";
        }
        return iamService.findUserNameByAccessKeyId(accessKeyId)
                .map(user -> "IAM:" + user)
                .orElseGet(() -> iamService.isAssumedRoleSession(accessKeyId)
                        ? "IAMR:assumed-role"
                        : "IAM:root");
    }

    private static String credentialsXml(ClusterCredentials credentials, boolean includeNextRefresh) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DbUser", credentials.dbUser())
                .elem("DbPassword", credentials.dbPassword())
                .elem("Expiration", formatInstant(credentials.expiration()));
        if (includeNextRefresh) {
            xml.elem("NextRefreshTime", formatInstant(credentials.nextRefreshTime()));
        }
        return xml.build();
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private Response handleCreateClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("ParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ParameterGroupName is required.", NS, 400);
        }
        ClusterParameterGroup group = service.createClusterParameterGroup(
                name,
                params.getFirst("ParameterGroupFamily"),
                params.getFirst("Description"),
                extractTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateClusterParameterGroup", NS,
                parameterGroupXml(group))).build();
    }

    private Response handleDescribeClusterParameterGroups(MultivaluedMap<String, String> params) {
        Collection<ClusterParameterGroup> listed =
                service.listClusterParameterGroups(params.getFirst("ParameterGroupName"));
        XmlBuilder xml = new XmlBuilder().start("ParameterGroups");
        for (ClusterParameterGroup group : listed) {
            xml.start("ClusterParameterGroup").raw(parameterGroupInnerXml(group)).end("ClusterParameterGroup");
        }
        xml.end("ParameterGroups");
        return Response.ok(AwsQueryResponse.envelope("DescribeClusterParameterGroups", NS, xml.build())).build();
    }

    private Response handleDeleteClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("ParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ParameterGroupName is required.", NS, 400);
        }
        service.deleteClusterParameterGroup(name);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteClusterParameterGroup", NS)).build();
    }

    private Response handleModifyClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("ParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ParameterGroupName is required.", NS, 400);
        }
        ClusterParameterGroup group = service.modifyClusterParameterGroup(name, parseParameters(params));
        String result = new XmlBuilder()
                .elem("ParameterGroupName", group.getParameterGroupName())
                .elem("ParameterGroupStatus",
                        "Your parameter group has been updated but changes won't take effect until you reboot associated clusters")
                .build();
        return Response.ok(AwsQueryResponse.envelope("ModifyClusterParameterGroup", NS, result)).build();
    }

    private Response handleResetClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("ParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ParameterGroupName is required.", NS, 400);
        }
        ClusterParameterGroup group = service.resetClusterParameterGroup(
                name,
                Boolean.parseBoolean(params.getFirst("ResetAllParameters")),
                parameterNames(params));
        String result = new XmlBuilder()
                .elem("ParameterGroupName", group.getParameterGroupName())
                .elem("ParameterGroupStatus",
                        "Your parameter group has been updated but changes won't take effect until you reboot associated clusters")
                .build();
        return Response.ok(AwsQueryResponse.envelope("ResetClusterParameterGroup", NS, result)).build();
    }

    private Response handleDescribeClusterParameters(MultivaluedMap<String, String> params) {
        String name = params.getFirst("ParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ParameterGroupName is required.", NS, 400);
        }
        ClusterParameterGroup group = service.getClusterParameterGroup(name);
        String source = params.getFirst("Source");
        XmlBuilder xml = new XmlBuilder().start("Parameters");
        for (Map.Entry<String, String> entry : group.getParameters().entrySet()) {
            if (source != null && !source.isBlank() && !"user".equalsIgnoreCase(source)
                    && !"all".equalsIgnoreCase(source)) {
                continue;
            }
            xml.start("Parameter")
                    .elem("ParameterName", entry.getKey())
                    .elem("ParameterValue", entry.getValue())
                    .elem("Source", "user")
                    .elem("DataType", "string")
                    .elem("ApplyType", "dynamic")
                    .elem("IsModifiable", true)
                    .end("Parameter");
        }
        xml.end("Parameters");
        return Response.ok(AwsQueryResponse.envelope("DescribeClusterParameters", NS, xml.build())).build();
    }

    private static String clusterXml(RedshiftCluster cluster) {
        return new XmlBuilder().start("Cluster").raw(clusterInnerXml(cluster)).end("Cluster").build();
    }

    private static String clusterInnerXml(RedshiftCluster cluster) {
        XmlBuilder xml = new XmlBuilder()
                .elem("ClusterIdentifier", cluster.getClusterIdentifier())
                .elem("NodeType", cluster.getNodeType())
                .elem("ClusterStatus", cluster.getClusterStatus())
                .elem("ClusterAvailabilityStatus", cluster.getClusterAvailabilityStatus())
                .elem("MasterUsername", cluster.getMasterUsername())
                .elem("DBName", cluster.getDbName())
                .elem("AvailabilityZone", cluster.getAvailabilityZone())
                .elem("ClusterSubnetGroupName", cluster.getClusterSubnetGroupName())
                .elem("VpcId", cluster.getVpcId())
                .elem("ClusterVersion", cluster.getClusterVersion())
                .elem("NumberOfNodes", cluster.getNumberOfNodes())
                .elem("PubliclyAccessible", cluster.isPubliclyAccessible())
                .elem("Encrypted", cluster.isEncrypted())
                .elem("KmsKeyId", cluster.getKmsKeyId())
                .elem("AllowVersionUpgrade", cluster.isAllowVersionUpgrade())
                .elem("EnhancedVpcRouting", cluster.isEnhancedVpcRouting())
                .elem("AutomatedSnapshotRetentionPeriod", cluster.getAutomatedSnapshotRetentionPeriod())
                .elem("PreferredMaintenanceWindow", cluster.getPreferredMaintenanceWindow())
                .elem("ClusterNamespaceArn", cluster.getClusterNamespaceArn())
                .elem("MasterPasswordSecretArn", cluster.getMasterPasswordSecretArn());
        if (cluster.getClusterCreateTime() != null) {
            xml.elem("ClusterCreateTime", cluster.getClusterCreateTime().toString());
        }
        xml.start("Endpoint")
                .elem("Address", cluster.getEndpointAddress())
                .elem("Port", cluster.getEndpointPort())
                .end("Endpoint");
        if (cluster.getClusterParameterGroupName() != null) {
            xml.start("ClusterParameterGroups")
                    .start("ClusterParameterGroup")
                    .elem("ParameterGroupName", cluster.getClusterParameterGroupName())
                    .elem("ParameterApplyStatus", "in-sync")
                    .end("ClusterParameterGroup")
                    .end("ClusterParameterGroups");
        }
        writeStringList(xml, "VpcSecurityGroups", "VpcSecurityGroup", "VpcSecurityGroupId",
                cluster.getVpcSecurityGroupIds());
        writeStringList(xml, "IamRoles", "ClusterIamRole", "IamRoleArn", cluster.getIamRoles());
        writeTags(xml, cluster.getTags());
        return xml.build();
    }

    private static String subnetGroupXml(RedshiftClusterSubnetGroup group) {
        return new XmlBuilder().start("ClusterSubnetGroup")
                .raw(subnetGroupInnerXml(group)).end("ClusterSubnetGroup").build();
    }

    private static String subnetGroupInnerXml(RedshiftClusterSubnetGroup group) {
        XmlBuilder xml = new XmlBuilder()
                .elem("ClusterSubnetGroupName", group.getClusterSubnetGroupName())
                .elem("Description", group.getDescription())
                .elem("VpcId", group.getVpcId())
                .elem("SubnetGroupStatus", group.getSubnetGroupStatus());
        xml.start("Subnets");
        for (String subnetId : group.getSubnetIds()) {
            String az = group.getSubnetAvailabilityZones().get(subnetId);
            xml.start("Subnet")
                    .elem("SubnetIdentifier", subnetId)
                    .start("SubnetAvailabilityZone").elem("Name", az != null ? az : "us-east-1a")
                    .end("SubnetAvailabilityZone")
                    .elem("SubnetStatus", "Active")
                    .end("Subnet");
        }
        xml.end("Subnets");
        writeTags(xml, group.getTags());
        return xml.build();
    }

    private static String eventSubscriptionXml(EventSubscription subscription) {
        return new XmlBuilder().start("EventSubscription")
                .raw(eventSubscriptionInnerXml(subscription))
                .end("EventSubscription")
                .build();
    }

    private static String eventSubscriptionInnerXml(EventSubscription subscription) {
        XmlBuilder xml = new XmlBuilder()
                .elem("CustomerAwsId", subscription.getCustomerAwsId())
                .elem("CustSubscriptionId", subscription.getCustSubscriptionId())
                .elem("SnsTopicArn", subscription.getSnsTopicArn())
                .elem("Status", subscription.getStatus())
                .elem("SourceType", subscription.getSourceType())
                .elem("Severity", subscription.getSeverity())
                .elem("Enabled", subscription.isEnabled());
        if (subscription.getSubscriptionCreationTime() != null) {
            xml.elem("SubscriptionCreationTime", subscription.getSubscriptionCreationTime().toString());
        }
        xml.start("SourceIdsList");
        for (String sourceId : subscription.getSourceIds()) {
            xml.elem("SourceId", sourceId);
        }
        xml.end("SourceIdsList");
        xml.start("EventCategoriesList");
        for (String category : subscription.getEventCategories()) {
            xml.elem("EventCategory", category);
        }
        xml.end("EventCategoriesList");
        writeTags(xml, subscription.getTags());
        return xml.build();
    }

    private static String snapshotXml(ClusterSnapshot snapshot) {
        return new XmlBuilder().start("Snapshot").raw(snapshotInnerXml(snapshot)).end("Snapshot").build();
    }

    private static String snapshotInnerXml(ClusterSnapshot snapshot) {
        XmlBuilder xml = new XmlBuilder()
                .elem("SnapshotIdentifier", snapshot.getSnapshotIdentifier())
                .elem("ClusterIdentifier", snapshot.getClusterIdentifier())
                .elem("SnapshotType", snapshot.getSnapshotType())
                .elem("Status", snapshot.getStatus())
                .elem("NodeType", snapshot.getNodeType())
                .elem("NumberOfNodes", snapshot.getNumberOfNodes())
                .elem("Port", snapshot.getPort())
                .elem("AvailabilityZone", snapshot.getAvailabilityZone())
                .elem("MasterUsername", snapshot.getMasterUsername())
                .elem("DBName", snapshot.getDbName())
                .elem("ClusterVersion", snapshot.getClusterVersion())
                .elem("Encrypted", snapshot.isEncrypted())
                .elem("SnapshotArn", snapshot.getSnapshotArn())
                .elem("OwnerAccount", snapshot.getOwnerAccount());
        if (snapshot.getSnapshotCreateTime() != null) {
            xml.elem("SnapshotCreateTime", snapshot.getSnapshotCreateTime().toString());
        }
        if (snapshot.getClusterCreateTime() != null) {
            xml.elem("ClusterCreateTime", snapshot.getClusterCreateTime().toString());
        }
        return xml.build();
    }

    private static String parameterGroupXml(ClusterParameterGroup group) {
        return new XmlBuilder().start("ClusterParameterGroup")
                .raw(parameterGroupInnerXml(group))
                .end("ClusterParameterGroup")
                .build();
    }

    private static String parameterGroupInnerXml(ClusterParameterGroup group) {
        XmlBuilder xml = new XmlBuilder()
                .elem("ParameterGroupName", group.getParameterGroupName())
                .elem("ParameterGroupFamily", group.getParameterGroupFamily())
                .elem("Description", group.getDescription());
        writeTags(xml, group.getTags());
        return xml.build();
    }

    /**
     * Distilled Query serialization uses the list element's xmlName ({@code Parameter}),
     * so requests arrive as {@code Parameters.Parameter.N.*}. Classic SDKs still send
     * {@code Parameters.member.N.*}; flattened {@code Parameters.N.*} is a fallback.
     */
    private static Map<String, String> parseParameters(MultivaluedMap<String, String> params) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int n = 1; ; n++) {
            String paramName = parameterField(params, n, "ParameterName");
            if (paramName == null) {
                break;
            }
            String paramValue = parameterField(params, n, "ParameterValue");
            if (paramValue != null) {
                parameters.put(paramName, paramValue);
            }
        }
        return parameters;
    }

    private static List<String> parameterNames(MultivaluedMap<String, String> params) {
        List<String> names = new ArrayList<>();
        for (int n = 1; ; n++) {
            String name = parameterField(params, n, "ParameterName");
            if (name == null) {
                break;
            }
            names.add(name);
        }
        return names;
    }

    private static String parameterField(MultivaluedMap<String, String> params, int n, String field) {
        String value = params.getFirst("Parameters.Parameter." + n + "." + field);
        if (value != null) {
            return value;
        }
        value = params.getFirst("Parameters.member." + n + "." + field);
        if (value != null) {
            return value;
        }
        return params.getFirst("Parameters." + n + "." + field);
    }

    private static void writeTags(XmlBuilder xml, Map<String, String> tags) {
        xml.start("Tags");
        if (tags != null) {
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                xml.start("Tag").elem("Key", entry.getKey()).elem("Value", entry.getValue()).end("Tag");
            }
        }
        xml.end("Tags");
    }

    private static void writeStringList(XmlBuilder xml, String listName, String itemName,
                                        String fieldName, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        xml.start(listName);
        for (String value : values) {
            xml.start(itemName).elem(fieldName, value).end(itemName);
        }
        xml.end(listName);
    }

    private static Map<String, String> extractTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = firstNonNull(params.getFirst("Tags.member." + i + ".Key"),
                    firstNonNull(params.getFirst("Tags.Tag." + i + ".Key"),
                            params.getFirst("Tag." + i + ".Key")));
            if (key == null || key.isBlank()) {
                break;
            }
            String value = firstNonNull(params.getFirst("Tags.member." + i + ".Value"),
                    firstNonNull(params.getFirst("Tags.Tag." + i + ".Value"),
                            params.getFirst("Tag." + i + ".Value")));
            tags.put(key, value);
        }
        return tags;
    }

    private static List<String> emptyToNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    /**
     * Distilled Query lists use the member xmlName ({@code SubnetIdentifier},
     * {@code TagKey}); classic SDKs still send {@code .member.N}.
     */
    private static List<String> extractMembers(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = readIndexed(params, prefix);
        if (!values.isEmpty() || !prefix.endsWith(".member.")) {
            return values;
        }
        String base = prefix.substring(0, prefix.length() - ".member.".length());
        for (String alt : List.of(
                base + ".SubnetIdentifier.",
                base + ".TagKey.",
                base + ".VpcSecurityGroupId.",
                base + ".EventCategory.",
                base + ".SourceId.",
                base + ".")) {
            values = readIndexed(params, alt);
            if (!values.isEmpty()) {
                return values;
            }
        }
        return values;
    }

    private static List<String> readIndexed(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(prefix + i);
            if (value == null) {
                break;
            }
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }

    private static boolean hasMemberParam(MultivaluedMap<String, String> params, String base) {
        String prefix = base + ".";
        return params.keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }
}
