package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbClusterSnapshot;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbSnapshot;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-protocol handler for all RDS actions (form-encoded POST, XML response).
 */
@ApplicationScoped
public class RdsQueryHandler {

    private static final Logger LOG = Logger.getLogger(RdsQueryHandler.class);

    private final RdsService service;
    private final EmulatorConfig config;

    @Inject
    public RdsQueryHandler(RdsService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        return handle(action, params, null);
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.infov("RDS action: {0}", action);
        try {
            return switch (action) {
                case "CreateDBInstance" -> handleCreateDbInstance(params, region);
                case "DescribeDBInstances" -> handleDescribeDbInstances(params);
                case "DeleteDBInstance" -> handleDeleteDbInstance(params);
                case "ModifyDBInstance" -> handleModifyDbInstance(params);
                case "RebootDBInstance" -> handleRebootDbInstance(params);
                case "DescribeOrderableDBInstanceOptions" -> handleDescribeOrderableDbInstanceOptions(params);
                case "CreateDBSubnetGroup" -> handleCreateDbSubnetGroup(params, region);
                case "DescribeDBSubnetGroups" -> handleDescribeDbSubnetGroups(params, region);
                case "ModifyDBSubnetGroup" -> handleModifyDbSubnetGroup(params, region);
                case "DeleteDBSubnetGroup" -> handleDeleteDbSubnetGroup(params);
                case "CreateDBCluster" -> handleCreateDbCluster(params, region);
                case "DescribeDBClusters" -> handleDescribeDbClusters(params);
                case "DeleteDBCluster" -> handleDeleteDbCluster(params);
                case "ModifyDBCluster" -> handleModifyDbCluster(params);
                case "CreateDBParameterGroup" -> handleCreateDbParameterGroup(params);
                case "DescribeDBParameterGroups" -> handleDescribeDbParameterGroups(params);
                case "DeleteDBParameterGroup" -> handleDeleteDbParameterGroup(params);
                case "ModifyDBParameterGroup" -> handleModifyDbParameterGroup(params);
                case "DescribeDBParameters" -> handleDescribeDbParameters(params);
                case "CreateDBClusterParameterGroup" -> handleCreateDbClusterParameterGroup(params);
                case "DescribeDBClusterParameterGroups" -> handleDescribeDbClusterParameterGroups(params);
                case "DeleteDBClusterParameterGroup" -> handleDeleteDbClusterParameterGroup(params);
                case "ModifyDBClusterParameterGroup" -> handleModifyDbClusterParameterGroup(params);
                case "DescribeDBClusterParameters" -> handleDescribeDbClusterParameters(params);
                case "DescribeDBSnapshots" -> handleDescribeDbSnapshots(params);
                case "CreateDBSnapshot" -> handleCreateDbSnapshot(params);
                case "DeleteDBSnapshot" -> handleDeleteDbSnapshot(params);
                case "CopyDBSnapshot" -> handleCopyDbSnapshot(params);
                case "DescribeDBProxies" -> handleDescribeDbProxies(params);
                case "DescribeDBClusterSnapshots" -> handleDescribeDbClusterSnapshots(params);
                case "CreateDBClusterSnapshot" -> handleCreateDbClusterSnapshot(params);
                case "DeleteDBClusterSnapshot" -> handleDeleteDbClusterSnapshot(params);
                case "CopyDBClusterSnapshot" -> handleCopyDbClusterSnapshot(params);
                case "DescribeDBClusterEndpoints" -> handleDescribeDbClusterEndpoints(params);
                case "CreateDBClusterEndpoint" -> handleCreateDbClusterEndpoint(params);
                case "ModifyDBClusterEndpoint" -> handleModifyDbClusterEndpoint(params);
                case "DeleteDBClusterEndpoint" -> handleDeleteDbClusterEndpoint(params);
                case "ResetDBParameterGroup" -> handleResetDbParameterGroup(params);
                case "ResetDBClusterParameterGroup" -> handleResetDbClusterParameterGroup(params);
                case "DescribeEvents" -> handleDescribeEvents();
                case "DescribePendingMaintenanceActions" -> handleDescribePendingMaintenanceActions();
                case "ApplyPendingMaintenanceAction" -> handleApplyPendingMaintenanceAction(params);
                case "StartDBInstance" -> handleStartStopInstance(params, "StartDBInstance");
                case "StopDBInstance" -> handleStartStopInstance(params, "StopDBInstance");
                case "StartDBCluster" -> handleStartStopCluster(params, "StartDBCluster");
                case "StopDBCluster" -> handleStartStopCluster(params, "StopDBCluster");
                case "FailoverDBCluster" -> handleStartStopCluster(params, "FailoverDBCluster");
                case "AddTagsToResource" -> handleAddTagsToResource(params);
                case "ListTagsForResource" -> handleListTagsForResource(params);
                case "RemoveTagsFromResource" -> handleRemoveTagsFromResource(params);
                default -> AwsQueryResponse.error("UnsupportedOperation",
                        "Operation " + action + " is not supported.", AwsNamespaces.RDS, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        } catch (Exception e) {
            LOG.errorv(e, "Unexpected error in RDS {0}", action);
            return Response.serverError().entity("Unexpected error: " + e.getMessage()).build();
        }
    }

    // ── DB Instances ──────────────────────────────────────────────────────────

    private Response handleCreateDbInstance(MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }

        String engine = params.getFirst("Engine");
        String engineVersion = params.getFirst("EngineVersion");
        String masterUsername = params.getFirst("MasterUsername");
        String masterPassword = params.getFirst("MasterUserPassword");
        String dbName = params.getFirst("DBName");
        String dbInstanceClass = params.getFirst("DBInstanceClass");
        String allocatedStorageStr = params.getFirst("AllocatedStorage");
        int allocatedStorage = allocatedStorageStr != null ? parseIntSafe(allocatedStorageStr, 20) : 20;
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));
        String paramGroupName = params.getFirst("DBParameterGroupName");
        String dbSubnetGroupName = params.getFirst("DBSubnetGroupName");
        String dbClusterIdentifier = params.getFirst("DBClusterIdentifier");
        boolean manageMasterUserPassword = "true".equalsIgnoreCase(params.getFirst("ManageMasterUserPassword"));
        String masterUserSecretKmsKeyId = params.getFirst("MasterUserSecretKmsKeyId");
        Map<String, String> tags = parseTags(params);
        String availabilityZone = params.getFirst("AvailabilityZone");
        boolean multiAz = "true".equalsIgnoreCase(params.getFirst("MultiAZ"));

