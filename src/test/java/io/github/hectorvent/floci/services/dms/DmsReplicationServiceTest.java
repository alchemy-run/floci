package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dms.model.DmsConnection;
import io.github.hectorvent.floci.services.dms.model.DmsEndpoint;
import io.github.hectorvent.floci.services.dms.model.DmsEvent;
import io.github.hectorvent.floci.services.dms.model.ReplicationInstance;
import io.github.hectorvent.floci.services.dms.model.ResourceTag;
import io.github.hectorvent.floci.services.dms.model.SchemaRefresh;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoints, replication instances, connection tests, schema refreshes, events, and the
 * task/replication operations Floci answers with typed faults.
 */
class DmsReplicationServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String VPC_ID = "vpc-0dms";

    private final ObjectMapper mapper = new ObjectMapper();
    private DmsService service;
    private DmsConnectivityProbe probe;
    private DmsEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("dms"), anyString(), any(TypeReference.class)))
                .thenAnswer(invocation -> (AccountAwareStorageBackend) AccountAwareStorageBackend.inMemory(ACCOUNT_ID));

        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.describeSubnets(eq(REGION), anyList(), anyMap())).thenAnswer(invocation -> {
            List<String> requested = invocation.getArgument(1);
            return requested.stream().map(DmsReplicationServiceTest::subnet).filter(Objects::nonNull).toList();
        });
        when(ec2Service.describeSecurityGroups(eq(REGION), anyList(), anyList(), anyMap())).thenAnswer(invocation -> {
            List<String> requested = invocation.getArgument(1);
            return requested.stream().map(DmsReplicationServiceTest::securityGroup).filter(Objects::nonNull).toList();
        });
        when(ec2Service.getSecurityGroupsForVpc(eq(REGION), eq(VPC_ID), anyMap()))
                .thenReturn(List.of(securityGroup("sg-default")));
        when(ec2Service.describeAvailabilityZones(REGION)).thenReturn(List.of(
                Map.of("zoneName", "us-east-1a"), Map.of("zoneName", "us-east-1b")));

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0) + ":" + invocation.getArgument(1) + ":" + ACCOUNT_ID
                        + ":" + invocation.getArgument(2));
        probe = mock(DmsConnectivityProbe.class);
        publisher = mock(DmsEventPublisher.class);
        service = new DmsService(storageFactory, ec2Service, regionResolver, probe, publisher,
                new InlineExecutorService());
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    @Test
    void createEndpointStoresUppercaseTypeAndAnEndpointArn() {
        DmsEndpoint endpoint = service.createEndpoint(mysqlSource("Source-DB"), REGION);

        assertEquals("source-db", endpoint.getEndpointIdentifier());
        assertEquals("SOURCE", endpoint.getEndpointType());
        assertEquals("mysql", endpoint.getEngineName());
        assertEquals("active", endpoint.getStatus());
        assertEquals("none", endpoint.getSslMode());
        assertTrue(endpoint.getEndpointArn().startsWith("arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":endpoint:"));
        assertEquals(26, endpoint.getEndpointArn().substring(endpoint.getEndpointArn().lastIndexOf(':') + 1).length());
    }

    @Test
    void createEndpointHonoursResourceIdentifierInTheArn() {
        ObjectNode request = mysqlSource("named");
        request.put("ResourceIdentifier", "Example-App-ARN1");

        DmsEndpoint endpoint = service.createEndpoint(request, REGION);

        assertEquals("arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":endpoint:Example-App-ARN1", endpoint.getEndpointArn());
    }

    @Test
    void createEndpointRejectsDuplicateIdentifier() {
        service.createEndpoint(mysqlSource("dup"), REGION);

        assertFault("ResourceAlreadyExistsFault", () -> service.createEndpoint(mysqlSource("DUP"), REGION));
    }

    @Test
    void createEndpointRejectsAnIdentifierWithConsecutiveHyphens() {
        assertFault("InvalidParameterValueException", () -> service.createEndpoint(mysqlSource("bad--id"), REGION));
    }

    @Test
    void createEndpointRejectsATargetOnlyEngineAsASource() {
        ObjectNode request = mysqlSource("dynamo-source");
        request.put("EngineName", "dynamodb");

        assertFault("InvalidParameterValueException", () -> service.createEndpoint(request, REGION));
    }

    @Test
    void createEndpointRejectsAnUnknownEngine() {
        ObjectNode request = mysqlSource("unknown-engine");
        request.put("EngineName", "cassandra");

        assertFault("InvalidParameterValueException", () -> service.createEndpoint(request, REGION));
    }

    @Test
    void describeEndpointsFiltersByIdAndFaultsOnNoMatch() {
        service.createEndpoint(mysqlSource("first"), REGION);
        service.createEndpoint(mysqlSource("second"), REGION);

        List<DmsEndpoint> matched = service.describeEndpoints(filter("endpoint-id", "second"), REGION).items();
        assertEquals(List.of("second"), matched.stream().map(DmsEndpoint::getEndpointIdentifier).toList());
        assertEquals(2, service.describeEndpoints(mapper.createObjectNode(), REGION).items().size());
        assertFault("ResourceNotFoundFault",
                () -> service.describeEndpoints(filter("endpoint-id", "absent"), REGION));
    }

    @Test
    void describeEndpointsRejectsAnUndocumentedFilter() {
        assertFault("InvalidParameterValueException",
                () -> service.describeEndpoints(filter("server-name", "x"), REGION));
    }

    @Test
    void describeEndpointsIsEmptyWithoutFilters() {
        assertTrue(service.describeEndpoints(mapper.createObjectNode(), REGION).items().isEmpty());
    }

    @Test
    void modifyEndpointKeepsOmittedMembersAndTheArn() {
        DmsEndpoint created = service.createEndpoint(mysqlSource("keep"), REGION);
        ObjectNode modify = mapper.createObjectNode();
        modify.put("EndpointArn", created.getEndpointArn());
        modify.put("Port", 3307);
        modify.put("Username", "readonly");

        DmsEndpoint modified = service.modifyEndpoint(modify, REGION);

        assertEquals(created.getEndpointArn(), modified.getEndpointArn());
        assertEquals(3307, modified.getPort());
        assertEquals("readonly", modified.getUsername());
        assertEquals("secret", modified.getPassword());
        assertEquals("db.example.com", modified.getServerName());
    }

    @Test
    void modifyEndpointMergesSettingsUnlessExactSettingsIsSet() {
        ObjectNode create = mysqlSource("settings");
        create.putObject("MySQLSettings").put("EventsPollInterval", 5).put("ServerTimezone", "UTC");
        DmsEndpoint created = service.createEndpoint(create, REGION);

        ObjectNode merge = mapper.createObjectNode();
        merge.put("EndpointArn", created.getEndpointArn());
        merge.putObject("MySQLSettings").put("EventsPollInterval", 10);
        DmsEndpoint merged = service.modifyEndpoint(merge, REGION);
        assertEquals(10, merged.getSettings().get("MySQLSettings").get("EventsPollInterval").intValue());
        assertEquals("UTC", merged.getSettings().get("MySQLSettings").get("ServerTimezone").textValue());

        ObjectNode exact = mapper.createObjectNode();
        exact.put("EndpointArn", created.getEndpointArn());
        exact.put("ExactSettings", true);
        exact.putObject("MySQLSettings").put("EventsPollInterval", 15);
        DmsEndpoint replaced = service.modifyEndpoint(exact, REGION);
        assertEquals(15, replaced.getSettings().get("MySQLSettings").get("EventsPollInterval").intValue());
        assertNull(replaced.getSettings().get("MySQLSettings").get("ServerTimezone"));
    }

    @Test
    void modifyEndpointRejectsRenamingOntoAnExistingIdentifier() {
        service.createEndpoint(mysqlSource("taken"), REGION);
        DmsEndpoint other = service.createEndpoint(mysqlSource("other"), REGION);
        ObjectNode modify = mapper.createObjectNode();
        modify.put("EndpointArn", other.getEndpointArn());
        modify.put("EndpointIdentifier", "taken");

        assertFault("ResourceAlreadyExistsFault", () -> service.modifyEndpoint(modify, REGION));
    }

    @Test
    void modifyAndDeleteOfAnUnknownEndpointFault() {
        ObjectNode request = mapper.createObjectNode();
        request.put("EndpointArn", "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":endpoint:NOPE");

        assertFault("ResourceNotFoundFault", () -> service.modifyEndpoint(request, REGION));
        assertFault("ResourceNotFoundFault", () -> service.deleteEndpoint(request, REGION));
    }

    @Test
    void deleteEndpointReportsDeletingAndRemovesIt() {
        DmsEndpoint created = service.createEndpoint(mysqlSource("doomed"), REGION);
        ObjectNode request = mapper.createObjectNode();
        request.put("EndpointArn", created.getEndpointArn());

        assertEquals("deleting", service.deleteEndpoint(request, REGION).getStatus());
        assertFault("ResourceNotFoundFault",
                () -> service.describeEndpoints(filter("endpoint-arn", created.getEndpointArn()), REGION));
    }

    @Test
    void endpointTagsAreAddressableByEndpointArn() {
        ObjectNode create = mysqlSource("tagged");
        create.putArray("Tags").addObject().put("Key", "team").put("Value", "data");
        DmsEndpoint created = service.createEndpoint(create, REGION);

        ObjectNode add = mapper.createObjectNode();
        add.put("ResourceArn", created.getEndpointArn());
        add.putArray("Tags").addObject().put("Key", "team").put("Value", "platform");
        service.addTagsToResource(add, REGION);

        assertEquals(Map.of("team", "platform"), tagsOf(created.getEndpointArn()));
    }

    @Test
    void describeEndpointSettingsCataloguesMysqlAndIsHonestAboutOtherEngines() {
        ObjectNode mysql = mapper.createObjectNode().put("EngineName", "mysql");
        assertFalse(service.describeEndpointSettings(mysql).items().isEmpty());

        ObjectNode oracle = mapper.createObjectNode().put("EngineName", "oracle");
        assertFault("InvalidParameterValueException", () -> service.describeEndpointSettings(oracle));
        ObjectNode unknown = mapper.createObjectNode().put("EngineName", "cassandra");
        assertFault("InvalidParameterValueException", () -> service.describeEndpointSettings(unknown));
    }

    // ── Replication instances ───────────────────────────────────────────────

    @Test
    void createInstanceReportsCreatingThenSettlesAvailableOnDescribe() {
        createGroup("grp");
        ReplicationInstance created = service.createReplicationInstance(instanceRequest("Inst", "grp"), REGION);

        assertEquals("creating", created.getStatus());
        assertEquals("inst", created.getReplicationInstanceIdentifier());
        assertTrue(created.getReplicationInstanceArn().startsWith("arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":rep:"));
        assertEquals(20, created.getAllocatedStorage());
        assertEquals("us-east-1a", created.getAvailabilityZone());
        assertEquals(List.of("sg-default"), created.getVpcSecurityGroupIds());
        assertEquals(VPC_ID, created.getSubnetGroup().getVpcId());

        ReplicationInstance described = service.describeReplicationInstances(
                filter("replication-instance-id", "inst"), REGION).items().getFirst();
        assertEquals("available", described.getStatus());
        verify(publisher).publishInstanceEvent(eq(DmsInstanceEvent.CREATION_STARTED),
                eq(created.getReplicationInstanceArn()), eq("inst"), eq(REGION));
        verify(publisher).publishInstanceEvent(eq(DmsInstanceEvent.CREATION_FINISHED),
                eq(created.getReplicationInstanceArn()), eq("inst"), eq(REGION));
    }

    @Test
    void createInstanceDefaultsStorageToTheClassAllowance() {
        createGroup("grp");
        ObjectNode request = instanceRequest("defaults", "grp");
        request.remove("AllocatedStorage");

        assertEquals(50, service.createReplicationInstance(request, REGION).getAllocatedStorage());
    }

    @Test
    void createInstanceRejectsAnUnknownSubnetGroup() {
        assertFault("ResourceNotFoundFault",
                () -> service.createReplicationInstance(instanceRequest("orphan", "missing-group"), REGION));
    }

    @Test
    void createInstanceRejectsAnUnknownClass() {
        createGroup("grp");
        ObjectNode request = instanceRequest("big", "grp");
        request.put("ReplicationInstanceClass", "dms.x99.huge");

        assertFault("InvalidParameterValueException", () -> service.createReplicationInstance(request, REGION));
    }

    @Test
    void createInstanceRejectsASecurityGroupFromAnotherVpc() {
        createGroup("grp");
        ObjectNode request = instanceRequest("foreign-sg", "grp");
        request.putArray("VpcSecurityGroupIds").add("sg-other-vpc");

        assertFault("InvalidParameterValueException", () -> service.createReplicationInstance(request, REGION));
    }

    @Test
    void createInstanceRejectsAnUnknownSecurityGroup() {
        createGroup("grp");
        ObjectNode request = instanceRequest("missing-sg", "grp");
        request.putArray("VpcSecurityGroupIds").add("sg-missing");

        assertFault("InvalidParameterValueException", () -> service.createReplicationInstance(request, REGION));
    }

    @Test
    void createInstanceRejectsAZoneOutsideTheGroup() {
        createGroup("grp");
        ObjectNode request = instanceRequest("zone", "grp");
        request.put("AvailabilityZone", "us-east-1c");

        assertFault("InvalidParameterValueException", () -> service.createReplicationInstance(request, REGION));
    }

    @Test
    void multiAzInstanceGetsASecondaryZone() {
        createGroup("grp");
        ObjectNode request = instanceRequest("ha", "grp");
        request.put("MultiAZ", true);

        ReplicationInstance created = service.createReplicationInstance(request, REGION);

        assertEquals("us-east-1a", created.getAvailabilityZone());
        assertEquals("us-east-1b", created.getSecondaryAvailabilityZone());
    }

    @Test
    void deleteOfANonexistentInstanceArnFaultsWithResourceNotFound() {
        ObjectNode request = mapper.createObjectNode();
        request.put("ReplicationInstanceArn", "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":rep:AAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertFault("ResourceNotFoundFault", () -> service.deleteReplicationInstance(request, REGION));
    }

    @Test
    void deleteInstanceReportsDeletingAndRemovesIt() {
        createGroup("grp");
        ReplicationInstance created = service.createReplicationInstance(instanceRequest("gone", "grp"), REGION);
        ObjectNode request = mapper.createObjectNode().put("ReplicationInstanceArn", created.getReplicationInstanceArn());

        assertEquals("deleting", service.deleteReplicationInstance(request, REGION).getStatus());
        assertFault("ResourceNotFoundFault", () -> service.describeReplicationInstances(
                filter("replication-instance-arn", created.getReplicationInstanceArn()), REGION));
        verify(publisher).publishInstanceEvent(eq(DmsInstanceEvent.DELETION_FINISHED),
                eq(created.getReplicationInstanceArn()), eq("gone"), eq(REGION));
    }

    @Test
    void modifyWithApplyImmediatelyAppliesWhenTheInstanceSettles() {
        createGroup("grp");
        ReplicationInstance created = service.createReplicationInstance(instanceRequest("mod", "grp"), REGION);
        ObjectNode modify = mapper.createObjectNode();
        modify.put("ReplicationInstanceArn", created.getReplicationInstanceArn());
        modify.put("ReplicationInstanceClass", "dms.t3.small");
        modify.put("AllocatedStorage", 30);
        modify.put("ApplyImmediately", true);

        ReplicationInstance modifying = service.modifyReplicationInstance(modify, REGION);
        assertEquals("modifying", modifying.getStatus());
        assertEquals("dms.t3.small", modifying.getPendingReplicationInstanceClass());
        assertEquals(30, modifying.getPendingAllocatedStorage());

        ReplicationInstance settled = describeOnly("mod");
        assertEquals("available", settled.getStatus());
        assertEquals("dms.t3.small", settled.getReplicationInstanceClass());
        assertEquals(30, settled.getAllocatedStorage());
        assertFalse(settled.hasPendingModifications());
    }

    @Test
    void modifyWithoutApplyImmediatelyLeavesTheChangePending() {
        createGroup("grp");
        ReplicationInstance created = service.createReplicationInstance(instanceRequest("deferred", "grp"), REGION);
        ObjectNode modify = mapper.createObjectNode();
        modify.put("ReplicationInstanceArn", created.getReplicationInstanceArn());
        modify.put("ReplicationInstanceClass", "dms.t3.small");

        service.modifyReplicationInstance(modify, REGION);

        ReplicationInstance described = describeOnly("deferred");
        assertEquals("available", described.getStatus());
        assertEquals("dms.t3.micro", described.getReplicationInstanceClass());
        assertEquals("dms.t3.small", described.getPendingReplicationInstanceClass());
    }

    @Test
    void modifyRejectsShrinkingStorageWithoutTouchingTheInstance() {
        createGroup("grp");
        ReplicationInstance created = service.createReplicationInstance(instanceRequest("shrink", "grp"), REGION);
        ObjectNode modify = mapper.createObjectNode();
        modify.put("ReplicationInstanceArn", created.getReplicationInstanceArn());
        modify.put("ReplicationInstanceClass", "dms.t3.small");
        modify.put("AllocatedStorage", 10);
        modify.put("ApplyImmediately", true);

        assertFault("InvalidParameterValueException", () -> service.modifyReplicationInstance(modify, REGION));
        ReplicationInstance described = describeOnly("shrink");
        assertEquals("dms.t3.micro", described.getReplicationInstanceClass());
        assertNull(described.getPendingReplicationInstanceClass());
    }

    @Test
    void rebootTransitionsThroughRebootingAndRejectsFailoverOnSingleAz() {
        createGroup("grp");
        ReplicationInstance created = service.createReplicationInstance(instanceRequest("boot", "grp"), REGION);
        ObjectNode reboot = mapper.createObjectNode().put("ReplicationInstanceArn", created.getReplicationInstanceArn());

        assertEquals("rebooting", service.rebootReplicationInstance(reboot, REGION).getStatus());
        assertEquals("available", describeOnly("boot").getStatus());

        reboot.put("ForceFailover", true);
        assertFault("InvalidParameterCombinationException", () -> service.rebootReplicationInstance(reboot, REGION));
    }

    @Test
    void instanceTagsAreAddressableByInstanceArn() {
        createGroup("grp");
        ObjectNode request = instanceRequest("tags", "grp");
        request.putArray("Tags").addObject().put("Key", "env").put("Value", "test");
        ReplicationInstance created = service.createReplicationInstance(request, REGION);

        ObjectNode remove = mapper.createObjectNode();
        remove.put("ResourceArn", created.getReplicationInstanceArn());
        remove.putArray("TagKeys").add("env");
        assertEquals(Map.of("env", "test"), tagsOf(created.getReplicationInstanceArn()));
        service.removeTagsFromResource(remove, REGION);
        assertEquals(Map.of(), tagsOf(created.getReplicationInstanceArn()));
    }

    @Test
    void describeEventsReportsTheCreationHistory() {
        createGroup("grp");
        service.createReplicationInstance(instanceRequest("evented", "grp"), REGION);
        describeOnly("evented");

        ObjectNode request = mapper.createObjectNode();
        request.put("SourceType", "replication-instance");
        request.put("Duration", 60);
        List<DmsEvent> events = service.describeEvents(request, REGION).items();

        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> event.eventCategories().equals(List.of("creation"))));
        assertTrue(events.stream().allMatch(event -> "evented".equals(event.sourceIdentifier())));
    }

    @Test
    void describeEventsRejectsAnUnknownSourceType() {
        ObjectNode request = mapper.createObjectNode().put("SourceType", "endpoint");

        assertFault("InvalidParameterValueException", () -> service.describeEvents(request, REGION));
    }

    @Test
    void orderableInstancesListEveryClassForEveryVersion() {
        List<DmsService.OrderableInstance> orderable = service.orderableReplicationInstances(REGION);

        assertEquals(DmsCatalog.INSTANCE_CLASSES.size() * DmsCatalog.ENGINE_VERSIONS.size(), orderable.size());
        assertTrue(orderable.stream().anyMatch(entry -> "dms.t3.micro".equals(entry.instanceClass())
                && entry.availabilityZones().equals(List.of("us-east-1a", "us-east-1b"))));
    }

    // ── Subnet groups in use ────────────────────────────────────────────────

    @Test
    void subnetGroupInUseCannotBeDeletedUntilItsInstanceIsGone() {
        createGroup("busy");
        ReplicationInstance created = service.createReplicationInstance(instanceRequest("occupant", "busy"), REGION);
        ObjectNode deleteGroup = mapper.createObjectNode().put("ReplicationSubnetGroupIdentifier", "busy");

        assertFault("InvalidResourceStateFault", () -> service.deleteReplicationSubnetGroup(deleteGroup, REGION));

        service.deleteReplicationInstance(
                mapper.createObjectNode().put("ReplicationInstanceArn", created.getReplicationInstanceArn()), REGION);
        service.deleteReplicationSubnetGroup(deleteGroup, REGION);
    }

    @Test
    void modifySubnetGroupReplacesSubnetsAndDescription() {
        createGroup("swap");
        ObjectNode modify = mapper.createObjectNode();
        modify.put("ReplicationSubnetGroupIdentifier", "swap");
        modify.put("ReplicationSubnetGroupDescription", "updated");
        modify.putArray("SubnetIds").add("subnet-a").add("subnet-c");

        var modified = service.modifyReplicationSubnetGroup(modify, REGION);

        assertEquals(List.of("subnet-a", "subnet-c"), modified.getSubnetIds());
        assertEquals("updated", modified.getReplicationSubnetGroupDescription());
    }

    @Test
    void modifySubnetGroupKeepsTheDescriptionWhenOmitted() {
        createGroup("keep-desc");
        ObjectNode modify = mapper.createObjectNode();
        modify.put("ReplicationSubnetGroupIdentifier", "keep-desc");
        modify.putArray("SubnetIds").add("subnet-a").add("subnet-c");

        assertEquals("example", service.modifyReplicationSubnetGroup(modify, REGION)
                .getReplicationSubnetGroupDescription());
    }

    @Test
    void modifySubnetGroupCannotDropTheZoneOfAPlacedInstance() {
        createGroup("placed");
        service.createReplicationInstance(instanceRequest("pinned", "placed"), REGION);
        ObjectNode modify = mapper.createObjectNode();
        modify.put("ReplicationSubnetGroupIdentifier", "placed");
        modify.putArray("SubnetIds").add("subnet-b").add("subnet-c");

        assertFault("SubnetAlreadyInUse", () -> service.modifyReplicationSubnetGroup(modify, REGION));
    }

    @Test
    void modifySubnetGroupOfAnUnknownGroupFaults() {
        ObjectNode modify = mapper.createObjectNode();
        modify.put("ReplicationSubnetGroupIdentifier", "absent");
        modify.putArray("SubnetIds").add("subnet-a").add("subnet-b");

        assertFault("ResourceNotFoundFault", () -> service.modifyReplicationSubnetGroup(modify, REGION));
    }

    @Test
    void modifySubnetGroupRejectsAnUnknownSubnet() {
        createGroup("bad-modify");
        ObjectNode modify = mapper.createObjectNode();
        modify.put("ReplicationSubnetGroupIdentifier", "bad-modify");
        modify.putArray("SubnetIds").add("subnet-a").add("subnet-missing");

        assertFault("InvalidSubnet", () -> service.modifyReplicationSubnetGroup(modify, REGION));
    }

    // ── Connections and schemas ─────────────────────────────────────────────

    @Test
    void testConnectionRecordsTheProbeFailureHonestly() {
        when(probe.testConnection(any())).thenReturn(
                new DmsConnectivityProbe.Result(false, "Communications link failure", List.of()));
        createGroup("grp");
        ReplicationInstance instance = service.createReplicationInstance(instanceRequest("tester", "grp"), REGION);
        DmsEndpoint endpoint = service.createEndpoint(mysqlSource("probed"), REGION);

        DmsConnection started = service.testConnection(connectionRequest(instance, endpoint), REGION);
        assertEquals("tester", started.getReplicationInstanceIdentifier());

        DmsConnection recorded = service.describeConnections(
                filter("endpoint-arn", endpoint.getEndpointArn()), REGION).items().getFirst();
        assertEquals("failed", recorded.getStatus());
        assertEquals("Communications link failure", recorded.getLastFailureMessage());
    }

    @Test
    void testConnectionRecordsASuccessfulLogin() {
        when(probe.testConnection(any())).thenReturn(new DmsConnectivityProbe.Result(true, null, List.of()));
        createGroup("grp");
        ReplicationInstance instance = service.createReplicationInstance(instanceRequest("ok", "grp"), REGION);
        DmsEndpoint endpoint = service.createEndpoint(mysqlSource("reachable"), REGION);

        service.testConnection(connectionRequest(instance, endpoint), REGION);

        DmsConnection recorded = service.describeConnections(mapper.createObjectNode(), REGION).items().getFirst();
        assertEquals("successful", recorded.getStatus());
        assertNull(recorded.getLastFailureMessage());
    }

    @Test
    void testConnectionToAnUnknownInstanceFaults() {
        DmsEndpoint endpoint = service.createEndpoint(mysqlSource("lonely"), REGION);
        ObjectNode request = mapper.createObjectNode();
        request.put("ReplicationInstanceArn", "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":rep:MISSING");
        request.put("EndpointArn", endpoint.getEndpointArn());

        assertFault("ResourceNotFoundFault", () -> service.testConnection(request, REGION));
    }

    @Test
    void describeConnectionsIsEmptyUnfilteredAndFaultsOnAFilterMiss() {
        assertTrue(service.describeConnections(mapper.createObjectNode(), REGION).items().isEmpty());
        assertFault("ResourceNotFoundFault",
                () -> service.describeConnections(filter("endpoint-arn", "arn:aws:dms:x"), REGION));
    }

    @Test
    void deletingTheEndpointDropsItsConnections() {
        when(probe.testConnection(any())).thenReturn(new DmsConnectivityProbe.Result(true, null, List.of()));
        createGroup("grp");
        ReplicationInstance instance = service.createReplicationInstance(instanceRequest("dropper", "grp"), REGION);
        DmsEndpoint endpoint = service.createEndpoint(mysqlSource("dropped"), REGION);
        service.testConnection(connectionRequest(instance, endpoint), REGION);

        service.deleteEndpoint(mapper.createObjectNode().put("EndpointArn", endpoint.getEndpointArn()), REGION);

        assertTrue(service.describeConnections(mapper.createObjectNode(), REGION).items().isEmpty());
    }

    @Test
    void schemasAreUnavailableUntilARefreshSucceeds() {
        when(probe.listSchemas(any())).thenReturn(
                new DmsConnectivityProbe.Result(true, null, List.of("app", "information_schema")));
        createGroup("grp");
        ReplicationInstance instance = service.createReplicationInstance(instanceRequest("refresher", "grp"), REGION);
        DmsEndpoint endpoint = service.createEndpoint(mysqlSource("schemas"), REGION);
        ObjectNode byEndpoint = mapper.createObjectNode().put("EndpointArn", endpoint.getEndpointArn());

        assertFault("InvalidResourceStateFault", () -> service.describeSchemas(byEndpoint, REGION));
        assertFault("ResourceNotFoundFault", () -> service.describeRefreshSchemasStatus(byEndpoint, REGION));

        SchemaRefresh started = service.refreshSchemas(connectionRequest(instance, endpoint), REGION);
        assertEquals(instance.getReplicationInstanceArn(), started.getReplicationInstanceArn());

        SchemaRefresh status = service.describeRefreshSchemasStatus(byEndpoint, REGION);
        assertEquals("successful", status.getStatus());
        assertEquals(List.of("app", "information_schema"), service.describeSchemas(byEndpoint, REGION).items());
    }

    @Test
    void aFailedRefreshLeavesSchemasUnavailableWithTheReason() {
        when(probe.listSchemas(any())).thenReturn(new DmsConnectivityProbe.Result(false, "Access denied", List.of()));
        createGroup("grp");
        ReplicationInstance instance = service.createReplicationInstance(instanceRequest("denied", "grp"), REGION);
        DmsEndpoint endpoint = service.createEndpoint(mysqlSource("denied-endpoint"), REGION);
        ObjectNode byEndpoint = mapper.createObjectNode().put("EndpointArn", endpoint.getEndpointArn());

        service.refreshSchemas(connectionRequest(instance, endpoint), REGION);

        SchemaRefresh status = service.describeRefreshSchemasStatus(byEndpoint, REGION);
        assertEquals("failed", status.getStatus());
        assertEquals("Access denied", status.getLastFailureMessage());
        assertFault("InvalidResourceStateFault", () -> service.describeSchemas(byEndpoint, REGION));
    }

    @Test
    void schemaOperationsOnAnUnknownEndpointFault() {
        ObjectNode byEndpoint = mapper.createObjectNode()
                .put("EndpointArn", "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":endpoint:MISSING");

        assertFault("ResourceNotFoundFault", () -> service.describeSchemas(byEndpoint, REGION));
        assertFault("ResourceNotFoundFault", () -> service.describeRefreshSchemasStatus(byEndpoint, REGION));
    }

    // ── Tasks and serverless replications ───────────────────────────────────

    @Test
    void taskOperationsAnswerResourceNotFoundForATaskThatDoesNotExist() {
        String taskArn = "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":task:AAAAAAAAAAAAAAAAAAAAAAAAAA";
        ObjectNode start = mapper.createObjectNode();
        start.put("ReplicationTaskArn", taskArn);
        start.put("StartReplicationTaskType", "start-replication");
        ObjectNode byTask = mapper.createObjectNode().put("ReplicationTaskArn", taskArn);
        ObjectNode reload = byTask.deepCopy();
        reload.putArray("TablesToReload").addObject().put("SchemaName", "public").put("TableName", "t");

        assertFault("ResourceNotFoundFault", () -> service.startReplicationTask(start));
        assertFault("ResourceNotFoundFault", () -> service.stopReplicationTask(byTask));
        assertFault("ResourceNotFoundFault", () -> service.describeTableStatistics(byTask));
        assertFault("ResourceNotFoundFault", () -> service.reloadTables(reload));
    }

    @Test
    void startReplicationTaskRejectsAnUnknownStartType() {
        ObjectNode start = mapper.createObjectNode();
        start.put("ReplicationTaskArn", "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":task:X");
        start.put("StartReplicationTaskType", "sideways");

        assertFault("InvalidParameterValueException", () -> service.startReplicationTask(start));
    }

    @Test
    void describeReplicationTasksIsEmptyUnfilteredAndFaultsOnAFilter() {
        assertTrue(service.describeReplicationTasks(mapper.createObjectNode()).items().isEmpty());
        assertFault("ResourceNotFoundFault",
                () -> service.describeReplicationTasks(filter("replication-task-id", "absent")));
    }

    @Test
    void replicationOperationsAnswerResourceNotFoundForAConfigThatDoesNotExist() {
        String configArn = "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":replication-config:AAAAAAAAAAAAAAAAAAAAAAAAAA";
        ObjectNode start = mapper.createObjectNode();
        start.put("ReplicationConfigArn", configArn);
        start.put("StartReplicationType", "start-replication");
        ObjectNode stop = mapper.createObjectNode().put("ReplicationConfigArn", configArn);

        assertFault("ResourceNotFoundFault", () -> service.startReplication(start));
        assertFault("ResourceNotFoundFault", () -> service.stopReplication(stop));
        assertFault("ResourceNotFoundFault",
                () -> service.describeReplications(filter("replication-config-arn", configArn)));
        assertTrue(service.describeReplications(mapper.createObjectNode()).items().isEmpty());
    }

    @Test
    void taskLogsOfAnExistingInstanceAreEmptyAndOfAMissingOneFault() {
        createGroup("grp");
        ReplicationInstance created = service.createReplicationInstance(instanceRequest("logs", "grp"), REGION);
        ObjectNode request = mapper.createObjectNode().put("ReplicationInstanceArn", created.getReplicationInstanceArn());

        assertEquals(created.getReplicationInstanceArn(),
                service.describeReplicationInstanceTaskLogs(request, REGION).getReplicationInstanceArn());
        ObjectNode missing = mapper.createObjectNode()
                .put("ReplicationInstanceArn", "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":rep:MISSING");
        assertFault("ResourceNotFoundFault", () -> service.describeReplicationInstanceTaskLogs(missing, REGION));
    }

    @Test
    void clearRemovesEveryResourceKind() {
        createGroup("grp");
        service.createReplicationInstance(instanceRequest("cleared", "grp"), REGION);
        service.createEndpoint(mysqlSource("cleared-endpoint"), REGION);

        service.clear();

        assertTrue(service.describeReplicationInstances(mapper.createObjectNode(), REGION).items().isEmpty());
        assertTrue(service.describeEndpoints(mapper.createObjectNode(), REGION).items().isEmpty());
        assertTrue(service.describeReplicationSubnetGroups(mapper.createObjectNode(), REGION).items().isEmpty());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ReplicationInstance describeOnly(String identifier) {
        return service.describeReplicationInstances(filter("replication-instance-id", identifier), REGION)
                .items().getFirst();
    }

    private Map<String, String> tagsOf(String arn) {
        return service.listTagsForResource(mapper.createObjectNode().put("ResourceArn", arn), REGION).stream()
                .collect(Collectors.toMap(ResourceTag::key, ResourceTag::value));
    }

    private void createGroup(String identifier) {
        ObjectNode request = mapper.createObjectNode();
        request.put("ReplicationSubnetGroupIdentifier", identifier);
        request.put("ReplicationSubnetGroupDescription", "example");
        request.putArray("SubnetIds").add("subnet-a").add("subnet-b");
        service.createReplicationSubnetGroup(request, REGION);
    }

    private ObjectNode instanceRequest(String identifier, String group) {
        ObjectNode request = mapper.createObjectNode();
        request.put("ReplicationInstanceIdentifier", identifier);
        request.put("ReplicationInstanceClass", "dms.t3.micro");
        request.put("AllocatedStorage", 20);
        request.put("ReplicationSubnetGroupIdentifier", group);
        request.put("PubliclyAccessible", false);
        return request;
    }

    private ObjectNode mysqlSource(String identifier) {
        ObjectNode request = mapper.createObjectNode();
        request.put("EndpointIdentifier", identifier);
        request.put("EndpointType", "source");
        request.put("EngineName", "mysql");
        request.put("ServerName", "db.example.com");
        request.put("Port", 3306);
        request.put("Username", "admin");
        request.put("Password", "secret");
        request.put("DatabaseName", "app");
        return request;
    }

    private ObjectNode connectionRequest(ReplicationInstance instance, DmsEndpoint endpoint) {
        ObjectNode request = mapper.createObjectNode();
        request.put("ReplicationInstanceArn", instance.getReplicationInstanceArn());
        request.put("EndpointArn", endpoint.getEndpointArn());
        return request;
    }

    private ObjectNode filter(String name, String value) {
        ObjectNode request = mapper.createObjectNode();
        ArrayNode filters = request.putArray("Filters");
        filters.addObject().put("Name", name).putArray("Values").add(value);
        return request;
    }

    private static void assertFault(String code, Executable call) {
        AwsException fault = assertThrows(AwsException.class, call);
        assertEquals(code, fault.getErrorCode());
    }

    private static Subnet subnet(String subnetId) {
        return switch (subnetId) {
            case "subnet-a" -> subnet(subnetId, "us-east-1a");
            case "subnet-b" -> subnet(subnetId, "us-east-1b");
            case "subnet-c" -> subnet(subnetId, "us-east-1c");
            default -> null;
        };
    }

    private static Subnet subnet(String subnetId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(VPC_ID);
        subnet.setAvailabilityZone(availabilityZone);
        subnet.setRegion(REGION);
        return subnet;
    }

    private static SecurityGroup securityGroup(String groupId) {
        return switch (groupId) {
            case "sg-default" -> securityGroup(groupId, "default", VPC_ID);
            case "sg-other-vpc" -> securityGroup(groupId, "elsewhere", "vpc-0other");
            default -> null;
        };
    }

    private static SecurityGroup securityGroup(String groupId, String name, String vpcId) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(groupId);
        group.setGroupName(name);
        group.setVpcId(vpcId);
        group.setRegion(REGION);
        return group;
    }
}
