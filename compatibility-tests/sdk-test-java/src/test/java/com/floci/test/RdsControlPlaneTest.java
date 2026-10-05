package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbSubnetGroupResponse;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.DbSubnetGroupNotFoundException;
import software.amazon.awssdk.services.rds.model.DescribeDbSubnetGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOrderableDbInstanceOptionsResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("RDS Control Plane")
class RdsControlPlaneTest {

    private static RdsClient rds;
    private static String subnetGroupName;
    private static List<String> subnetIds;

    @BeforeAll
    static void setup() {
        rds = TestFixtures.rdsClient();
        subnetGroupName = TestFixtures.uniqueName("rds-subnets");
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSubnetsResponse response = ec2.describeSubnets();
            subnetIds = response.subnets().stream()
                    .map(subnet -> subnet.subnetId())
                    .sorted()
                    .limit(2)
                    .toList();
        }
        assertThat(subnetIds).hasSizeGreaterThanOrEqualTo(2);
    }

    @AfterAll
    static void cleanup() {
        if (rds != null) {
            try {
                rds.deleteDBSubnetGroup(b -> b.dbSubnetGroupName(subnetGroupName));
            } catch (Exception ignored) {
            }
            rds.close();
        }
    }

    @Test
    void sdkUnmarshalsDbSubnetGroupSubnets() {
        CreateDbSubnetGroupResponse createResponse = rds.createDBSubnetGroup(b -> b
                .dbSubnetGroupName(subnetGroupName)
                .dbSubnetGroupDescription("SDK subnet group shape")
                .subnetIds(subnetIds));

        assertThat(createResponse.dbSubnetGroup().subnets())
                .extracting("subnetIdentifier")
                .containsExactlyElementsOf(subnetIds);

        DescribeDbSubnetGroupsResponse describeResponse = rds.describeDBSubnetGroups(b -> b
                .dbSubnetGroupName(subnetGroupName));

        assertThat(describeResponse.dbSubnetGroups()).hasSize(1);
        assertThat(describeResponse.dbSubnetGroups().get(0).subnets())
                .extracting("subnetIdentifier")
                .containsExactlyElementsOf(subnetIds);
    }

    @Test
    void sdkDescribesAndDeletesInstanceAfterItsSubnetGroupIsRemoved() {
        String orphanId = TestFixtures.uniqueName("rds-orphan");
        String healthyId = TestFixtures.uniqueName("rds-healthy");
        List<String> instanceIds = List.of(orphanId, healthyId);

        try {
            for (String id : instanceIds) {
                rds.createDBSubnetGroup(b -> b
                        .dbSubnetGroupName(id)
                        .dbSubnetGroupDescription("SDK orphan recovery")
                        .subnetIds(subnetIds));
                rds.createDBInstance(b -> b
                        .dbInstanceIdentifier(id)
                        .dbSubnetGroupName(id)
                        .engine("mariadb")
                        .engineVersion("11.4.5")
                        .dbInstanceClass("db.t3.micro")
                        .allocatedStorage(20)
                        .masterUsername("admin")
                        .masterUserPassword("secret123"));
            }

            var originalGroup = rds.describeDBSubnetGroups(b -> b.dbSubnetGroupName(orphanId))
                    .dbSubnetGroups().get(0);
            rds.deleteDBSubnetGroup(b -> b.dbSubnetGroupName(orphanId));

            assertAll(
                    () -> {
                        var response = rds.describeDBInstances(b -> b.dbInstanceIdentifier(orphanId));
                        assertThat(response.dbInstances()).hasSize(1);
                        var instance = response.dbInstances().get(0);
                        assertThat(instance.dbInstanceIdentifier()).isEqualTo(orphanId);
                        assertThat(instance.dbSubnetGroup().dbSubnetGroupName()).isEqualTo(orphanId);
                        assertThat(instance.dbSubnetGroup().dbSubnetGroupArn())
                                .isEqualTo(originalGroup.dbSubnetGroupArn());
                        assertThat(instance.dbSubnetGroup().vpcId()).isEqualTo(originalGroup.vpcId());
                        assertThat(instance.dbSubnetGroup().subnetGroupStatus()).isEqualTo("Incomplete");
                        assertThat(instance.dbSubnetGroup().subnets()).isEmpty();
                    },
                    () -> {
                        var instances = rds.describeDBInstances().dbInstances();
                        assertThat(instances).extracting(DBInstance::dbInstanceIdentifier)
                                .contains(orphanId, healthyId);
                        var healthy = instances.stream()
                                .filter(instance -> healthyId.equals(instance.dbInstanceIdentifier()))
                                .findFirst().orElseThrow();
                        assertThat(healthy.dbSubnetGroup().dbSubnetGroupName()).isEqualTo(healthyId);
                        assertThat(healthy.dbSubnetGroup().subnetGroupStatus()).isEqualTo("Complete");
                        assertThat(healthy.dbSubnetGroup().subnets()).extracting("subnetIdentifier")
                                .containsExactlyElementsOf(subnetIds);
                    },
                    () -> {
                        var deleted = rds.deleteDBInstance(b -> b
                                .dbInstanceIdentifier(orphanId).skipFinalSnapshot(true)).dbInstance();
                        assertThat(deleted.dbInstanceIdentifier()).isEqualTo(orphanId);
                        assertThat(deleted.dbSubnetGroup().subnetGroupStatus()).isEqualTo("Incomplete");
                        assertThat(deleted.dbSubnetGroup().subnets()).isEmpty();
                        assertThatThrownBy(() -> rds.describeDBInstances(b -> b.dbInstanceIdentifier(orphanId)))
                                .isInstanceOf(DbInstanceNotFoundException.class);
                        assertThatThrownBy(() -> rds.describeDBSubnetGroups(b -> b.dbSubnetGroupName(orphanId)))
                                .isInstanceOf(DbSubnetGroupNotFoundException.class);
                        assertThat(rds.describeDBInstances(b -> b.dbInstanceIdentifier(healthyId)).dbInstances())
                                .extracting(DBInstance::dbInstanceIdentifier).containsExactly(healthyId);
                    });
        } finally {
            for (String id : instanceIds) {
                try {
                    rds.deleteDBInstance(b -> b.dbInstanceIdentifier(id).skipFinalSnapshot(true));
                } catch (DbInstanceNotFoundException alreadyDeleted) {
                    System.getLogger(RdsControlPlaneTest.class.getName())
                            .log(System.Logger.Level.DEBUG, "DB instance already removed: {0}", id);
                }
                try {
                    rds.deleteDBSubnetGroup(b -> b.dbSubnetGroupName(id));
                } catch (DbSubnetGroupNotFoundException alreadyDeleted) {
                    System.getLogger(RdsControlPlaneTest.class.getName())
                            .log(System.Logger.Level.DEBUG, "DB subnet group already removed: {0}", id);
                }
            }
        }
    }

    @Test
    void sdkDiscoversCurrentSmallGravitonPostgresOption() {
        DescribeOrderableDbInstanceOptionsResponse response = rds.describeOrderableDBInstanceOptions(b -> b
                .engine("postgres")
                .engineVersion("16.14")
                .dbInstanceClass("db.t4g.small"));

        assertThat(response.orderableDBInstanceOptions()).hasSize(1);
        assertThat(response.orderableDBInstanceOptions().get(0).engine()).isEqualTo("postgres");
        assertThat(response.orderableDBInstanceOptions().get(0).engineVersion()).isEqualTo("16.14");
        assertThat(response.orderableDBInstanceOptions().get(0).dbInstanceClass()).isEqualTo("db.t4g.small");
    }
}
