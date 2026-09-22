package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.ClusterParameterGroup;
import software.amazon.awssdk.services.redshift.model.ClusterParameterGroupNotFoundException;
import software.amazon.awssdk.services.redshift.model.ClusterSnapshotNotFoundException;
import software.amazon.awssdk.services.redshift.model.ClusterSubnetGroupNotFoundException;
import software.amazon.awssdk.services.redshift.model.Parameter;
import software.amazon.awssdk.services.redshift.model.CreateClusterParameterGroupRequest;
import software.amazon.awssdk.services.redshift.model.CreateClusterParameterGroupResponse;
import software.amazon.awssdk.services.redshift.model.CreateClusterRequest;
import software.amazon.awssdk.services.redshift.model.CreateClusterResponse;
import software.amazon.awssdk.services.redshift.model.CreateClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.CreateClusterSnapshotResponse;
import software.amazon.awssdk.services.redshift.model.CreateClusterSubnetGroupRequest;
import software.amazon.awssdk.services.redshift.model.CreateTagsRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterParameterGroupRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterSubnetGroupRequest;
import software.amazon.awssdk.services.redshift.model.DeleteTagsRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClusterParameterGroupsRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClusterParameterGroupsResponse;
import software.amazon.awssdk.services.redshift.model.DescribeClusterSnapshotsRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClusterSnapshotsResponse;
import software.amazon.awssdk.services.redshift.model.DescribeClusterSubnetGroupsRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClustersRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClustersResponse;
import software.amazon.awssdk.services.redshift.model.DescribeTagsRequest;
import software.amazon.awssdk.services.redshift.model.ModifyClusterRequest;
import software.amazon.awssdk.services.redshift.model.RebootClusterRequest;
import software.amazon.awssdk.services.redshift.model.RestoreFromClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.RestoreFromClusterSnapshotResponse;
import software.amazon.awssdk.services.redshift.model.Snapshot;
import software.amazon.awssdk.services.redshift.model.EventSubscription;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.redshift.model.Tag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Redshift Operations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedshiftOperationsTest {

    private static final Logger LOG = Logger.getLogger(RedshiftOperationsTest.class.getName());

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password123";
    private static final String DATABASE = "dev";

    private static RedshiftClient client;
    private static final List<String> clustersToCleanup = new ArrayList<>();
    private static final List<String> snapshotsToCleanup = new ArrayList<>();
    private static final List<String> parameterGroupsToCleanup = new ArrayList<>();
    private static final List<String> subnetGroupsToCleanup = new ArrayList<>();

    @BeforeAll
    static void setup() {
        client = TestFixtures.redshiftClient();
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            for (String clusterId : clustersToCleanup) {
                try {
                    client.deleteCluster(DeleteClusterRequest.builder()
                            .clusterIdentifier(clusterId)
                            .build());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to clean up Redshift cluster " + clusterId, e);
                }
            }
            for (String snapId : snapshotsToCleanup) {
                try {
                    client.deleteClusterSnapshot(DeleteClusterSnapshotRequest.builder()
                            .snapshotIdentifier(snapId)
                            .build());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to clean up Redshift snapshot " + snapId, e);
                }
            }
            for (String pgName : parameterGroupsToCleanup) {
                try {
                    client.deleteClusterParameterGroup(DeleteClusterParameterGroupRequest.builder()
                            .parameterGroupName(pgName)
                            .build());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to clean up Redshift parameter group " + pgName, e);
                }
            }
            for (String subnetGroupName : subnetGroupsToCleanup) {
                try {
                    client.deleteClusterSubnetGroup(DeleteClusterSubnetGroupRequest.builder()
                            .clusterSubnetGroupName(subnetGroupName)
                            .build());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to clean up Redshift cluster subnet group " + subnetGroupName, e);
                }
            }
            client.close();
        }
    }

    @Test
    @DisplayName("Revision, resize, reserved node and subscription probes decode modeled errors")
    void modeledQueryErrors() {
        assertThatThrownBy(() -> client.describeClusterDbRevisions(r -> r.clusterIdentifier("missing-revision-probe")))
                .isInstanceOfSatisfying(RedshiftException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ClusterNotFound"));
        assertThatThrownBy(() -> client.describeResize(r -> r.clusterIdentifier("missing-resize-probe")))
                .isInstanceOfSatisfying(RedshiftException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ClusterNotFound"));
        assertThatThrownBy(() -> client.getReservedNodeExchangeOfferings(r -> r.reservedNodeId("00000000-0000-0000-0000-000000000000")))
                .isInstanceOfSatisfying(RedshiftException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ReservedNodeNotFound"));
        assertThatThrownBy(() -> client.describeEventSubscriptions(r -> r.subscriptionName("missing-subscription-probe")))
                .isInstanceOfSatisfying(RedshiftException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("SubscriptionNotFound"));
        assertThat(client.describeClusterDbRevisions(r -> r.maxRecords(100)).clusterDbRevisions()).isNotNull();
    }

    @Test
    @DisplayName("Event subscriptions deliver observed lifecycle events through SNS to SQS and honor filters")
    void eventSubscriptionsDeliverObservedEvents() {
        String name = TestFixtures.uniqueName("rs-events");
        String source = TestFixtures.uniqueName("rs-event-source");
        try (SnsClient sns = TestFixtures.snsClient(); SqsClient sqs = TestFixtures.sqsClient()) {
            String topicArn = sns.createTopic(r -> r.name(name)).topicArn();
            String queueUrl = sqs.createQueue(r -> r.queueName(name)).queueUrl();
            try {
                String queueArn = sqs.getQueueAttributes(r -> r.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                        .attributes().get(QueueAttributeName.QUEUE_ARN);
                sqs.setQueueAttributes(r -> r.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY,
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},"
                                + "\"Action\":\"sqs:SendMessage\",\"Resource\":\"" + queueArn + "\","
                                + "\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"" + topicArn + "\"}}}]}")));
                sns.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(queueArn)
                        .attributes(Map.of("RawMessageDelivery", "true")));
                client.createClusterParameterGroup(r -> r.parameterGroupName(source).parameterGroupFamily("redshift-1.0")
                        .description("event delivery source"));
                parameterGroupsToCleanup.add(source);
                EventSubscription created = client.createEventSubscription(r -> r.subscriptionName(name).snsTopicArn(topicArn)
                        .sourceType("cluster-parameter-group").sourceIds(source).eventCategories("management", "monitoring")
                        .severity("INFO").tags(Tag.builder().key("owner").value("delivery-test").build())).eventSubscription();
                assertThat(created.custSubscriptionId()).isEqualTo(name);
                assertThat(created.status()).isEqualTo("active");
                assertThat(created.enabled()).isTrue();
                assertThat(created.subscriptionCreationTime()).isNotNull();
                assertThat(created.sourceIdsList()).containsExactly(source);
                assertThat(created.eventCategoriesList()).containsExactly("management", "monitoring");
                assertThat(client.describeEventSubscriptions(r -> r.subscriptionName(name)).eventSubscriptionsList())
                        .singleElement().satisfies(observed -> assertThat(observed.tags()).containsExactlyElementsOf(created.tags()));
                assertThat(sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(1)).messages()).isEmpty();
                String otherSource = TestFixtures.uniqueName("rs-other-source");
                client.createClusterParameterGroup(r -> r.parameterGroupName(otherSource).parameterGroupFamily("redshift-1.0")
                        .description("nonmatching event source"));
                parameterGroupsToCleanup.add(otherSource);
                client.deleteClusterParameterGroup(r -> r.parameterGroupName(otherSource));
                parameterGroupsToCleanup.remove(otherSource);
                assertThat(sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(1)).messages()).isEmpty();

                client.modifyClusterParameterGroup(r -> r.parameterGroupName(source)
                        .parameters(Parameter.builder().parameterName("statement_timeout").parameterValue("1234").build()));
                List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(2)).messages();
                assertThat(messages).singleElement().satisfies(message -> {
                    assertThat(message.body()).contains(source, "Cluster parameter group modified.");
                    String observedMessage = client.describeEvents(r -> r.sourceType("cluster-parameter-group")
                            .sourceIdentifier(source)).events().getLast().message();
                    assertThat(message.body()).contains(observedMessage);
                    sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                });

                EventSubscription disabled = client.modifyEventSubscription(r -> r.subscriptionName(name)
                        .eventCategories("monitoring").severity("ERROR").enabled(false)).eventSubscription();
                assertThat(disabled.custSubscriptionId()).isEqualTo(name);
                assertThat(disabled.eventCategoriesList()).containsExactly("monitoring");
                assertThat(disabled.severity()).isEqualTo("ERROR");
                assertThat(disabled.enabled()).isFalse();
                client.modifyClusterParameterGroup(r -> r.parameterGroupName(source)
                        .parameters(Parameter.builder().parameterName("statement_timeout").parameterValue("1235").build()));
                assertThat(sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(1)).messages()).isEmpty();

                client.modifyEventSubscription(r -> r.subscriptionName(name).enabled(true).severity("INFO"));
                client.modifyClusterParameterGroup(r -> r.parameterGroupName(source)
                        .parameters(Parameter.builder().parameterName("statement_timeout").parameterValue("1236").build()));
                assertThat(sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(1)).messages()).isEmpty();
                client.modifyEventSubscription(r -> r.subscriptionName(name).eventCategories("management").severity("ERROR"));
                client.modifyClusterParameterGroup(r -> r.parameterGroupName(source)
                        .parameters(Parameter.builder().parameterName("statement_timeout").parameterValue("1237").build()));
                assertThat(sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(1)).messages()).isEmpty();

                client.deleteEventSubscription(r -> r.subscriptionName(name));
                assertThatThrownBy(() -> client.describeEventSubscriptions(r -> r.subscriptionName(name)))
                        .isInstanceOfSatisfying(RedshiftException.class,
                                error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("SubscriptionNotFound"));
                client.deleteClusterParameterGroup(r -> r.parameterGroupName(source));
                parameterGroupsToCleanup.remove(source);
                assertThat(sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(1)).messages()).isEmpty();
            } finally {
                try {
                    client.deleteEventSubscription(r -> r.subscriptionName(name));
                } catch (RedshiftException exception) {
                    if (!"SubscriptionNotFound".equals(exception.awsErrorDetails().errorCode())) {
                        throw exception;
                    }
                } finally {
                    sns.deleteTopic(r -> r.topicArn(topicArn));
                    sqs.deleteQueue(r -> r.queueUrl(queueUrl));
                }
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("Create cluster, insert data, snapshot, restore to new cluster, verify data")
    void testSnapshotAndRestoreLifecycle() throws Exception {
        String sourceClusterId = TestFixtures.uniqueName("rs-source");
        String restoredClusterId = TestFixtures.uniqueName("rs-restored");
        String snapshotId = TestFixtures.uniqueName("rs-snap");
        String copiedSnapshotId = TestFixtures.uniqueName("rs-copy");

        clustersToCleanup.add(sourceClusterId);
        clustersToCleanup.add(restoredClusterId);
        snapshotsToCleanup.add(snapshotId);

        // 1. Create source cluster
        CreateClusterResponse createRes = client.createCluster(CreateClusterRequest.builder()
                .clusterIdentifier(sourceClusterId)
                .nodeType("dc2.large")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD)
                .build());

        assertThat(createRes.cluster()).isNotNull();
        assertThat(createRes.cluster().clusterIdentifier()).isEqualTo(sourceClusterId);

        DescribeClustersResponse descRes = client.describeClusters(DescribeClustersRequest.builder()
                .clusterIdentifier(sourceClusterId)
                .build());
        Cluster sourceCluster = descRes.clusters().get(0);
        assertThat(sourceCluster.endpoint()).isNotNull();
        String sourceHost = sourceCluster.endpoint().address();
        int sourcePort = sourceCluster.endpoint().port();

        // 2. Use JDBC to create a table and insert a row
        try (Connection conn = awaitPostgresConnection(sourceHost, sourcePort, USERNAME, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE test_data (id INT PRIMARY KEY, name VARCHAR(100));");
            stmt.execute("INSERT INTO test_data (id, name) VALUES (1, 'floci-redshift-snapshot-val');");
        }

        // 3. Call createClusterSnapshot via AWS SDK
        CreateClusterSnapshotResponse snapRes = client.createClusterSnapshot(CreateClusterSnapshotRequest.builder()
                .snapshotIdentifier(snapshotId)
                .clusterIdentifier(sourceClusterId)
                .build());

        Snapshot snapshot = snapRes.snapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.snapshotIdentifier()).isEqualTo(snapshotId);
        assertThat(snapshot.clusterIdentifier()).isEqualTo(sourceClusterId);
        assertThat(snapshot.status()).isEqualTo("available");

        DescribeClusterSnapshotsResponse descSnapRes = client.describeClusterSnapshots(DescribeClusterSnapshotsRequest.builder()
                .snapshotIdentifier(snapshotId)
                .build());
        assertThat(descSnapRes.snapshots()).isNotEmpty();
        assertThat(descSnapRes.snapshots().get(0).snapshotIdentifier()).isEqualTo(snapshotId);

        Snapshot copied = client.copyClusterSnapshot(r -> r.sourceSnapshotIdentifier(snapshotId)
                .sourceSnapshotClusterIdentifier(sourceClusterId).targetSnapshotIdentifier(copiedSnapshotId)).snapshot();
        snapshotsToCleanup.add(copiedSnapshotId);
        assertThat(copied.snapshotIdentifier()).isEqualTo(copiedSnapshotId);
        assertThat(copied.clusterIdentifier()).isEqualTo(sourceClusterId);
        client.deleteClusterSnapshot(r -> r.snapshotIdentifier(snapshotId));
        snapshotsToCleanup.remove(snapshotId);
        assertThat(client.describeClusterSnapshots(r -> r.snapshotIdentifier(copiedSnapshotId))
                .snapshots().get(0).status()).isEqualTo("available");

        // 4. Delete source cluster
        client.deleteCluster(DeleteClusterRequest.builder()
                .clusterIdentifier(sourceClusterId)
                .build());
        clustersToCleanup.remove(sourceClusterId);

        // 5. Restore from snapshot to a new cluster identifier
        RestoreFromClusterSnapshotResponse restoreRes = client.restoreFromClusterSnapshot(
                RestoreFromClusterSnapshotRequest.builder()
                        .clusterIdentifier(restoredClusterId)
                        .snapshotIdentifier(copiedSnapshotId)
                        .nodeType("dc2.large")
                        .build());

        Cluster restoredCluster = restoreRes.cluster();
        assertThat(restoredCluster).isNotNull();
        assertThat(restoredCluster.clusterIdentifier()).isEqualTo(restoredClusterId);
        assertThat(restoredCluster.endpoint()).isNotNull();

        String restoredHost = restoredCluster.endpoint().address();
        int restoredPort = restoredCluster.endpoint().port();

        // 6. Use JDBC to query restored cluster and verify row exists
        try (Connection conn = awaitPostgresConnection(restoredHost, restoredPort, USERNAME, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM test_data WHERE id = 1;")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("name")).isEqualTo("floci-redshift-snapshot-val");
        }

        // Cleanup restored cluster and snapshot
        client.deleteCluster(DeleteClusterRequest.builder()
                .clusterIdentifier(restoredClusterId)
                .build());
        clustersToCleanup.remove(restoredClusterId);

        client.deleteClusterSnapshot(DeleteClusterSnapshotRequest.builder()
                .snapshotIdentifier(copiedSnapshotId)
                .build());
        snapshotsToCleanup.remove(copiedSnapshotId);
    }

    @Test
    @Order(2)
    @DisplayName("Create, describe, and delete cluster parameter group")
    void testClusterParameterGroupLifecycle() {
        String pgName = TestFixtures.uniqueName("rs-pg");
        parameterGroupsToCleanup.add(pgName);

        // 1. Create cluster parameter group
        CreateClusterParameterGroupResponse createPgRes = client.createClusterParameterGroup(
                CreateClusterParameterGroupRequest.builder()
                        .parameterGroupName(pgName)
                        .parameterGroupFamily("redshift-1.0")
                        .description("Test parameter group for Redshift")
                        .build());

        ClusterParameterGroup pg = createPgRes.clusterParameterGroup();
        assertThat(pg).isNotNull();
        assertThat(pg.parameterGroupName()).isEqualTo(pgName);
        assertThat(pg.parameterGroupFamily()).isEqualTo("redshift-1.0");
        assertThat(pg.description()).isEqualTo("Test parameter group for Redshift");

        // 2. Describe cluster parameter groups
        DescribeClusterParameterGroupsResponse descPgRes = client.describeClusterParameterGroups(
                DescribeClusterParameterGroupsRequest.builder()
                        .parameterGroupName(pgName)
                        .build());

        assertThat(descPgRes.parameterGroups()).isNotEmpty();
        ClusterParameterGroup foundPg = descPgRes.parameterGroups().stream()
                .filter(g -> pgName.equals(g.parameterGroupName()))
                .findFirst()
                .orElse(null);
        assertThat(foundPg).isNotNull();
        assertThat(foundPg.parameterGroupFamily()).isEqualTo("redshift-1.0");
        assertThat(foundPg.description()).isEqualTo("Test parameter group for Redshift");

        // 3. Delete cluster parameter group
        client.deleteClusterParameterGroup(DeleteClusterParameterGroupRequest.builder()
                .parameterGroupName(pgName)
                .build());
        parameterGroupsToCleanup.remove(pgName);
    }

    @Test
    @Order(3)
    void testTaggingAndSubnetGroupAndModify() {
        // Cluster subnet group
        String subnetGroupName = TestFixtures.uniqueName("rs-subnet-group");
        List<Subnet> subnets;
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            subnets = ec2.describeSubnets(r -> r.filters(Filter.builder()
                    .name("default-for-az").values("true").build())).subnets();
        }
        List<String> subnetIds = subnets.stream().limit(2).map(Subnet::subnetId).toList();
        assertThat(subnetIds).hasSize(2);
        client.createClusterSubnetGroup(CreateClusterSubnetGroupRequest.builder()
                .clusterSubnetGroupName(subnetGroupName)
                .description("SDK test subnet group")
                .subnetIds(subnetIds)
                .tags(Tag.builder().key("owner").value("sdk-subnets").build())
                .build());
        subnetGroupsToCleanup.add(subnetGroupName);

        var describedGroups = client.describeClusterSubnetGroups(
                DescribeClusterSubnetGroupsRequest.builder()
                        .clusterSubnetGroupName(subnetGroupName)
                        .build());
        assertThat(describedGroups.clusterSubnetGroups()).hasSize(1);
        assertThat(describedGroups.clusterSubnetGroups().get(0).subnets()).hasSize(2);
        assertThat(describedGroups.clusterSubnetGroups().get(0).vpcId()).isEqualTo(subnets.get(0).vpcId());
        assertThat(describedGroups.clusterSubnetGroups().get(0).subnetGroupStatus()).isEqualTo("Complete");
        assertThat(describedGroups.clusterSubnetGroups().get(0).tags())
                .contains(Tag.builder().key("owner").value("sdk-subnets").build());

        // Cluster + tagging + modify + reboot
        String clusterId = TestFixtures.uniqueName("rs-tag-cluster");
        CreateClusterResponse created = client.createCluster(CreateClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .nodeType("dc2.large")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD)
                .build());
        clustersToCleanup.add(clusterId);
        String arn = "arn:aws:redshift:us-east-1:000000000000:cluster:" + clusterId;

        client.createTags(CreateTagsRequest.builder()
                .resourceName(arn)
                .tags(Tag.builder().key("env").value("test").build())
                .build());

        var described = client.describeTags(DescribeTagsRequest.builder()
                .resourceName(arn)
                .build());
        assertThat(described.taggedResources()).anyMatch(t -> "env".equals(t.tag().key()) && "test".equals(t.tag().value()));

        client.deleteTags(DeleteTagsRequest.builder()
                .resourceName(arn)
                .tagKeys("env")
                .build());

        var modified = client.modifyCluster(ModifyClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .nodeType("ra3.xlplus")
                .build());
        assertThat(modified.cluster().nodeType()).isEqualTo("ra3.xlplus");

        var rebooted = client.rebootCluster(RebootClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .build());
        assertThat(rebooted.cluster().clusterStatus()).isEqualTo("available");
    }

    @Test
    @DisplayName("Parameter reset, ownership tags, replacement, events, and typed missing resources")
    void parameterResetReplacementAndTypedErrors() {
        String name = TestFixtures.uniqueName("rs-reset");
        String replacement = TestFixtures.uniqueName("rs-replacement");
        parameterGroupsToCleanup.add(name);
        parameterGroupsToCleanup.add(replacement);
        Tag owner = Tag.builder().key("owner").value("sdk").build();
        client.createClusterParameterGroup(r -> r.parameterGroupName(name).parameterGroupFamily("redshift-1.0")
                .description("before replacement").tags(owner));
        assertThat(client.describeClusterParameters(r -> r.parameterGroupName(name).source("user")).parameters()).isEmpty();
        assertThat(client.describeClusterParameterGroups(r -> r.parameterGroupName(name))
                .parameterGroups().get(0).tags()).contains(owner);
        client.modifyClusterParameterGroup(r -> r.parameterGroupName(name).parameters(
                Parameter.builder().parameterName("enable_user_activity_logging").parameterValue("true").build(),
                Parameter.builder().parameterName("statement_timeout").parameterValue("60000").build()));
        client.resetClusterParameterGroup(r -> r.parameterGroupName(name).resetAllParameters(false)
                .parameters(Parameter.builder().parameterName("enable_user_activity_logging").build()));
        assertThat(client.describeClusterParameters(r -> r.parameterGroupName(name).source("user")).parameters())
                .singleElement().satisfies(p -> {
                    assertThat(p.parameterName()).isEqualTo("statement_timeout");
                    assertThat(p.parameterValue()).isEqualTo("60000");
                    assertThat(p.source()).isEqualTo("user");
                });
        client.resetClusterParameterGroup(r -> r.parameterGroupName(name).resetAllParameters(true));
        assertThat(client.describeClusterParameters(r -> r.parameterGroupName(name).source("user")).parameters()).isEmpty();
        client.createClusterParameterGroup(r -> r.parameterGroupName(replacement).parameterGroupFamily("redshift-1.0")
                .description("after replacement").tags(owner));
        client.deleteClusterParameterGroup(r -> r.parameterGroupName(name));
        parameterGroupsToCleanup.remove(name);
        assertThatThrownBy(() -> client.describeClusterParameterGroups(r -> r.parameterGroupName(name)))
                .isInstanceOf(ClusterParameterGroupNotFoundException.class);
        assertThat(client.describeClusterParameterGroups(r -> r.parameterGroupName(replacement))
                .parameterGroups().get(0).description()).isEqualTo("after replacement");
        assertThat(client.describeEvents(r -> r.duration(60).sourceType("cluster-parameter-group").sourceIdentifier(name))
                .events()).isNotEmpty().allSatisfy(event -> {
                    assertThat(event.sourceIdentifier()).isEqualTo(name);
                    assertThat(event.date()).isNotNull();
                });
        assertThatThrownBy(() -> client.copyClusterSnapshot(r -> r.sourceSnapshotIdentifier("missing-sdk-snapshot")
                .targetSnapshotIdentifier("missing-sdk-snapshot-copy")))
                .isInstanceOf(ClusterSnapshotNotFoundException.class);
        assertThatThrownBy(() -> client.describeClusterSubnetGroups(r -> r.clusterSubnetGroupName("missing-sdk-subnet-group")))
                .isInstanceOf(ClusterSubnetGroupNotFoundException.class);
    }

    @Test
    @DisplayName("JDBC readiness does not retry permanent authentication or database failures")
    void jdbcReadinessRejectsPermanentFailures() {
        assertThat(isPermanentConnectionFailure(new SQLException("Invalid password", "28P01"))).isTrue();
        assertThat(isPermanentConnectionFailure(new SQLException("Invalid authorization", "28000"))).isTrue();
        assertThat(isPermanentConnectionFailure(new SQLException("Missing database", "3D000"))).isTrue();
        PSQLException backendFailure = new PSQLException(new ServerErrorMessage(
                "SFATAL\0C08006\0MBackend database authentication failed\0\0"));
        assertThat(isPermanentConnectionFailure(backendFailure)).isTrue();
    }

    @Test
    @DisplayName("JDBC readiness still retries connection and startup failures")
    void jdbcReadinessRetainsTransientRetries() {
        assertThat(isPermanentConnectionFailure(new SQLException("Connection refused", "08001"))).isFalse();
        assertThat(isPermanentConnectionFailure(new SQLException("Connection reset", "08006"))).isFalse();
        PSQLException unavailable = new PSQLException(new ServerErrorMessage(
                "SFATAL\0C08006\0Mcould not connect to backend database\0\0"));
        assertThat(isPermanentConnectionFailure(unavailable)).isFalse();
        assertThat(isPermanentConnectionFailure(new SQLException("Database starting", "57P03"))).isFalse();
    }

    private static boolean isPermanentConnectionFailure(SQLException failure) {
        String state = failure.getSQLState();
        if (state != null && (state.startsWith("28") || state.equals("3D000"))) {
            return true;
        }
        // The shared proxy currently reports backend authentication failures as connection errors.
        return "08006".equals(state)
                && failure instanceof PSQLException postgres
                && postgres.getServerErrorMessage() != null
                && "Backend database authentication failed".equals(postgres.getServerErrorMessage().getMessage());
    }

    private static Connection awaitPostgresConnection(String host, int port, String username, String password) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                Properties properties = new Properties();
                properties.setProperty("user", username);
                properties.setProperty("password", password);
                properties.setProperty("sslmode", "disable");
                properties.setProperty("connectTimeout", "5");
                properties.setProperty("socketTimeout", "5");
                return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + DATABASE, properties);
            } catch (SQLException e) {
                if (isPermanentConnectionFailure(e)) {
                    throw e;
                }
                last = e;
                Thread.sleep(1000);
            }
        }
        throw last != null ? last : new SQLException("Timed out waiting for PostgreSQL connection at " + host + ":" + port);
    }
}
