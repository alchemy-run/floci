package io.github.hectorvent.floci.services.neptune;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterParameterGroup;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSnapshot;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstance;
import io.github.hectorvent.floci.services.neptune.model.NeptuneSubnetGroup;
import io.github.hectorvent.floci.services.neptune.model.NeptuneParameterGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class NeptuneQueryHandler {

    private static final Logger LOG = Logger.getLogger(NeptuneQueryHandler.class);

    private final NeptuneService service;
    private final EmulatorConfig config;

    @Inject
    public NeptuneQueryHandler(NeptuneService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.infov("Neptune action: {0}", action);
        try {
            return switch (action) {
                case "CreateDBCluster"    -> handleCreateDbCluster(params);
                case "DescribeDBClusters" -> handleDescribeDbClusters(params);
                case "DeleteDBCluster"    -> handleDeleteDbCluster(params);
                case "ModifyDBCluster"    -> handleModifyDbCluster(params);
                case "CreateDBInstance"   -> handleCreateDbInstance(params);
                case "DescribeDBInstances"-> handleDescribeDbInstances(params);
                case "DeleteDBInstance"   -> handleDeleteDbInstance(params);
                case "ModifyDBInstance"   -> handleModifyDbInstance(params);
                case "DescribeDBClusterSnapshots" -> handleDescribeDbClusterSnapshots(params);
                case "DeleteDBClusterSnapshot" -> handleDeleteDbClusterSnapshot(params);
                case "CopyDBClusterSnapshot" -> handleCopyDbClusterSnapshot(params);
                case "DescribeDBClusterEndpoints" -> handleDescribeDbClusterEndpoints();
                case "DescribeEvents" -> handleDescribeEvents();
                case "DescribePendingMaintenanceActions" -> handleDescribePendingMaintenanceActions();
                case "ApplyPendingMaintenanceAction" -> handleApplyPendingMaintenanceAction(params);
                case "CreateDBClusterParameterGroup" -> handleCreateDbClusterParameterGroup(params);
                case "DescribeDBClusterParameterGroups" -> handleDescribeDbClusterParameterGroups(params);
                case "DeleteDBClusterParameterGroup" -> handleDeleteDbClusterParameterGroup(params);
                case "ModifyDBClusterParameterGroup" -> handleModifyDbClusterParameterGroup(params);
                case "DescribeDBClusterParameters" -> handleDescribeDbClusterParameters(params);
                case "ResetDBClusterParameterGroup" -> handleResetDbClusterParameterGroup(params);
                case "CreateDBParameterGroup" -> handleCreateDbParameterGroup(params);
                case "DescribeDBParameterGroups" -> handleDescribeDbParameterGroups(params);
                case "DeleteDBParameterGroup" -> handleDeleteDbParameterGroup(params);
                case "ModifyDBParameterGroup" -> handleModifyDbParameterGroup(params);
                case "DescribeDBParameters" -> handleDescribeDbParameters(params);
                case "ResetDBParameterGroup" -> handleResetDbParameterGroup(params);
                case "CreateDBSubnetGroup" -> handleCreateDbSubnetGroup(params);
                case "DescribeDBSubnetGroups" -> handleDescribeDbSubnetGroups(params);
                case "ModifyDBSubnetGroup" -> handleModifyDbSubnetGroup(params);
                case "DeleteDBSubnetGroup" -> handleDeleteDbSubnetGroup(params);
                case "AddTagsToResource" -> handleAddTagsToResource(params);
                case "ListTagsForResource" -> handleListTagsForResource(params);
                case "RemoveTagsFromResource" -> handleRemoveTagsFromResource(params);
                default -> AwsQueryResponse.error("UnsupportedOperation",
                        "Operation " + action + " is not supported by Neptune.", AwsNamespaces.RDS, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in Neptune {0}", action);
            return Response.serverError().entity("Unexpected error: " + e.getMessage()).build();
        }
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    private Response handleCreateDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String engineVersion = params.getFirst("EngineVersion");
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));

        NeptuneCluster cluster = service.createDbCluster(id, engineVersion, iamEnabled);
        return Response.ok(AwsQueryResponse.envelope("CreateDBCluster", AwsNamespaces.RDS,
                clusterXml(cluster))).build();
    }

    private Response handleDescribeDbClusters(MultivaluedMap<String, String> params) {
        String identifier = params.getFirst("DBClusterIdentifier");
        String filterId = identifier;
        if (filterId == null || filterId.isBlank()) {
            filterId = extractFilterValue(params, "db-cluster-id");
        }

        // AWS parity: the DBClusterIdentifier parameter faults with
        // DBClusterNotFoundFault when no cluster matches, while the
        // db-cluster-id Filters form returns an empty list.
        if (identifier != null && !identifier.isBlank()) {
            service.getDbCluster(identifier); // throws DBClusterNotFoundFault if absent
        }

        Collection<NeptuneCluster> result = service.listDbClusters(filterId);

        XmlBuilder xml = new XmlBuilder().start("DBClusters");
        for (NeptuneCluster c : result) {
            xml.start("DBCluster").raw(clusterInnerXml(c)).end("DBCluster");
        }
        xml.end("DBClusters").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusters", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleDeleteDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneCluster cluster = service.getDbCluster(id);
        service.deleteDbCluster(id);
        return Response.ok(AwsQueryResponse.envelope("DeleteDBCluster", AwsNamespaces.RDS,
                clusterXml(cluster))).build();
    }

    private Response handleModifyDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String engineVersion = params.getFirst("EngineVersion");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;

        NeptuneCluster cluster = service.modifyDbCluster(id, engineVersion, iamEnabled);
        return Response.ok(AwsQueryResponse.envelope("ModifyDBCluster", AwsNamespaces.RDS,
                clusterXml(cluster))).build();
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    private Response handleCreateDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String dbClusterIdentifier = params.getFirst("DBClusterIdentifier");
        if (dbClusterIdentifier == null || dbClusterIdentifier.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterIdentifier is required for Neptune instances.", AwsNamespaces.RDS, 400);
        }
        String dbInstanceClass = params.getFirst("DBInstanceClass");
        String engineVersion = params.getFirst("EngineVersion");
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));

        NeptuneInstance instance = service.createDbInstance(id, dbClusterIdentifier,
                dbInstanceClass, engineVersion, iamEnabled);
        return Response.ok(AwsQueryResponse.envelope("CreateDBInstance", AwsNamespaces.RDS,
                instanceXml(instance))).build();
    }

    private Response handleDescribeDbInstances(MultivaluedMap<String, String> params) {
        String identifier = params.getFirst("DBInstanceIdentifier");
        String filterId = identifier;
        if (filterId == null || filterId.isBlank()) {
            filterId = extractFilterValue(params, "db-instance-id");
        }

        // AWS parity: the DBInstanceIdentifier parameter faults with
        // DBInstanceNotFound when no instance matches, while the
        // db-instance-id Filters form returns an empty list.
        if (identifier != null && !identifier.isBlank()) {
            service.getDbInstance(identifier); // throws DBInstanceNotFound if absent
        }

        Collection<NeptuneInstance> result = service.listDbInstances(filterId);

        XmlBuilder xml = new XmlBuilder().start("DBInstances");
        for (NeptuneInstance i : result) {
            xml.start("DBInstance").raw(instanceInnerXml(i)).end("DBInstance");
        }
        xml.end("DBInstances").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBInstances", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleDeleteDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneInstance instance = service.getDbInstance(id);
        service.deleteDbInstance(id);
        return Response.ok(AwsQueryResponse.envelope("DeleteDBInstance", AwsNamespaces.RDS,
                instanceXml(instance))).build();
    }

    private Response handleModifyDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String dbInstanceClass = params.getFirst("DBInstanceClass");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;

        NeptuneInstance instance = service.modifyDbInstance(id, dbInstanceClass, iamEnabled);
        return Response.ok(AwsQueryResponse.envelope("ModifyDBInstance", AwsNamespaces.RDS,
                instanceXml(instance))).build();
    }

    // ── Snapshots, events, maintenance (Alchemy bindings) ─────────────────────

    private Response handleDescribeDbClusterSnapshots(MultivaluedMap<String, String> params) {
        Collection<NeptuneClusterSnapshot> result = service.listDbClusterSnapshots(
                params.getFirst("DBClusterSnapshotIdentifier"), params.getFirst("DBClusterIdentifier"));
        XmlBuilder xml = new XmlBuilder().start("DBClusterSnapshots");
        for (NeptuneClusterSnapshot snapshot : result) {
            xml.start("DBClusterSnapshot").raw(clusterSnapshotInnerXml(snapshot)).end("DBClusterSnapshot");
        }
        xml.end("DBClusterSnapshots");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterSnapshots", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleDeleteDbClusterSnapshot(MultivaluedMap<String, String> params) {
        NeptuneClusterSnapshot snapshot = service.getDbClusterSnapshot(params.getFirst("DBClusterSnapshotIdentifier"));
        service.deleteDbClusterSnapshot(params.getFirst("DBClusterSnapshotIdentifier"));
        return Response.ok(AwsQueryResponse.envelope("DeleteDBClusterSnapshot", AwsNamespaces.RDS,
                clusterSnapshotXml(snapshot))).build();
    }

    private Response handleCopyDbClusterSnapshot(MultivaluedMap<String, String> params) {
        NeptuneClusterSnapshot snapshot = service.copyDbClusterSnapshot(
                params.getFirst("SourceDBClusterSnapshotIdentifier"),
                params.getFirst("TargetDBClusterSnapshotIdentifier"));
        return Response.ok(AwsQueryResponse.envelope("CopyDBClusterSnapshot", AwsNamespaces.RDS,
                clusterSnapshotXml(snapshot))).build();
    }

    private Response handleDescribeDbClusterEndpoints() {
        String result = new XmlBuilder().start("DBClusterEndpoints").end("DBClusterEndpoints").build();
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterEndpoints", AwsNamespaces.RDS, result)).build();
    }

    private Response handleDescribeEvents() {
        String result = new XmlBuilder().start("Events").end("Events").build();
        return Response.ok(AwsQueryResponse.envelope("DescribeEvents", AwsNamespaces.RDS, result)).build();
    }

    private Response handleDescribePendingMaintenanceActions() {
        String result = new XmlBuilder().start("PendingMaintenanceActions").end("PendingMaintenanceActions").build();
        return Response.ok(AwsQueryResponse.envelope("DescribePendingMaintenanceActions", AwsNamespaces.RDS, result)).build();
    }

    private Response handleApplyPendingMaintenanceAction(MultivaluedMap<String, String> params) {
        String resourceIdentifier = service.applyPendingMaintenanceAction(
                params.getFirst("ResourceIdentifier"));
        String result = new XmlBuilder()
                .start("ResourcePendingMaintenanceActions")
                .elem("ResourceIdentifier", resourceIdentifier)
                .start("PendingMaintenanceActionDetails")
                .end("PendingMaintenanceActionDetails")
                .end("ResourcePendingMaintenanceActions")
                .build();
        return Response.ok(AwsQueryResponse.envelope(
                "ApplyPendingMaintenanceAction", AwsNamespaces.RDS, result)).build();
    }

    // ── Cluster parameter groups ──────────────────────────────────────────────

    private Response handleCreateDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        String family = params.getFirst("DBParameterGroupFamily");
        String description = params.getFirst("Description");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneClusterParameterGroup group = service.createDbClusterParameterGroup(
                name, family, description, parseTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateDBClusterParameterGroup", AwsNamespaces.RDS,
                clusterParamGroupXml(group))).build();
    }

    private Response handleDescribeDbClusterParameterGroups(MultivaluedMap<String, String> params) {
        String filterName = params.getFirst("DBClusterParameterGroupName");
        Collection<NeptuneClusterParameterGroup> result = service.listDbClusterParameterGroups(filterName);
        XmlBuilder xml = new XmlBuilder().start("DBClusterParameterGroups");
        for (NeptuneClusterParameterGroup group : result) {
            xml.start("DBClusterParameterGroup").raw(clusterParamGroupInnerXml(group)).end("DBClusterParameterGroup");
        }
        xml.end("DBClusterParameterGroups").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterParameterGroups", AwsNamespaces.RDS,
                xml.build())).build();
    }

    private Response handleDeleteDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        service.deleteDbClusterParameterGroup(name);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDBClusterParameterGroup",
                AwsNamespaces.RDS)).build();
    }

    private Response handleModifyDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneClusterParameterGroup group = service.modifyDbClusterParameterGroup(name, parseParameters(params));
        String result = new XmlBuilder()
                .elem("DBClusterParameterGroupName", group.getDbClusterParameterGroupName())
                .build();
        return Response.ok(AwsQueryResponse.envelope("ModifyDBClusterParameterGroup", AwsNamespaces.RDS,
                result)).build();
    }

    private Response handleDescribeDbClusterParameters(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneClusterParameterGroup group = service.getDbClusterParameterGroup(name);
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
               .elem("ApplyType", "dynamic")
               .elem("ApplyMethod", "immediate")
               .elem("IsModifiable", true)
               .end("Parameter");
        }
        xml.end("Parameters").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterParameters", AwsNamespaces.RDS,
                xml.build())).build();
    }

    private Response handleResetDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneClusterParameterGroup group = service.resetDbClusterParameterGroup(
                name,
                "true".equalsIgnoreCase(params.getFirst("ResetAllParameters")),
                parameterNames(params));
        String result = new XmlBuilder()
                .elem("DBClusterParameterGroupName", group.getDbClusterParameterGroupName())
                .build();
        return Response.ok(AwsQueryResponse.envelope("ResetDBClusterParameterGroup", AwsNamespaces.RDS,
                result)).build();
    }

    // ── Instance parameter groups ─────────────────────────────────────────────

    private Response handleCreateDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        String family = params.getFirst("DBParameterGroupFamily");
        String description = params.getFirst("Description");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneParameterGroup group = service.createDbParameterGroup(
                name, family, description, parseTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateDBParameterGroup", AwsNamespaces.RDS,
                paramGroupXml(group))).build();
    }

    private Response handleDescribeDbParameterGroups(MultivaluedMap<String, String> params) {
        String filterName = params.getFirst("DBParameterGroupName");
        Collection<NeptuneParameterGroup> result = service.listDbParameterGroups(filterName);
        XmlBuilder xml = new XmlBuilder().start("DBParameterGroups");
        for (NeptuneParameterGroup group : result) {
            xml.start("DBParameterGroup").raw(paramGroupInnerXml(group)).end("DBParameterGroup");
        }
        xml.end("DBParameterGroups").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBParameterGroups", AwsNamespaces.RDS,
                xml.build())).build();
    }

    private Response handleDeleteDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        service.deleteDbParameterGroup(name);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDBParameterGroup",
                AwsNamespaces.RDS)).build();
    }

    private Response handleModifyDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneParameterGroup group = service.modifyDbParameterGroup(name, parseParameters(params));
        String result = new XmlBuilder()
                .elem("DBParameterGroupName", group.getDbParameterGroupName())
                .build();
        return Response.ok(AwsQueryResponse.envelope("ModifyDBParameterGroup", AwsNamespaces.RDS,
                result)).build();
    }

    private Response handleDescribeDbParameters(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneParameterGroup group = service.getDbParameterGroup(name);
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
               .elem("ApplyType", "dynamic")
               .elem("ApplyMethod", "immediate")
               .elem("IsModifiable", true)
               .end("Parameter");
        }
        xml.end("Parameters").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBParameters", AwsNamespaces.RDS,
                xml.build())).build();
    }

    private Response handleResetDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneParameterGroup group = service.resetDbParameterGroup(
                name,
                "true".equalsIgnoreCase(params.getFirst("ResetAllParameters")),
                parameterNames(params));
        String result = new XmlBuilder()
                .elem("DBParameterGroupName", group.getDbParameterGroupName())
                .build();
        return Response.ok(AwsQueryResponse.envelope("ResetDBParameterGroup", AwsNamespaces.RDS,
                result)).build();
    }

    private Response handleAddTagsToResource(MultivaluedMap<String, String> params) {
        service.addTagsToResource(params.getFirst("ResourceName"), parseTags(params));
        return Response.ok(AwsQueryResponse.envelope("AddTagsToResource", AwsNamespaces.RDS, "")).build();
    }

    private Response handleListTagsForResource(MultivaluedMap<String, String> params) {
        XmlBuilder xml = new XmlBuilder().start("TagList");
        writeTags(xml, service.listTagsForResource(params.getFirst("ResourceName")));
        xml.end("TagList");
        return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleRemoveTagsFromResource(MultivaluedMap<String, String> params) {
        service.removeTagsFromResource(params.getFirst("ResourceName"), memberList(params, "TagKeys"));
        return Response.ok(AwsQueryResponse.envelope("RemoveTagsFromResource", AwsNamespaces.RDS, "")).build();
    }

    // ── DB subnet groups ──────────────────────────────────────────────────────

    private Response handleCreateDbSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("MissingParameter",
                    "The request must contain the parameter DBSubnetGroupName.", AwsNamespaces.RDS, 400);
        }
        String description = params.getFirst("DBSubnetGroupDescription");
        List<String> subnetIds = memberList(params, "SubnetIds");
        NeptuneSubnetGroup group = service.createDbSubnetGroup(name, description, subnetIds, parseTags(params));
        return Response.ok(AwsQueryResponse.envelope("CreateDBSubnetGroup", AwsNamespaces.RDS,
                dbSubnetGroupXml(group))).build();
    }

    private Response handleDescribeDbSubnetGroups(MultivaluedMap<String, String> params) {
        String filterName = params.getFirst("DBSubnetGroupName");
        Collection<NeptuneSubnetGroup> result = service.listDbSubnetGroups(filterName);
        XmlBuilder xml = new XmlBuilder().start("DBSubnetGroups");
        for (NeptuneSubnetGroup group : result) {
            xml.start("DBSubnetGroup").raw(dbSubnetGroupInnerXml(group)).end("DBSubnetGroup");
        }
        xml.end("DBSubnetGroups").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBSubnetGroups", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleModifyDbSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        NeptuneSubnetGroup group = service.modifyDbSubnetGroup(
                name, params.getFirst("DBSubnetGroupDescription"), memberList(params, "SubnetIds"));
        return Response.ok(AwsQueryResponse.envelope("ModifyDBSubnetGroup", AwsNamespaces.RDS,
                dbSubnetGroupXml(group))).build();
    }

    private Response handleDeleteDbSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        service.deleteDbSubnetGroup(name);
        return Response.ok(AwsQueryResponse.envelope("DeleteDBSubnetGroup", AwsNamespaces.RDS, "")).build();
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String clusterXml(NeptuneCluster c) {
        return new XmlBuilder().start("DBCluster").raw(clusterInnerXml(c)).end("DBCluster").build();
    }

    private String clusterInnerXml(NeptuneCluster c) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBClusterIdentifier", c.getDbClusterIdentifier())
                .elem("Status", c.getStatus())
                .elem("Engine", "neptune")
                .elem("EngineVersion", c.getEngineVersion())
                .elem("Endpoint", c.getEndpoint())
                .elem("ReaderEndpoint", c.getReaderEndpoint())
                .elem("Port", c.getPort())
                .elem("IAMDatabaseAuthenticationEnabled", c.isIamDatabaseAuthenticationEnabled())
                .elem("MultiAZ", false)
                .elem("StorageEncrypted", true)
                .elem("AvailabilityZone", config.defaultAvailabilityZone())
                .elem("DbClusterResourceId", c.getDbClusterResourceId())
                .elem("DBClusterArn", c.getDbClusterArn())
                .start("DBClusterMembers");
        if (c.getDbClusterMembers() != null) {
            for (String memberId : c.getDbClusterMembers()) {
                xml.start("member")
                   .elem("DBInstanceIdentifier", memberId)
                   .elem("IsClusterWriter", true)
                   .end("member");
            }
        }
        xml.end("DBClusterMembers");
        return xml.build();
    }

    private String instanceXml(NeptuneInstance i) {
        return new XmlBuilder().start("DBInstance").raw(instanceInnerXml(i)).end("DBInstance").build();
    }

    private String instanceInnerXml(NeptuneInstance i) {
        return new XmlBuilder()
                .elem("DBInstanceIdentifier", i.getDbInstanceIdentifier())
                .elem("DBClusterIdentifier", i.getDbClusterIdentifier())
                .elem("DBInstanceClass", i.getDbInstanceClass())
                .elem("DBInstanceStatus", i.getStatus())
                .elem("Engine", "neptune")
                .elem("EngineVersion", i.getEngineVersion())
                .start("Endpoint")
                  .elem("Address", i.getEndpoint())
                  .elem("Port", i.getPort())
                .end("Endpoint")
                .elem("IAMDatabaseAuthenticationEnabled", i.isIamDatabaseAuthenticationEnabled())
                .elem("MultiAZ", false)
                .elem("StorageEncrypted", true)
                .elem("AvailabilityZone", config.defaultAvailabilityZone())
                .elem("DbiResourceId", i.getDbiResourceId())
                .elem("DBInstanceArn", i.getDbInstanceArn())
                .build();
    }

    private String clusterSnapshotXml(NeptuneClusterSnapshot snapshot) {
        return new XmlBuilder().start("DBClusterSnapshot").raw(clusterSnapshotInnerXml(snapshot)).end("DBClusterSnapshot").build();
    }

    private String clusterSnapshotInnerXml(NeptuneClusterSnapshot snapshot) {
        return new XmlBuilder()
                .elem("DBClusterSnapshotIdentifier", snapshot.getDbClusterSnapshotIdentifier())
                .elem("DBClusterIdentifier", snapshot.getDbClusterIdentifier())
                .elem("Status", snapshot.getStatus())
                .elem("Engine", snapshot.getEngine())
                .elem("SnapshotType", snapshot.getSnapshotType())
                .elem("DBClusterSnapshotArn", snapshot.getDbClusterSnapshotArn())
                .build();
    }

    private String paramGroupXml(NeptuneParameterGroup group) {
        return new XmlBuilder().start("DBParameterGroup")
                .raw(paramGroupInnerXml(group))
                .end("DBParameterGroup")
                .build();
    }

    private String paramGroupInnerXml(NeptuneParameterGroup group) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBParameterGroupName", group.getDbParameterGroupName())
                .elem("DBParameterGroupFamily", group.getDbParameterGroupFamily())
                .elem("Description", group.getDescription());
        if (group.getDbParameterGroupArn() != null) {
            xml.elem("DBParameterGroupArn", group.getDbParameterGroupArn());
        }
        return xml.build();
    }

    private String clusterParamGroupXml(NeptuneClusterParameterGroup group) {
        return new XmlBuilder().start("DBClusterParameterGroup")
                .raw(clusterParamGroupInnerXml(group))
                .end("DBClusterParameterGroup")
                .build();
    }

    private String clusterParamGroupInnerXml(NeptuneClusterParameterGroup group) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBClusterParameterGroupName", group.getDbClusterParameterGroupName())
                .elem("DBParameterGroupFamily", group.getDbParameterGroupFamily())
                .elem("Description", group.getDescription());
        if (group.getDbClusterParameterGroupArn() != null) {
            xml.elem("DBClusterParameterGroupArn", group.getDbClusterParameterGroupArn());
        }
        return xml.build();
    }

    private String dbSubnetGroupXml(NeptuneSubnetGroup group) {
        return new XmlBuilder().start("DBSubnetGroup").raw(dbSubnetGroupInnerXml(group)).end("DBSubnetGroup").build();
    }

    private String dbSubnetGroupInnerXml(NeptuneSubnetGroup group) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBSubnetGroupName", group.getDbSubnetGroupName())
                .elem("DBSubnetGroupDescription", group.getDescription())
                .elem("VpcId", group.getVpcId() != null ? group.getVpcId() : "vpc-00000000")
                .elem("SubnetGroupStatus", group.getSubnetGroupStatus() != null ? group.getSubnetGroupStatus() : "Complete")
                .elem("DBSubnetGroupArn", group.getDbSubnetGroupArn())
                .start("Subnets");
        for (String subnetId : group.getSubnetIds()) {
            String az = group.getSubnetAvailabilityZones().get(subnetId);
            xml.start("Subnet")
               .elem("SubnetIdentifier", subnetId)
               .start("SubnetAvailabilityZone")
                 .elem("Name", az != null ? az : config.defaultAvailabilityZone())
               .end("SubnetAvailabilityZone")
               .elem("SubnetStatus", "Active")
               .end("Subnet");
        }
        return xml.end("Subnets").build();
    }

    private static void writeTags(XmlBuilder xml, Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        tags.forEach((key, value) -> xml.start("Tag")
                .elem("Key", key)
                .elem("Value", value)
                .end("Tag"));
    }

    /**
     * Distilled's AWS Query serializer uses the list element's xmlName
     * ({@code Parameter}), so requests arrive as {@code Parameters.Parameter.N.*}.
     * Classic AWS SDKs still send {@code Parameters.member.N.*}; flattened
     * {@code Parameters.N.*} is accepted as a fallback.
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

    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        readTags(params, "Tags.member", tags);
        readTags(params, "Tags.Tag", tags);
        readTags(params, "Tag", tags);
        return tags;
    }

    private static void readTags(MultivaluedMap<String, String> params, String prefix, Map<String, String> tags) {
        for (int i = 1; ; i++) {
            String key = params.getFirst(prefix + "." + i + ".Key");
            if (key == null) {
                break;
            }
            tags.put(key, params.getFirst(prefix + "." + i + ".Value"));
        }
    }

    private static List<String> memberList(MultivaluedMap<String, String> params, String baseName) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(baseName + ".member." + i);
            if (value == null) {
                value = params.getFirst(baseName + ".SubnetIdentifier." + i);
            }
            if (value == null) {
                value = params.getFirst(baseName + "." + i);
            }
            if (value == null) {
                break;
            }
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String extractFilterValue(MultivaluedMap<String, String> params, String filterName) {
        for (int i = 1; ; i++) {
            String name = params.getFirst("Filters.Filter." + i + ".Name");
            if (name == null) {
                break;
            }
            if (filterName.equals(name)) {
                return params.getFirst("Filters.Filter." + i + ".Values.Value.1");
            }
        }
        return null;
    }
}
