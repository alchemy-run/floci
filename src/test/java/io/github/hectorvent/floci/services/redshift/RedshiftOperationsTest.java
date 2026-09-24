package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RedshiftOperationsTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260822/us-east-1/redshift/aws4_request";

    private static final String GRANT_NAMES_PATH = "DescribeSnapshotCopyGrantsResponse"
            + ".DescribeSnapshotCopyGrantsResult.SnapshotCopyGrants.SnapshotCopyGrant.SnapshotCopyGrantName";
    private static final String MARKER_PATH =
            "DescribeSnapshotCopyGrantsResponse.DescribeSnapshotCopyGrantsResult.Marker";

    @InjectMock
    RedshiftContainerManager containerManager;

    @Inject
    Ec2Service ec2Service;

    @Inject
    SnsService snsService;

    private static Response query(String... parameters) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_HEADER)
                .formParam("Version", "2012-12-01");
        for (int i = 0; i < parameters.length; i += 2) {
            request.formParam(parameters[i], parameters[i + 1]);
        }
        return request.post("/");
    }

    @Test
    void missingRevisionResizeReservedNodeAndSubscriptionUseModeledErrors() {
        query("Action", "DescribeClusterDbRevisions", "ClusterIdentifier", "missing-revision-cluster")
                .then().statusCode(404).body(containsString("<Code>ClusterNotFound</Code>"));
        query("Action", "DescribeResize", "ClusterIdentifier", "missing-resize-cluster")
                .then().statusCode(404).body(containsString("<Code>ClusterNotFound</Code>"));
        query("Action", "GetReservedNodeExchangeOfferings", "ReservedNodeId", "00000000-0000-0000-0000-000000000000")
                .then().statusCode(404).body(containsString("<Code>ReservedNodeNotFound</Code>"));
        query("Action", "DescribeEventSubscriptions", "SubscriptionName", "missing-event-subscription")
                .then().statusCode(404).body(containsString("<Code>SubscriptionNotFound</Code>"));
        query("Action", "DeleteEventSubscription", "SubscriptionName", "missing-event-subscription")
                .then().statusCode(404).body(containsString("<Code>SubscriptionNotFound</Code>"));
        query("Action", "DescribeClusterDbRevisions", "MaxRecords", "19")
                .then().statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
        query("Action", "DescribeEventSubscriptions", "Marker", "unknown-marker")
                .then().statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void eventSubscriptionQueryRoundTripPreservesNamedMembersAndTags() {
        String topicArn = snsService.createTopic("redshift-wire-events", Map.of(), Map.of(), "us-east-1").getTopicArn();
        String name = "redshift-wire-events";
        try {
            query("Action", "CreateEventSubscription", "SubscriptionName", name, "SnsTopicArn", topicArn,
                    "SourceType", "cluster", "EventCategories.EventCategory.1", "monitoring",
                    "EventCategories.EventCategory.2", "management", "Severity", "INFO",
                    "Tags.Tag.1.Key", "owner", "Tags.Tag.1.Value", "wire")
                    .then().statusCode(200)
                    .body(containsString("<CustSubscriptionId>" + name + "</CustSubscriptionId>"))
                    .body(containsString("<Enabled>true</Enabled>"))
                    .body(containsString("<EventCategory>management</EventCategory>"))
                    .body(containsString("<EventCategory>monitoring</EventCategory>"));
            query("Action", "CreateEventSubscription", "SubscriptionName", name, "SnsTopicArn", topicArn)
                    .then().statusCode(400).body(containsString("<Code>SubscriptionAlreadyExist</Code>"));
            query("Action", "DescribeEventSubscriptions", "SubscriptionName", name)
                    .then().statusCode(200)
                    .body("DescribeEventSubscriptionsResponse.DescribeEventSubscriptionsResult.EventSubscriptionsList.EventSubscription.size()", equalTo(1))
                    .body(containsString("<Status>active</Status>"))
                    .body(containsString("<Key>owner</Key>"))
                    .body(not(containsString("<Marker>")));
            query("Action", "ModifyEventSubscription", "SubscriptionName", name,
                    "EventCategories.EventCategory.1", "monitoring", "Severity", "ERROR", "Enabled", "false")
                    .then().statusCode(200)
                    .body(containsString("<Enabled>false</Enabled>"))
                    .body(containsString("<Severity>ERROR</Severity>"))
                    .body(containsString("<SnsTopicArn>" + topicArn + "</SnsTopicArn>"))
                    .body(not(containsString("<EventCategory>management</EventCategory>")));
            query("Action", "ModifyEventSubscription", "SubscriptionName", name, "Enabled", "maybe")
                    .then().statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
            query("Action", "DeleteEventSubscription", "SubscriptionName", name).then().statusCode(200);
            query("Action", "DescribeEventSubscriptions", "SubscriptionName", name)
                    .then().statusCode(404).body(containsString("<Code>SubscriptionNotFound</Code>"));
        } finally {
            query("Action", "DeleteEventSubscription", "SubscriptionName", name);
            snsService.deleteTopic(topicArn, "us-east-1");
        }
    }

    @Test
    void parameterOverridesResetAndReplacementPreserveObservedOwnership() {
        String name = "pg-reset-wire";
        String replacement = "pg-reset-replacement";
        query("Action", "CreateClusterParameterGroup", "ParameterGroupName", name,
                "ParameterGroupFamily", "redshift-1.0", "Description", "before replacement",
                "Tags.Tag.1.Key", "owner", "Tags.Tag.1.Value", "control-plane")
                .then().statusCode(200);
        try {
            query("Action", "DescribeClusterParameters", "ParameterGroupName", name, "Source", "user")
                    .then().statusCode(200)
                    .body("DescribeClusterParametersResponse.DescribeClusterParametersResult.Parameters.Parameter.size()", equalTo(0));
            query("Action", "DescribeClusterParameterGroups", "ParameterGroupName", name)
                    .then().statusCode(200).body(containsString("<Key>owner</Key>"));
            query("Action", "ModifyClusterParameterGroup", "ParameterGroupName", name,
                    "Parameters.Parameter.1.ParameterName", "enable_user_activity_logging",
                    "Parameters.Parameter.1.ParameterValue", "true",
                    "Parameters.Parameter.2.ParameterName", "statement_timeout",
                    "Parameters.Parameter.2.ParameterValue", "60000")
                    .then().statusCode(200);
            query("Action", "ResetClusterParameterGroup", "ParameterGroupName", name,
                    "ResetAllParameters", "false",
                    "Parameters.Parameter.1.ParameterName", "enable_user_activity_logging")
                    .then().statusCode(200)
                    .body(containsString("<ParameterGroupStatus>pending-reboot</ParameterGroupStatus>"));
            query("Action", "DescribeClusterParameters", "ParameterGroupName", name, "Source", "user")
                    .then().statusCode(200)
                    .body(containsString("<ParameterName>statement_timeout</ParameterName>"))
                    .body(containsString("<Source>user</Source>"))
                    .body(not(containsString("<ParameterName>enable_user_activity_logging</ParameterName>")));
            query("Action", "DescribeClusterParameters", "ParameterGroupName", name, "Source", "engine-default")
                    .then().statusCode(200).body(containsString("<ParameterValue>false</ParameterValue>"));
            query("Action", "ResetClusterParameterGroup", "ParameterGroupName", name, "ResetAllParameters", "true")
                    .then().statusCode(200);
            query("Action", "DescribeClusterParameters", "ParameterGroupName", name, "Source", "user")
                    .then().statusCode(200)
                    .body("DescribeClusterParametersResponse.DescribeClusterParametersResult.Parameters.Parameter.size()", equalTo(0));
            query("Action", "CreateClusterParameterGroup", "ParameterGroupName", replacement,
                    "ParameterGroupFamily", "redshift-1.0", "Description", "after replacement")
                    .then().statusCode(200);
            query("Action", "DeleteClusterParameterGroup", "ParameterGroupName", name).then().statusCode(200);
            query("Action", "DescribeClusterParameterGroups", "ParameterGroupName", name)
                    .then().statusCode(404).body(containsString("<Code>ClusterParameterGroupNotFound</Code>"));
            query("Action", "DescribeClusterParameterGroups", "ParameterGroupName", replacement)
                    .then().statusCode(200).body(containsString("<Description>after replacement</Description>"));
        } finally {
            query("Action", "DeleteClusterParameterGroup", "ParameterGroupName", name);
            query("Action", "DeleteClusterParameterGroup", "ParameterGroupName", replacement);
        }
    }

    @Test
    void missingSnapshotCopyAndSubnetGroupReturnTheirAwsErrorCodes() {
        query("Action", "CopyClusterSnapshot", "SourceSnapshotIdentifier", "missing-copy-source",
                "TargetSnapshotIdentifier", "missing-copy-target")
                .then().statusCode(404).body(containsString("<Code>ClusterSnapshotNotFound</Code>"));
        query("Action", "DescribeClusterSnapshots", "SnapshotIdentifier", "missing-copy-target")
                .then().statusCode(404).body(containsString("<Code>ClusterSnapshotNotFound</Code>"));
        query("Action", "DescribeClusterSubnetGroups", "ClusterSubnetGroupName", "missing-subnet-group")
                .then().statusCode(404).body(containsString("<Code>ClusterSubnetGroupNotFoundFault</Code>"));
        query("Action", "ResetClusterParameterGroup", "ParameterGroupName", "missing-reset-group",
                "ResetAllParameters", "true")
                .then().statusCode(404).body(containsString("<Code>ClusterParameterGroupNotFound</Code>"));
    }

    @Test
    void describeEventsReturnsRecordedLifecycleEventsAndAppliesFilters() {
        String name = "pg-event-history";
        query("Action", "CreateClusterParameterGroup", "ParameterGroupName", name,
                "ParameterGroupFamily", "redshift-1.0", "Description", "events")
                .then().statusCode(200);
        query("Action", "DeleteClusterParameterGroup", "ParameterGroupName", name).then().statusCode(200);
        query("Action", "DescribeEvents", "Duration", "60", "SourceType", "cluster-parameter-group",
                "SourceIdentifier", name)
                .then().statusCode(200)
                .body("DescribeEventsResponse.DescribeEventsResult.Events.Event.size()", equalTo(2))
                .body(containsString("<SourceIdentifier>" + name + "</SourceIdentifier>"))
                .body(containsString("<SourceType>cluster-parameter-group</SourceType>"))
                .body(containsString("<Date>"));
        query("Action", "DescribeEvents", "Duration", "60", "SourceType", "cluster",
                "SourceIdentifier", name)
                .then().statusCode(200)
                .body("DescribeEventsResponse.DescribeEventsResult.Events.Event.size()", equalTo(0));
        query("Action", "DescribeEvents", "Duration", "-1")
                .then().statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    @Order(1)
    void testParameterGroupLifecycle() {
        // 1. CreateClusterParameterGroup
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateClusterParameterGroup")
            .formParam("ParameterGroupName", "pg-test-1")
            .formParam("ParameterGroupFamily", "redshift-1.0")
            .formParam("Description", "Test Redshift Parameter Group")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ParameterGroupName>pg-test-1</ParameterGroupName>"))
            .body(containsString("<ParameterGroupFamily>redshift-1.0</ParameterGroupFamily>"));

        // 2. DescribeClusterParameterGroups
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterParameterGroups")
            .formParam("ParameterGroupName", "pg-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ParameterGroupName>pg-test-1</ParameterGroupName>"));

        // 2b. ModifyClusterParameterGroup
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "ModifyClusterParameterGroup")
            .formParam("ParameterGroupName", "pg-test-1")
            .formParam("Parameters.member.1.ParameterName", "statement_timeout")
            .formParam("Parameters.member.1.ParameterValue", "5000")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ParameterGroupName>pg-test-1</ParameterGroupName>"));

        // 3. DescribeClusterParameters
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterParameters")
            .formParam("ParameterGroupName", "pg-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<DescribeClusterParametersResponse>"))
            .body(containsString("<ParameterName>statement_timeout</ParameterName>"))
            .body(containsString("<ParameterValue>5000</ParameterValue>"));

        // 4. DeleteClusterParameterGroup
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteClusterParameterGroup")
            .formParam("ParameterGroupName", "pg-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<DeleteClusterParameterGroupResponse>"));
    }

    @Test
    @Order(2)
    void testClusterAndSnapshotLifecycle() {
        when(containerManager.start(any(), eq("cluster-src"), any(), any()))
                .thenReturn(new RedshiftContainerHandle("c1", "cluster-src", "localhost", 5439));
        doAnswer(invocation -> {
            Path p = invocation.getArgument(4);
            Files.writeString(p, "-- dump sql table test_data;");
            return null;
        }).when(containerManager).takeSnapshot(any(), eq("cluster-src"), eq("admin"), eq("dev"), any(Path.class));
        when(containerManager.start(any(), eq("cluster-restored"), any(), any()))
                .thenReturn(new RedshiftContainerHandle("c2", "cluster-restored", "localhost", 5440));

        // 1. CreateCluster
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateCluster")
            .formParam("ClusterIdentifier", "cluster-src")
            .formParam("NodeType", "dc2.large")
            .formParam("MasterUsername", "admin")
            .formParam("MasterUserPassword", "password123")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-src</ClusterIdentifier>"))
            .body(containsString("<ClusterStatus>available</ClusterStatus>"))
            .body(containsString("<ClusterAvailabilityStatus>Available</ClusterAvailabilityStatus>"))
            .body(containsString("<AvailabilityZoneRelocationStatus>disabled</AvailabilityZoneRelocationStatus>"));

        // 1b. RebootCluster, must preserve data (no Docker volume backs this container)
        when(containerManager.getContainer(any(), eq("cluster-src")))
                .thenReturn(Optional.of(new RedshiftContainerHandle("c1", "cluster-src", "localhost", 5439)));
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "RebootCluster")
            .formParam("ClusterIdentifier", "cluster-src")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-src</ClusterIdentifier>"))
            .body(containsString("<ClusterStatus>available</ClusterStatus>"));

        // 1c. ModifyCluster
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "ModifyCluster")
            .formParam("ClusterIdentifier", "cluster-src")
            .formParam("NodeType", "ra3.xlplus")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<NodeType>ra3.xlplus</NodeType>"));

        // 1d. ModifyClusterIamRoles adds a role, then removes it
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "ModifyClusterIamRoles")
            .formParam("ClusterIdentifier", "cluster-src")
            .formParam("AddIamRoles.IamRoleArn.1", "arn:aws:iam::000000000000:role/redshift-copy")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<IamRoleArn>arn:aws:iam::000000000000:role/redshift-copy</IamRoleArn>"))
            .body(containsString("<ApplyStatus>in-sync</ApplyStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "ModifyClusterIamRoles")
            .formParam("ClusterIdentifier", "cluster-src")
            .formParam("RemoveIamRoles.IamRoleArn.1", "arn:aws:iam::000000000000:role/redshift-copy")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("redshift-copy")));

        // 1e. Static discovery APIs
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterVersions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ClusterParameterGroupFamily>redshift-1.0</ClusterParameterGroupFamily>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeOrderableClusterOptions")
            .formParam("NodeType", "ra3.xlplus")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<NodeType>ra3.xlplus</NodeType>"));

        // 2. CreateClusterSnapshot
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateClusterSnapshot")
            .formParam("SnapshotIdentifier", "snap-test-1")
            .formParam("ClusterIdentifier", "cluster-src")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<SnapshotIdentifier>snap-test-1</SnapshotIdentifier>"))
            .body(containsString("<ClusterIdentifier>cluster-src</ClusterIdentifier>"));

        // 3. DescribeClusterSnapshots
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterSnapshots")
            .formParam("SnapshotIdentifier", "snap-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<SnapshotIdentifier>snap-test-1</SnapshotIdentifier>"));

        // 4. DeleteCluster
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteCluster")
            .formParam("ClusterIdentifier", "cluster-src")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-src</ClusterIdentifier>"))
            .body(containsString("<ClusterStatus>deleting</ClusterStatus>"));

        // 5. RestoreFromClusterSnapshot
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "RestoreFromClusterSnapshot")
            .formParam("ClusterIdentifier", "cluster-restored")
            .formParam("SnapshotIdentifier", "snap-test-1")
            .formParam("NodeType", "dc2.large")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-restored</ClusterIdentifier>"))
            .body(containsString("<ClusterStatus>available</ClusterStatus>"));

        // 6. DeleteClusterSnapshot
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteClusterSnapshot")
            .formParam("SnapshotIdentifier", "snap-test-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<SnapshotIdentifier>snap-test-1</SnapshotIdentifier>"))
            .body(containsString("<Status>deleted</Status>"));

        // 7. Delete restored cluster
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteCluster")
            .formParam("ClusterIdentifier", "cluster-restored")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterIdentifier>cluster-restored</ClusterIdentifier>"));
    }

    @Test
    @Order(4)
    void testClusterSubnetGroupLifecycle() {
        List<Subnet> subnets = ec2Service.describeSubnets("us-east-1", List.of(),
                Map.of("default-for-az", List.of("true")));
        String first = subnets.get(0).getSubnetId();
        String second = subnets.get(1).getSubnetId();
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", "subnet-group-1")
            .formParam("Description", "Test subnet group")
            .formParam("SubnetIds.SubnetIdentifier.1", first)
            .formParam("SubnetIds.SubnetIdentifier.2", second)
            .formParam("Tags.Tag.1.Key", "owner")
            .formParam("Tags.Tag.1.Value", "subnet-test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<ClusterSubnetGroupName>subnet-group-1</ClusterSubnetGroupName>"))
            .body(containsString("<SubnetIdentifier>" + first + "</SubnetIdentifier>"))
            .body(containsString("<VpcId>" + subnets.getFirst().getVpcId() + "</VpcId>"))
            .body(containsString("<SubnetGroupStatus>Complete</SubnetGroupStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeClusterSubnetGroups")
            .formParam("ClusterSubnetGroupName", "subnet-group-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ClusterSubnetGroupName>subnet-group-1</ClusterSubnetGroupName>"))
            .body(containsString("<Value>subnet-test</Value>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "ModifyClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", "subnet-group-1")
            .formParam("Description", "Updated")
            .formParam("SubnetIds.SubnetIdentifier.1", second)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubnetIdentifier>" + second + "</SubnetIdentifier>"))
            .body(not(containsString("<SubnetIdentifier>" + first + "</SubnetIdentifier>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteClusterSubnetGroup")
            .formParam("ClusterSubnetGroupName", "subnet-group-1")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void snapshotCopyGrantLifecycle() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", "copy-grant-1")
            .formParam("KmsKeyId", "key-abc")
            .formParam("Tags.Tag.1.Key", "env")
            .formParam("Tags.Tag.1.Value", "test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<SnapshotCopyGrantName>copy-grant-1</SnapshotCopyGrantName>"))
            .body(containsString("<KmsKeyId>key-abc</KmsKeyId>"))
            .body(containsString("<Key>env</Key>"));

        // A grant created without KmsKeyId gets the account's AWS-managed Redshift key.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", "copy-grant-2")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<KmsKeyId>arn:aws:kms:us-east-1:"))
            .body(containsString(":alias/aws/redshift</KmsKeyId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", "copy-grant-1")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("SnapshotCopyGrantAlreadyExistsFault"));

        // Name filter selects one grant, and omitting it returns both.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
            .formParam("SnapshotCopyGrantName", "copy-grant-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SnapshotCopyGrantName>copy-grant-1</SnapshotCopyGrantName>"))
            .body(not(containsString("<SnapshotCopyGrantName>copy-grant-2</SnapshotCopyGrantName>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SnapshotCopyGrantName>copy-grant-1</SnapshotCopyGrantName>"))
            .body(containsString("<SnapshotCopyGrantName>copy-grant-2</SnapshotCopyGrantName>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", "copy-grant-1")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
            .formParam("SnapshotCopyGrantName", "copy-grant-1")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("SnapshotCopyGrantNotFoundFault"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", "copy-grant-1")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("SnapshotCopyGrantNotFoundFault"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", "copy-grant-2")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void snapshotCopyGrantPagination() {
        // AWS constrains MaxRecords to 20-100, so a two-page walk needs more than 20 grants.
        int total = 21;
        for (int i = 1; i <= total; i++) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateSnapshotCopyGrant")
                .formParam("SnapshotCopyGrantName", String.format("pg-grant-%02d", i))
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }

        String firstPage = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
            .formParam("MaxRecords", "20")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        List<String> firstNames = new XmlPath(firstPage).getList(GRANT_NAMES_PATH);
        String marker = new XmlPath(firstPage).getString(MARKER_PATH);
        assertEquals(20, firstNames.size());
        assertEquals("pg-grant-01", firstNames.get(0));
        assertEquals("pg-grant-20", firstNames.get(19));
        assertNotNull(marker);
        assertFalse(marker.isBlank(), "a non-final page must carry a Marker");

        String secondPage = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
            .formParam("MaxRecords", "20")
            .formParam("Marker", marker)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        List<String> secondNames = new XmlPath(secondPage).getList(GRANT_NAMES_PATH);
        assertEquals(List.of("pg-grant-21"), secondNames);
        // Asserted on the raw body: an absent GPath node can read back as "" rather than null.
        assertFalse(secondPage.contains("<Marker>"), "the final page must not carry a Marker");

        // Out-of-range MaxRecords is rejected rather than silently clamped.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
            .formParam("MaxRecords", "19")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
            .formParam("MaxRecords", "101")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));

        for (int i = 1; i <= total; i++) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DeleteSnapshotCopyGrant")
                .formParam("SnapshotCopyGrantName", String.format("pg-grant-%02d", i))
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(7)
    void snapshotCopyGrantNameContract() {
        String tooLong = "g123456789012345678901234567890123456789012345678901234567890123";
        for (String invalid : List.of("1grant", "Grant", "grant--copy", "grant-", tooLong)) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateSnapshotCopyGrant")
                .formParam("SnapshotCopyGrantName", invalid)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("InvalidParameterValue"));

            // A rejected name must leave nothing behind, read back through Describe.
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeSnapshotCopyGrants")
                .formParam("SnapshotCopyGrantName", invalid)
            .when()
                .post("/")
            .then()
                .body(not(containsString("<SnapshotCopyGrantName>" + invalid + "</SnapshotCopyGrantName>")));
        }

        String maxLength = "g12345678901234567890123456789012345678901234567890123456789012";
        assertEquals(63, maxLength.length());
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", maxLength)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
            .formParam("SnapshotCopyGrantName", maxLength)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SnapshotCopyGrantName>" + maxLength + "</SnapshotCopyGrantName>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", maxLength)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void testTagLifecycle() {
        when(containerManager.start(any(), eq("cluster-tags"), any(), any()))
                .thenReturn(new RedshiftContainerHandle("c3", "cluster-tags", "localhost", 5441));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateCluster")
            .formParam("ClusterIdentifier", "cluster-tags")
            .formParam("NodeType", "dc2.large")
            .formParam("MasterUsername", "admin")
            .formParam("MasterUserPassword", "password123")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String clusterArn = "arn:aws:redshift:us-east-1:000000000000:cluster:cluster-tags";

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "CreateTags")
            .formParam("ResourceName", clusterArn)
            .formParam("Tags.member.1.Key", "env")
            .formParam("Tags.member.1.Value", "test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeTags")
            .formParam("ResourceName", clusterArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"))
            .body(containsString("<Value>test</Value>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteTags")
            .formParam("ResourceName", clusterArn)
            .formParam("TagKeys.member.1", "env")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteCluster")
            .formParam("ClusterIdentifier", "cluster-tags")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /**
     * CreateSnapshotCopyGrant checks the name is free and then writes. Both steps run under the
     * service lock, so of two concurrent creates of one name exactly one wins and the other sees
     * SnapshotCopyGrantAlreadyExistsFault -- the loser must not overwrite the winner's grant.
     */
    @Test
    @Order(8)
    void concurrentCreatesOfOneGrantNameLeaveExactlyOneGrant() throws Exception {
        int threads = 2;
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        release.await();
                        int status = given()
                            .contentType("application/x-www-form-urlencoded")
                            .header("Authorization", AUTH_HEADER)
                            .formParam("Action", "CreateSnapshotCopyGrant")
                            .formParam("SnapshotCopyGrantName", "race-grant")
                        .when()
                            .post("/")
                        .then()
                            .extract().statusCode();
                        if (status == 200) {
                            created.incrementAndGet();
                        } else if (status == 400) {
                            rejected.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            release.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent creates did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, created.get(), "exactly one create should succeed");
        assertEquals(1, rejected.get(), "the losing create should be rejected, not silently accepted");

        String names = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DescribeSnapshotCopyGrants")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();
        assertEquals(1, names.split("<SnapshotCopyGrantName>race-grant</SnapshotCopyGrantName>", -1).length - 1,
                "the grant should be stored once");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "DeleteSnapshotCopyGrant")
            .formParam("SnapshotCopyGrantName", "race-grant")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
