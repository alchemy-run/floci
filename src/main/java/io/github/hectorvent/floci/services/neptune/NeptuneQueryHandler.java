package io.github.hectorvent.floci.services.neptune;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSnapshot;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collection;

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