        if (dbInstanceClass == null) {
            dbInstanceClass = "db.t3.micro";
        }
        if (engineVersion == null) {
            engineVersion = defaultEngineVersion(engine);
        }

        try {
            validateMasterUserPassword(masterPassword);
            validateBackupRetentionPeriod(params.getFirst("BackupRetentionPeriod"), false);
            List<String> vpcSecurityGroupIds = vpcSecurityGroupIds(params);
            DbInstance instance = service.createDbInstance(id, engine, engineVersion, masterUsername,
                    masterPassword, dbName, dbInstanceClass, allocatedStorage, iamEnabled,
                    paramGroupName, dbSubnetGroupName, dbClusterIdentifier, availabilityZone, multiAz,
                    manageMasterUserPassword, masterUserSecretKmsKeyId, tags, vpcSecurityGroupIds, region);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("CreateDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbInstances(MultivaluedMap<String, String> params) {
        String identifier = params.getFirst("DBInstanceIdentifier");
        String filterId = identifier;
        if (filterId == null || filterId.isBlank()) {
            filterId = extractRdsFilterValue(params, "db-instance-id");
        }
        try {
            Collection<DbInstance> result = service.listDbInstances(filterId);
            // AWS parity: the DBInstanceIdentifier PARAMETER faults with
            // DBInstanceNotFound when no instance matches, while the
            // db-instance-id Filters form returns an empty list.
            if (identifier != null && !identifier.isBlank() && result.isEmpty()) {
                throw new AwsException("DBInstanceNotFound",
                        "DBInstance " + identifier + " not found.", 404);
            }
            XmlBuilder xml = new XmlBuilder().start("DBInstances");
            for (DbInstance i : result) {
                xml.start("DBInstance").raw(dbInstanceInnerXml(i)).end("DBInstance");
            }
            xml.end("DBInstances").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBInstances", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbInstance instance = service.getDbInstance(id);
            service.deleteDbInstance(id);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String newPassword = params.getFirst("MasterUserPassword");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;
        String dbSubnetGroupName = params.getFirst("DBSubnetGroupName");
        try {
            List<String> vpcSecurityGroupIds = vpcSecurityGroupIds(params);
            DbInstance instance = service.modifyDbInstance(
                    id, newPassword, iamEnabled, dbSubnetGroupName, vpcSecurityGroupIds);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeOrderableDbInstanceOptions(MultivaluedMap<String, String> params) {
        Collection<Map<String, String>> options = service.describeOrderableDbInstanceOptions(
                params.getFirst("Engine"),
                params.getFirst("EngineVersion"),
                params.getFirst("DBInstanceClass"));
        XmlBuilder xml = new XmlBuilder().start("OrderableDBInstanceOptions");
        for (Map<String, String> option : options) {
            xml.start("OrderableDBInstanceOption")
               .elem("Engine", option.get("engine"))
               .elem("EngineVersion", option.get("engineVersion"))
               .elem("DBInstanceClass", option.get("dbInstanceClass"))
               .elem("LicenseModel", "postgresql-license")
               .start("AvailabilityZones")
                 .start("AvailabilityZone")
                   .elem("Name", config.defaultAvailabilityZone())
                 .end("AvailabilityZone")
               .end("AvailabilityZones")
               .end("OrderableDBInstanceOption");
        }
        xml.end("OrderableDBInstanceOptions").start("Marker").end("Marker");
        return Response.ok(AwsQueryResponse.envelope("DescribeOrderableDBInstanceOptions",
                AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleAddTagsToResource(MultivaluedMap<String, String> params) {
        String resourceName = params.getFirst("ResourceName");
        try {
            service.addTagsToResource(resourceName, parseTags(params));
            return Response.ok(AwsQueryResponse.envelope("AddTagsToResource", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleListTagsForResource(MultivaluedMap<String, String> params) {
        String resourceName = params.getFirst("ResourceName");
        try {
            XmlBuilder xml = new XmlBuilder().start("TagList");
            writeTags(xml, service.listTagsForResource(resourceName));
            xml.end("TagList");
            return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleRemoveTagsFromResource(MultivaluedMap<String, String> params) {
        String resourceName = params.getFirst("ResourceName");
        try {
            service.removeTagsFromResource(resourceName, memberList(params, "TagKeys"));
            return Response.ok(AwsQueryResponse.envelope("RemoveTagsFromResource", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleCreateDbSubnetGroup(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("MissingParameter",
                    "The request must contain the parameter DBSubnetGroupName.", AwsNamespaces.RDS, 400);
        }
        String description = params.getFirst("DBSubnetGroupDescription");
        List<String> subnetIds = memberList(params, "SubnetIds");
        try {
            DbSubnetGroup group = service.createDbSubnetGroup(name, description, subnetIds, region);
            return Response.ok(AwsQueryResponse.envelope("CreateDBSubnetGroup",
                    AwsNamespaces.RDS, dbSubnetGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbSubnetGroups(MultivaluedMap<String, String> params, String region) {
        String filterName = params.getFirst("DBSubnetGroupName");
        try {
            Collection<DbSubnetGroup> result = service.listDbSubnetGroups(filterName, region);
            XmlBuilder xml = new XmlBuilder().start("DBSubnetGroups");
            for (DbSubnetGroup group : result) {
                xml.start("DBSubnetGroup").raw(dbSubnetGroupInnerXml(group)).end("DBSubnetGroup");
            }
            xml.end("DBSubnetGroups").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBSubnetGroups", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbSubnetGroup(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        List<String> subnetIds = memberList(params, "SubnetIds");
        String description = params.getFirst("DBSubnetGroupDescription");
        try {
            DbSubnetGroup group = (description != null && !description.isBlank())
                    ? service.modifyDbSubnetGroup(name, description, subnetIds, region)
                    : service.modifyDbSubnetGroup(name, subnetIds, region);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBSubnetGroup",
                    AwsNamespaces.RDS, dbSubnetGroupXml(group))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbSubnetGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBSubnetGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "DBSubnetGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbSubnetGroup(name);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBSubnetGroup", AwsNamespaces.RDS, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleRebootDbInstance(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBInstanceIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBInstanceIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbInstance instance = service.rebootDbInstance(id);
            String result = dbInstanceXml(instance);
            return Response.ok(AwsQueryResponse.envelope("RebootDBInstance", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── DB Clusters ───────────────────────────────────────────────────────────

    private Response handleCreateDbCluster(MultivaluedMap<String, String> params, String region) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }

        String engine = params.getFirst("Engine");
        String engineVersion = params.getFirst("EngineVersion");
        String masterUsername = params.getFirst("MasterUsername");
        String masterPassword = params.getFirst("MasterUserPassword");
        String databaseName = params.getFirst("DatabaseName");
        boolean iamEnabled = "true".equalsIgnoreCase(params.getFirst("EnableIAMDatabaseAuthentication"));
        String paramGroupName = params.getFirst("DBClusterParameterGroupName");
        String dbSubnetGroupName = params.getFirst("DBSubnetGroupName");
        String availabilityZone = params.getFirst("AvailabilityZone");
        boolean multiAz = "true".equalsIgnoreCase(params.getFirst("MultiAZ"));

        if (engineVersion == null) {
            engineVersion = defaultEngineVersion(engine);
        }

        try {
            validateMasterUserPassword(masterPassword);
            validateBackupRetentionPeriod(params.getFirst("BackupRetentionPeriod"), true);
            DbCluster cluster = service.createDbCluster(id, engine, engineVersion, masterUsername,
                    masterPassword, databaseName, iamEnabled, paramGroupName,
                    dbSubnetGroupName, availabilityZone, multiAz, region);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("CreateDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusters(MultivaluedMap<String, String> params) {
        String identifier = params.getFirst("DBClusterIdentifier");
        String filterId = identifier;
        if (filterId == null || filterId.isBlank()) {
            filterId = extractRdsFilterValue(params, "db-cluster-id");
        }
        try {
            Collection<DbCluster> result = service.listDbClusters(filterId);
            // AWS parity: the DBClusterIdentifier PARAMETER faults with
            // DBClusterNotFoundFault when no cluster matches, while the
            // db-cluster-id Filters form returns an empty list.
            if (identifier != null && !identifier.isBlank() && result.isEmpty()) {
                throw new AwsException("DBClusterNotFoundFault",
                        "DBCluster " + identifier + " not found.", 404);
            }
            XmlBuilder xml = new XmlBuilder().start("DBClusters");
            for (DbCluster c : result) {
                xml.start("DBCluster").raw(dbClusterInnerXml(c)).end("DBCluster");
            }
            xml.end("DBClusters").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBClusters", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbCluster cluster = service.getDbCluster(id);
            service.deleteDbCluster(id);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("DeleteDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbCluster(MultivaluedMap<String, String> params) {
        String id = params.getFirst("DBClusterIdentifier");
        if (id == null || id.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterIdentifier is required.", AwsNamespaces.RDS, 400);
        }
        String newPassword = params.getFirst("MasterUserPassword");
        String iamStr = params.getFirst("EnableIAMDatabaseAuthentication");
        Boolean iamEnabled = iamStr != null ? Boolean.parseBoolean(iamStr) : null;
        try {
            DbCluster cluster = service.modifyDbCluster(id, newPassword, iamEnabled);
            String result = dbClusterXml(cluster);
            return Response.ok(AwsQueryResponse.envelope("ModifyDBCluster", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── Parameter Groups ──────────────────────────────────────────────────────

    private Response handleCreateDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        String family = params.getFirst("DBParameterGroupFamily");
        String description = params.getFirst("Description");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbParameterGroup group = service.createDbParameterGroup(name, family, description);
            String result = paramGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateDBParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbParameterGroups(MultivaluedMap<String, String> params) {
        String filterName = params.getFirst("DBParameterGroupName");
        try {
            Collection<DbParameterGroup> result = service.listDbParameterGroups(filterName);
            XmlBuilder xml = new XmlBuilder().start("DBParameterGroups");
            for (DbParameterGroup g : result) {
                xml.start("DBParameterGroup").raw(paramGroupInnerXml(g)).end("DBParameterGroup");
            }
            xml.end("DBParameterGroups").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBParameterGroups", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbParameterGroup(name);
            return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDBParameterGroup", AwsNamespaces.RDS)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        Map<String, String> parameters = parseParameters(params);
        try {
            DbParameterGroup group = service.modifyDbParameterGroup(name, parameters);
            String result = new XmlBuilder()
                    .elem("DBParameterGroupName", group.getDbParameterGroupName())
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ModifyDBParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbParameters(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbParameterGroup group = service.getDbParameterGroup(name);
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
            return Response.ok(AwsQueryResponse.envelope("DescribeDBParameters", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── Cluster Parameter Groups ──────────────────────────────────────────────

    private Response handleCreateDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        String family = params.getFirst("DBParameterGroupFamily");
        String description = params.getFirst("Description");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbClusterParameterGroup group = service.createDbClusterParameterGroup(name, family, description);
            String result = clusterParamGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateDBClusterParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusterParameterGroups(MultivaluedMap<String, String> params) {
        String filterName = params.getFirst("DBClusterParameterGroupName");
        try {
            Collection<DbClusterParameterGroup> result = service.listDbClusterParameterGroups(filterName);
            XmlBuilder xml = new XmlBuilder().start("DBClusterParameterGroups");
            for (DbClusterParameterGroup g : result) {
                xml.start("DBClusterParameterGroup").raw(clusterParamGroupInnerXml(g)).end("DBClusterParameterGroup");
            }
            xml.end("DBClusterParameterGroups").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterParameterGroups", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            service.deleteDbClusterParameterGroup(name);
            return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteDBClusterParameterGroup", AwsNamespaces.RDS)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        Map<String, String> parameters = parseParameters(params);
        try {
            DbClusterParameterGroup group = service.modifyDbClusterParameterGroup(name, parameters);
            String result = new XmlBuilder()
                    .elem("DBClusterParameterGroupName", group.getDbClusterParameterGroupName())
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ModifyDBClusterParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusterParameters(MultivaluedMap<String, String> params) {
        String name = params.getFirst("DBClusterParameterGroupName");
        if (name == null || name.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "DBClusterParameterGroupName is required.", AwsNamespaces.RDS, 400);
        }
        try {
            DbClusterParameterGroup group = service.getDbClusterParameterGroup(name);
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
            return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterParameters", AwsNamespaces.RDS, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── Snapshots & Proxies (not modeled — empty lists) ───────────────────────

    private Response handleDescribeDbSnapshots(MultivaluedMap<String, String> params) {
        Collection<DbSnapshot> result = service.listDbSnapshots(
                params.getFirst("DBSnapshotIdentifier"), params.getFirst("DBInstanceIdentifier"));
        XmlBuilder xml = new XmlBuilder().start("DBSnapshots");
        for (DbSnapshot snapshot : result) {
            xml.start("DBSnapshot").raw(dbSnapshotInnerXml(snapshot)).end("DBSnapshot");
        }
        xml.end("DBSnapshots");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBSnapshots", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleCreateDbSnapshot(MultivaluedMap<String, String> params) {
        try {
            DbSnapshot snapshot = service.createDbSnapshot(
                    params.getFirst("DBSnapshotIdentifier"), params.getFirst("DBInstanceIdentifier"));
            return Response.ok(AwsQueryResponse.envelope("CreateDBSnapshot", AwsNamespaces.RDS,
                    new XmlBuilder().start("DBSnapshot").raw(dbSnapshotInnerXml(snapshot)).end("DBSnapshot").build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbSnapshot(MultivaluedMap<String, String> params) {
        try {
            DbSnapshot snapshot = service.getDbSnapshot(params.getFirst("DBSnapshotIdentifier"));
            service.deleteDbSnapshot(params.getFirst("DBSnapshotIdentifier"));
            return Response.ok(AwsQueryResponse.envelope("DeleteDBSnapshot", AwsNamespaces.RDS,
                    new XmlBuilder().start("DBSnapshot").raw(dbSnapshotInnerXml(snapshot)).end("DBSnapshot").build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleCopyDbSnapshot(MultivaluedMap<String, String> params) {
        try {
            DbSnapshot snapshot = service.copyDbSnapshot(
                    params.getFirst("SourceDBSnapshotIdentifier"), params.getFirst("TargetDBSnapshotIdentifier"));
            return Response.ok(AwsQueryResponse.envelope("CopyDBSnapshot", AwsNamespaces.RDS,
                    new XmlBuilder().start("DBSnapshot").raw(dbSnapshotInnerXml(snapshot)).end("DBSnapshot").build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbProxies(MultivaluedMap<String, String> params) {
        // DB proxies are not modeled; return the RDS Query API's wire-accurate empty
        // result (empty <DBProxies> wrapper, no <Marker>) so SDK clients complete the
        // read instead of failing with UnsupportedOperation.
        String result = new XmlBuilder().start("DBProxies").end("DBProxies").build();
        return Response.ok(AwsQueryResponse.envelope("DescribeDBProxies", AwsNamespaces.RDS, result)).build();
    }

    private Response handleDescribeDbClusterSnapshots(MultivaluedMap<String, String> params) {
        Collection<DbClusterSnapshot> result = service.listDbClusterSnapshots(
                params.getFirst("DBClusterSnapshotIdentifier"), params.getFirst("DBClusterIdentifier"));
        XmlBuilder xml = new XmlBuilder().start("DBClusterSnapshots");
        for (DbClusterSnapshot snapshot : result) {
            xml.start("DBClusterSnapshot").raw(dbClusterSnapshotInnerXml(snapshot)).end("DBClusterSnapshot");
        }
        xml.end("DBClusterSnapshots");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterSnapshots", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleCreateDbClusterSnapshot(MultivaluedMap<String, String> params) {
        try {
            DbClusterSnapshot snapshot = service.createDbClusterSnapshot(
                    params.getFirst("DBClusterSnapshotIdentifier"), params.getFirst("DBClusterIdentifier"));
            return Response.ok(AwsQueryResponse.envelope("CreateDBClusterSnapshot", AwsNamespaces.RDS,
                    dbClusterSnapshotXml(snapshot))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbClusterSnapshot(MultivaluedMap<String, String> params) {
        try {
            DbClusterSnapshot snapshot = service.getDbClusterSnapshot(params.getFirst("DBClusterSnapshotIdentifier"));
            service.deleteDbClusterSnapshot(params.getFirst("DBClusterSnapshotIdentifier"));
            return Response.ok(AwsQueryResponse.envelope("DeleteDBClusterSnapshot", AwsNamespaces.RDS,
                    dbClusterSnapshotXml(snapshot))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleCopyDbClusterSnapshot(MultivaluedMap<String, String> params) {
        try {
            DbClusterSnapshot snapshot = service.copyDbClusterSnapshot(
                    params.getFirst("SourceDBClusterSnapshotIdentifier"),
                    params.getFirst("TargetDBClusterSnapshotIdentifier"));
            return Response.ok(AwsQueryResponse.envelope("CopyDBClusterSnapshot", AwsNamespaces.RDS,
                    dbClusterSnapshotXml(snapshot))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeDbClusterEndpoints(MultivaluedMap<String, String> params) {
        Collection<DbClusterEndpoint> result = service.listDbClusterEndpoints(
                params.getFirst("DBClusterIdentifier"), params.getFirst("DBClusterEndpointIdentifier"));
        XmlBuilder xml = new XmlBuilder().start("DBClusterEndpoints");
        for (DbClusterEndpoint endpoint : result) {
            xml.start("DBClusterEndpoint").raw(dbClusterEndpointInnerXml(endpoint)).end("DBClusterEndpoint");
        }
        xml.end("DBClusterEndpoints");
        return Response.ok(AwsQueryResponse.envelope("DescribeDBClusterEndpoints", AwsNamespaces.RDS, xml.build())).build();
    }

    private Response handleCreateDbClusterEndpoint(MultivaluedMap<String, String> params) {
        try {
            DbClusterEndpoint endpoint = service.createDbClusterEndpoint(
                    params.getFirst("DBClusterEndpointIdentifier"),
                    params.getFirst("DBClusterIdentifier"),
                    params.getFirst("EndpointType"),
                    memberList(params, "StaticMembers"),
                    memberList(params, "ExcludedMembers"),
                    parseTags(params));
            return Response.ok(AwsQueryResponse.envelope("CreateDBClusterEndpoint", AwsNamespaces.RDS,
                    dbClusterEndpointXml(endpoint))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleModifyDbClusterEndpoint(MultivaluedMap<String, String> params) {
        try {
            DbClusterEndpoint endpoint = service.modifyDbClusterEndpoint(
                    params.getFirst("DBClusterEndpointIdentifier"),
                    params.getFirst("EndpointType"),
                    memberList(params, "StaticMembers"),
                    memberList(params, "ExcludedMembers"));
            return Response.ok(AwsQueryResponse.envelope("ModifyDBClusterEndpoint", AwsNamespaces.RDS,
                    dbClusterEndpointXml(endpoint))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDeleteDbClusterEndpoint(MultivaluedMap<String, String> params) {
        try {
            DbClusterEndpoint endpoint = service.getDbClusterEndpoint(params.getFirst("DBClusterEndpointIdentifier"));
            service.deleteDbClusterEndpoint(params.getFirst("DBClusterEndpointIdentifier"));
            return Response.ok(AwsQueryResponse.envelope("DeleteDBClusterEndpoint", AwsNamespaces.RDS,
                    dbClusterEndpointXml(endpoint))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleResetDbParameterGroup(MultivaluedMap<String, String> params) {
        try {
            DbParameterGroup group = service.resetDbParameterGroup(
                    params.getFirst("DBParameterGroupName"),
                    "true".equalsIgnoreCase(params.getFirst("ResetAllParameters")),
                    parameterNames(params));
            String result = new XmlBuilder()
                    .elem("DBParameterGroupName", group.getDbParameterGroupName())
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ResetDBParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleResetDbClusterParameterGroup(MultivaluedMap<String, String> params) {
        try {
            DbClusterParameterGroup group = service.resetDbClusterParameterGroup(
                    params.getFirst("DBClusterParameterGroupName"),
                    "true".equalsIgnoreCase(params.getFirst("ResetAllParameters")),
                    parameterNames(params));
            String result = new XmlBuilder()
                    .elem("DBClusterParameterGroupName", group.getDbClusterParameterGroupName())
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ResetDBClusterParameterGroup", AwsNamespaces.RDS, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribeEvents() {
        String result = new XmlBuilder().start("Events").end("Events").build();
        return Response.ok(AwsQueryResponse.envelope("DescribeEvents", AwsNamespaces.RDS, result)).build();
    }

    private Response handleApplyPendingMaintenanceAction(MultivaluedMap<String, String> params) {
        try {
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
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleDescribePendingMaintenanceActions() {
        String result = new XmlBuilder().start("PendingMaintenanceActions").end("PendingMaintenanceActions").build();
        return Response.ok(AwsQueryResponse.envelope("DescribePendingMaintenanceActions", AwsNamespaces.RDS, result)).build();
    }

    private Response handleStartStopInstance(MultivaluedMap<String, String> params, String action) {
        try {
            DbInstance instance = service.getDbInstance(params.getFirst("DBInstanceIdentifier"));
            return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.RDS, dbInstanceXml(instance))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    private Response handleStartStopCluster(MultivaluedMap<String, String> params, String action) {
        try {
            DbCluster cluster = service.getDbCluster(params.getFirst("DBClusterIdentifier"));
            return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.RDS, dbClusterXml(cluster))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.RDS, e.getHttpStatus());
        }
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String dbInstanceXml(DbInstance i) {
        return new XmlBuilder().start("DBInstance").raw(dbInstanceInnerXml(i)).end("DBInstance").build();
    }

    private String dbInstanceInnerXml(DbInstance i) {
        DbEndpoint ep = i.getEndpoint();
        String engineStr = i.getEngine() != null ? i.getEngine().name() : "";
        String statusStr = i.getStatus() != null ? statusLabel(i.getStatus()) : "available";

        XmlBuilder xml = new XmlBuilder()
                .elem("DBInstanceIdentifier", i.getDbInstanceIdentifier())
                .elem("DBInstanceStatus", statusStr)
                .elem("Engine", engineStr.toLowerCase())
                .elem("EngineVersion", i.getEngineVersion())
                .elem("MasterUsername", i.getMasterUsername());
        if (i.getDbName() != null && !i.getDbName().isBlank()) {
            xml.elem("DBName", i.getDbName());
        }
        xml.elem("DBInstanceClass", i.getDbInstanceClass())
           .elem("AllocatedStorage", i.getAllocatedStorage());
        if (ep != null) {
            xml.start("Endpoint")
               .elem("Address", ep.address())
               .elem("Port", ep.port())
               .end("Endpoint");
        }
        xml.elem("IAMDatabaseAuthenticationEnabled", i.isIamDatabaseAuthenticationEnabled())
           .elem("MultiAZ", i.isMultiAz())
           .elem("StorageType", "gp2")
           .elem("PubliclyAccessible", false)
           .elem("AvailabilityZone", i.getAvailabilityZone() != null ? i.getAvailabilityZone() : config.defaultAvailabilityZone())
           .elem("PreferredMaintenanceWindow", "mon:00:00-mon:03:00")
           .elem("PreferredBackupWindow", "04:00-06:00")
           .raw(vpcSecurityGroupsXml(i))
           .raw(dbParameterGroupsXml(i))
           .raw(dbSubnetGroupXml(dbSubnetGroupForInstance(i)))
           .elem("DbiResourceId", i.getDbiResourceId())
           .elem("DBInstanceArn", i.getDbInstanceArn());
        if (i.getMasterUserSecretArn() != null && !i.getMasterUserSecretArn().isBlank()) {
            xml.start("MasterUserSecret")
                    .elem("SecretArn", i.getMasterUserSecretArn())
                    .elem("SecretStatus", i.getMasterUserSecretStatus() == null ? "active" : i.getMasterUserSecretStatus());
            if (i.getMasterUserSecretKmsKeyId() != null && !i.getMasterUserSecretKmsKeyId().isBlank()) {
                xml.elem("KmsKeyId", i.getMasterUserSecretKmsKeyId());
            }
            xml.end("MasterUserSecret");
        }
        if (i.getDbClusterIdentifier() != null && !i.getDbClusterIdentifier().isBlank()) {
            xml.elem("DBClusterIdentifier", i.getDbClusterIdentifier());
        }
        xml.start("TagList");
        writeTags(xml, i.getTags());
        xml.end("TagList");
        return xml.build();
    }

    private String vpcSecurityGroupsXml(DbInstance i) {
        List<String> groupIds = i.getVpcSecurityGroupIds().isEmpty()
                ? List.of("sg-00000000")
                : i.getVpcSecurityGroupIds();
        XmlBuilder xml = new XmlBuilder().start("VpcSecurityGroups");
        for (String groupId : groupIds) {
            xml.start("VpcSecurityGroupMembership")
                    .elem("VpcSecurityGroupId", groupId)
                    .elem("Status", "active")
                    .end("VpcSecurityGroupMembership");
        }
        return xml.end("VpcSecurityGroups").build();
    }

    private static List<String> vpcSecurityGroupIds(MultivaluedMap<String, String> params) {
        List<String> values = memberList(params, "VpcSecurityGroupIds");
        if (values.isEmpty() && hasMemberKeys(params, "VpcSecurityGroupIds")) {
            throw new AwsException("InvalidParameterValue",
                    "VpcSecurityGroupIds must contain at least one non-empty VpcSecurityGroupId.", 400);
        }
        return values;
    }

    private static String dbParameterGroupsXml(DbInstance instance) {
        String name = dbParameterGroupName(instance);

        XmlBuilder xml = new XmlBuilder().start("DBParameterGroups");
        xml.start("DBParameterGroup")
           .elem("DBParameterGroupName", name)
           .elem("ParameterApplyStatus", "in-sync")
           .end("DBParameterGroup");
        return xml.end("DBParameterGroups").build();
    }

    private static String dbParameterGroupName(DbInstance instance) {
        String name = instance.getParameterGroupName();
        if (name != null && !name.isBlank()) {
            return name;
        }

        String engine = instance.getEngine() != null
                ? instance.getEngine().name().toLowerCase()
                : "unknown";
        return "default." + engine + dbEngineMajorVersion(instance);
    }

    private static String dbEngineMajorVersion(DbInstance instance) {
        String engineVersion = instance.getEngineVersion();
        if ((engineVersion == null || engineVersion.isBlank()) && instance.getEngine() != null) {
            engineVersion = defaultEngineVersion(instance.getEngine().name());
        }
        if (engineVersion == null || engineVersion.isBlank()) {
            return "";
        }

        String trimmed = engineVersion.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? "" : trimmed.substring(0, end);
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

    private String dbClusterXml(DbCluster c) {
        return new XmlBuilder().start("DBCluster").raw(dbClusterInnerXml(c)).end("DBCluster").build();
    }

    private String dbClusterInnerXml(DbCluster c) {
        DbEndpoint ep = c.getEndpoint();
        DbEndpoint readerEp = c.getReaderEndpoint();
        String engineStr = c.getEngine() != null ? c.getEngine().name() : "";
        String statusStr = c.getStatus() != null ? statusLabel(c.getStatus()) : "available";

        XmlBuilder xml = new XmlBuilder()
                .elem("DBClusterIdentifier", c.getDbClusterIdentifier())
                .elem("Status", statusStr)
                .elem("Engine", engineStr.toLowerCase())
                .elem("EngineVersion", c.getEngineVersion())
                .elem("MasterUsername", c.getMasterUsername());
        if (c.getDatabaseName() != null && !c.getDatabaseName().isBlank()) {
            xml.elem("DatabaseName", c.getDatabaseName());
        }
        if (ep != null) {
            xml.elem("Endpoint", ep.address())
               .elem("Port", ep.port());
        }
        if (readerEp != null) {
            xml.elem("ReaderEndpoint", readerEp.address());
        }
        xml.elem("IAMDatabaseAuthenticationEnabled", c.isIamDatabaseAuthenticationEnabled())
           .elem("MultiAZ", c.isMultiAz())
           .elem("AvailabilityZone", c.getAvailabilityZone() != null ? c.getAvailabilityZone() : config.defaultAvailabilityZone())
           .elem("PreferredMaintenanceWindow", "mon:00:00-mon:03:00")
           .elem("PreferredBackupWindow", "04:00-06:00")
           .start("VpcSecurityGroups")
             .start("VpcSecurityGroupMembership")
               .elem("VpcSecurityGroupId", "sg-00000000")
               .elem("Status", "active")
             .end("VpcSecurityGroupMembership")
           .end("VpcSecurityGroups")
           .elem("DBSubnetGroup", c.getDbSubnetGroupName() != null ? c.getDbSubnetGroupName() : "default")
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

    private String paramGroupXml(DbParameterGroup g) {
        return new XmlBuilder().start("DBParameterGroup").raw(paramGroupInnerXml(g)).end("DBParameterGroup").build();
    }

    private String dbSubnetGroupXml(DbSubnetGroup g) {
        return new XmlBuilder().start("DBSubnetGroup").raw(dbSubnetGroupInnerXml(g)).end("DBSubnetGroup").build();
    }

    private String dbSubnetGroupInnerXml(DbSubnetGroup g) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBSubnetGroupName", g.getDbSubnetGroupName())
                .elem("DBSubnetGroupDescription", g.getDescription())
                .elem("VpcId", g.getVpcId() != null ? g.getVpcId() : "vpc-00000000")
                .elem("SubnetGroupStatus", g.getSubnetGroupStatus() != null ? g.getSubnetGroupStatus() : "Complete")
                .elem("DBSubnetGroupArn", g.getDbSubnetGroupArn())
                .start("Subnets");
        for (String subnetId : g.getSubnetIds()) {
            String az = g.getSubnetAvailabilityZones().get(subnetId);
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

    private DbSubnetGroup dbSubnetGroupForInstance(DbInstance instance) {
        String groupName = instance.getDbSubnetGroupName();
        if (groupName == null || groupName.isBlank() || "default".equalsIgnoreCase(groupName)) {
            return fallbackSubnetGroup(instance, "default", "default subnet group");
        }
        return service.getDbSubnetGroup(groupName);
    }

    private DbSubnetGroup fallbackSubnetGroup(DbInstance instance, String name, String description) {
        DbSubnetGroup fallback = new DbSubnetGroup();
        fallback.setDbSubnetGroupName(name);
        fallback.setDescription(description);
        fallback.setVpcId(instance.getVpcId() != null ? instance.getVpcId() : "vpc-00000000");
        fallback.setSubnetGroupStatus("Complete");
        fallback.setDbSubnetGroupArn(subnetGroupArnForInstance(instance, name));
        Map<String, String> zones = instance.getSubnetAvailabilityZones();
        if (!zones.isEmpty()) {
            fallback.setSubnetIds(List.copyOf(zones.keySet()));
            fallback.setSubnetAvailabilityZones(zones);
        } else {
            fallback.setSubnetIds(List.of("subnet-00000000"));
            fallback.setSubnetAvailabilityZones(Map.of("subnet-00000000", config.defaultAvailabilityZone()));
        }
        return fallback;
    }

    private static String subnetGroupArnForInstance(DbInstance instance, String name) {
        String arn = instance.getDbInstanceArn();
        if (arn == null || arn.isBlank()) {
            return null;
        }
        String[] parts = arn.split(":", 6);
        if (parts.length < 6) {
            return null;
        }
        return String.join(":", parts[0], parts[1], parts[2], parts[3], parts[4], "subgrp:" + name);
    }

    private String paramGroupInnerXml(DbParameterGroup g) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBParameterGroupName", g.getDbParameterGroupName())
                .elem("DBParameterGroupFamily", g.getDbParameterGroupFamily())
                .elem("Description", g.getDescription());
        if (g.getDbParameterGroupArn() != null) {
            xml.elem("DBParameterGroupArn", g.getDbParameterGroupArn());
        }
        return xml.build();
    }

    private String clusterParamGroupXml(DbClusterParameterGroup g) {
        return new XmlBuilder().start("DBClusterParameterGroup").raw(clusterParamGroupInnerXml(g)).end("DBClusterParameterGroup").build();
    }

    private String clusterParamGroupInnerXml(DbClusterParameterGroup g) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBClusterParameterGroupName", g.getDbClusterParameterGroupName())
                .elem("DBParameterGroupFamily", g.getDbParameterGroupFamily())
                .elem("Description", g.getDescription());
        if (g.getDbClusterParameterGroupArn() != null) {
            xml.elem("DBClusterParameterGroupArn", g.getDbClusterParameterGroupArn());
        }
        return xml.build();
    }

    private String dbSnapshotXml(DbSnapshot snapshot) {
        return new XmlBuilder().start("DBSnapshot").raw(dbSnapshotInnerXml(snapshot)).end("DBSnapshot").build();
    }

    private String dbSnapshotInnerXml(DbSnapshot snapshot) {
        return new XmlBuilder()
                .elem("DBSnapshotIdentifier", snapshot.getDbSnapshotIdentifier())
                .elem("DBInstanceIdentifier", snapshot.getDbInstanceIdentifier())
                .elem("Status", snapshot.getStatus())
                .elem("Engine", snapshot.getEngine())
                .elem("SnapshotType", snapshot.getSnapshotType())
                .elem("DBSnapshotArn", snapshot.getDbSnapshotArn())
                .build();
    }

    private String dbClusterSnapshotXml(DbClusterSnapshot snapshot) {
        return new XmlBuilder().start("DBClusterSnapshot").raw(dbClusterSnapshotInnerXml(snapshot)).end("DBClusterSnapshot").build();
    }

    private String dbClusterSnapshotInnerXml(DbClusterSnapshot snapshot) {
        return new XmlBuilder()
                .elem("DBClusterSnapshotIdentifier", snapshot.getDbClusterSnapshotIdentifier())
                .elem("DBClusterIdentifier", snapshot.getDbClusterIdentifier())
                .elem("Status", snapshot.getStatus())
                .elem("Engine", snapshot.getEngine())
                .elem("SnapshotType", snapshot.getSnapshotType())
                .elem("DBClusterSnapshotArn", snapshot.getDbClusterSnapshotArn())
                .build();
    }

    private String dbClusterEndpointXml(DbClusterEndpoint endpoint) {
        return new XmlBuilder().start("DBClusterEndpoint").raw(dbClusterEndpointInnerXml(endpoint)).end("DBClusterEndpoint").build();
    }

    private String dbClusterEndpointInnerXml(DbClusterEndpoint endpoint) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DBClusterEndpointIdentifier", endpoint.getDbClusterEndpointIdentifier())
                .elem("DBClusterEndpointArn", endpoint.getDbClusterEndpointArn())
                .elem("DBClusterIdentifier", endpoint.getDbClusterIdentifier())
                .elem("Endpoint", endpoint.getEndpoint())
                .elem("Status", endpoint.getStatus())
                .elem("EndpointType", endpoint.getEndpointType())
                .elem("CustomEndpointType", endpoint.getCustomEndpointType())
                .start("StaticMembers");
        for (String member : endpoint.getStaticMembers()) {
            xml.elem("member", member);
        }
        xml.end("StaticMembers").start("ExcludedMembers");
        for (String member : endpoint.getExcludedMembers()) {
            xml.elem("member", member);
        }
        xml.end("ExcludedMembers");
        return xml.build();
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
        List<String> names = new java.util.ArrayList<>();
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

    /**
     * AWS rejects {@code /}, {@code "}, {@code @}, space, and non-printable
     * characters on {@code MasterUserPassword} before provisioning.
     */
    private static void validateMasterUserPassword(String password) {
        if (password == null || password.isBlank()) {
            return;
        }
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c == '/' || c == '"' || c == '@' || c == ' ' || c < 0x20 || c > 0x7E) {
                throw new AwsException("InvalidParameterValue",
                        "The parameter MasterUserPassword is not a valid password. Only printable ASCII characters besides '/', '@', '\"', ' ' may be used.",
                        400);
            }
        }
    }

    /**
     * AWS backup retention is 0–35 days on instances and 1–35 on clusters.
     */
    private static void validateBackupRetentionPeriod(String raw, boolean cluster) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        int days;
        try {
            days = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid backup retention period: " + raw + ".", 400);
        }
        int min = cluster ? 1 : 0;
        if (days < min || days > 35) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid backup retention period: " + days + ". Valid values are " + min + "-35.",
                    400);
        }
    }

    private String statusLabel(DbInstanceStatus status) {
        return switch (status) {
            case CREATING -> "creating";
            case AVAILABLE -> "available";
            case DELETING -> "deleting";
            case REBOOTING -> "rebooting";
            case MODIFYING -> "modifying";
        };
    }

    /**
     * Extracts the first value for a named filter from RDS Query API encoded params:
     * {@code Filters.Filter.N.Name=filterName} / {@code Filters.Filter.N.Values.Value.1=value}.
     * Returns null if no matching filter is present.
     */
    private static String extractRdsFilterValue(MultivaluedMap<String, String> params, String filterName) {
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

    private static List<String> memberList(MultivaluedMap<String, String> params, String baseName) {
        return params.keySet().stream()
                .filter(key -> key.matches(memberKeyRegex(baseName)))
                .sorted(java.util.Comparator.comparingInt(RdsQueryHandler::numericSuffix))
                .map(params::getFirst)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static boolean hasMemberKeys(MultivaluedMap<String, String> params, String baseName) {
        return params.keySet().stream().anyMatch(key -> key.matches(memberKeyRegex(baseName)));
    }

    private static String memberKeyRegex(String baseName) {
        String quoted = java.util.regex.Pattern.quote(baseName);
        return switch (baseName) {
            case "SubnetIds" -> quoted + "(\\.member|\\.SubnetIdentifier)?\\.\\d+";
            case "VpcSecurityGroupIds" -> quoted + "(\\.member|\\.VpcSecurityGroupId)?\\.\\d+";
            default -> quoted + "(\\.member)?\\.\\d+";
        };
    }

    private static int numericSuffix(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot == key.length() - 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(key.substring(dot + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
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

    private static int parseIntSafe(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String defaultEngineVersion(String engine) {
        if (engine == null) {
            return "16.3";
        }
        return switch (engine.toLowerCase()) {
            case "postgres", "aurora-postgresql" -> "16.3";
            case "mysql", "aurora-mysql", "aurora" -> "8.0.36";
            case "mariadb" -> "11.2";
            default -> "1.0";
        };
    }
}
