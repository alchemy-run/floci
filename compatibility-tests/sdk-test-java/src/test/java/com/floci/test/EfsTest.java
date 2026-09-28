package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.GroupIdentifier;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.efs.model.*;
import software.amazon.awssdk.services.efs.model.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EFS Elastic File System")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EfsTest {

    private static EfsClient efs;

    @BeforeAll
    static void setup() {
        efs = TestFixtures.efsClient();
    }

    @AfterAll
    static void cleanup() {
        if (efs != null) {
            efs.close();
        }
    }

    @Test
    @Order(1)
    void createWithElasticThroughputMode() {
        String token = TestFixtures.uniqueName("efs-");
        CreateFileSystemResponse response = efs.createFileSystem(r -> r
                .creationToken(token)
                .throughputMode(ThroughputMode.ELASTIC)
        );

        assertThat(response.throughputMode()).isEqualTo(ThroughputMode.ELASTIC);
        assertThat(response.fileSystemId()).startsWith("fs-");
    }

    @Test
    @Order(2)
    void duplicateCreationTokenThrowsFileSystemAlreadyExists() {
        String token = TestFixtures.uniqueName("efs-dup-");
        
        CreateFileSystemResponse response1 = efs.createFileSystem(r -> r.creationToken(token));
        String fsId = response1.fileSystemId();
        
        FileSystemAlreadyExistsException ex = catchThrowableOfType(
                () -> efs.createFileSystem(r -> r.creationToken(token).throughputMode(ThroughputMode.PROVISIONED)),
                FileSystemAlreadyExistsException.class
        );
        
        assertThat(ex).isNotNull();
        assertThat(ex.fileSystemId()).isEqualTo(fsId);
    }

    @Test
    @Order(3)
    void duplicateClientTokenThrowsAccessPointAlreadyExists() {
        String fsToken = TestFixtures.uniqueName("efs-ap-");
        CreateFileSystemResponse fsResponse = efs.createFileSystem(r -> r.creationToken(fsToken));
        String fsId = fsResponse.fileSystemId();
        
        String clientToken = TestFixtures.uniqueName("ap-dup-");
        
        CreateAccessPointResponse apResponse1 = efs.createAccessPoint(r -> r
                .fileSystemId(fsId)
                .clientToken(clientToken)
        );
        String apId = apResponse1.accessPointId();
        
        AccessPointAlreadyExistsException ex = catchThrowableOfType(
                () -> efs.createAccessPoint(r -> r
                        .fileSystemId(fsId)
                        .clientToken(clientToken)
                        .tags(Tag.builder().key("foo").value("bar").build())
                ),
                AccessPointAlreadyExistsException.class
        );
        
        assertThat(ex).isNotNull();
        assertThat(ex.accessPointId()).isEqualTo(apId);
    }

    @Test
    @DisplayName("Regional backup defaults to disabled and policy updates persist")
    void backupPolicyDefaultsAndUpdatesRoundTrip() {
        String fsId = efs.createFileSystem(r -> r.creationToken(TestFixtures.uniqueName("efs-backup")))
                .fileSystemId();
        try {
            assertThat(efs.describeBackupPolicy(r -> r.fileSystemId(fsId)).backupPolicy().statusAsString())
                    .isEqualTo("DISABLED");
            for (String status : List.of("ENABLED", "DISABLED")) {
                assertThat(efs.putBackupPolicy(r -> r.fileSystemId(fsId)
                        .backupPolicy(p -> p.status(status))).backupPolicy().statusAsString()).isEqualTo(status);
                assertThat(efs.describeBackupPolicy(r -> r.fileSystemId(fsId)).backupPolicy().statusAsString())
                        .isEqualTo(status);
            }
        } finally {
            efs.deleteFileSystem(r -> r.fileSystemId(fsId));
        }
    }

    @Test
    @DisplayName("One Zone backup default can be overridden explicitly")
    void oneZoneBackupDefaultAndExplicitOverrides() {
        for (Boolean backup : new Boolean[]{null, false, true}) {
            String fsId = efs.createFileSystem(r -> r.creationToken(TestFixtures.uniqueName("efs-one-zone"))
                    .availabilityZoneName("us-east-1b").backup(backup)).fileSystemId();
            try {
                String expected = Boolean.FALSE.equals(backup) ? "DISABLED" : "ENABLED";
                assertThat(efs.describeBackupPolicy(r -> r.fileSystemId(fsId)).backupPolicy().statusAsString())
                        .isEqualTo(expected);
            } finally {
                efs.deleteFileSystem(r -> r.fileSystemId(fsId));
            }
        }
    }

    @Test
    @DisplayName("Replication description distinguishes missing file systems from absent replication")
    void describeUnreplicatedFileSystemReturnsTypedError() {
        String fsId = efs.createFileSystem(r -> r.creationToken(TestFixtures.uniqueName("efs-replication")))
                .fileSystemId();
        try {
            assertThatThrownBy(() -> efs.describeReplicationConfigurations(r -> r.fileSystemId(fsId)))
                    .isInstanceOf(ReplicationNotFoundException.class);
        } finally {
            efs.deleteFileSystem(r -> r.fileSystemId(fsId));
        }
        assertThatThrownBy(() -> efs.describeReplicationConfigurations(r -> r.fileSystemId(fsId)))
                .isInstanceOf(FileSystemNotFoundException.class);
        assertThat(efs.describeReplicationConfigurations(r -> r.maxResults(10)).replications()).isEmpty();
    }

    @Test
    @DisplayName("Mount targets use EC2 subnet metadata, allocated ENIs, and VPC security groups")
    void mountTargetsUseSubnetNetworkAndReleaseEnis() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(r -> r.cidrBlock("10.86.0.0/16")).vpc().vpcId();
            String otherVpcId = ec2.createVpc(r -> r.cidrBlock("10.85.0.0/16")).vpc().vpcId();
            List<String> subnetIds = new ArrayList<>();
            List<String> targetIds = new ArrayList<>();
            String extraGroupId = ec2.createSecurityGroup(r -> r.vpcId(vpcId)
                    .groupName(TestFixtures.uniqueName("efs-extra")).description("EFS mount target test")).groupId();
            String fsId = efs.createFileSystem(r -> r.creationToken(TestFixtures.uniqueName("efs-network")))
                    .fileSystemId();
            try {
                Subnet subnet = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.86.1.0/24")
                        .availabilityZone("us-east-1b")).subnet();
                subnetIds.add(subnet.subnetId());
                Subnet sameAz = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.86.2.0/24")
                        .availabilityZone("us-east-1b")).subnet();
                subnetIds.add(sameAz.subnetId());
                Subnet otherAz = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.86.3.0/24")
                        .availabilityZone("us-east-1c")).subnet();
                subnetIds.add(otherAz.subnetId());
                Subnet foreignSubnet = ec2.createSubnet(r -> r.vpcId(otherVpcId).cidrBlock("10.85.1.0/24")
                        .availabilityZone("us-east-1a")).subnet();
                subnetIds.add(foreignSubnet.subnetId());
                String defaultGroupId = ec2.describeSecurityGroups(r -> r.filters(
                        Filter.builder().name("vpc-id").values(vpcId).build(),
                        Filter.builder().name("group-name").values("default").build()))
                        .securityGroups().get(0).groupId();
                String foreignGroupId = ec2.describeSecurityGroups(r -> r.filters(
                        Filter.builder().name("vpc-id").values(otherVpcId).build(),
                        Filter.builder().name("group-name").values("default").build()))
                        .securityGroups().get(0).groupId();

                CreateMountTargetResponse target = efs.createMountTarget(r -> r.fileSystemId(fsId)
                        .subnetId(subnet.subnetId()));
                targetIds.add(target.mountTargetId());
                assertThat(target.vpcId()).isEqualTo(vpcId);
                assertThat(target.availabilityZoneName()).isEqualTo(subnet.availabilityZone());
                assertThat(target.availabilityZoneId()).isEqualTo(subnet.availabilityZoneId());
                assertThat(target.ownerId()).isEqualTo(subnet.ownerId());
                assertThat(target.ipAddress()).startsWith("10.86.1.");
                assertThat(efs.describeMountTargetSecurityGroups(r -> r.mountTargetId(target.mountTargetId()))
                        .securityGroups()).containsExactly(defaultGroupId);
                NetworkInterface eni = ec2.describeNetworkInterfaces(r -> r
                        .networkInterfaceIds(target.networkInterfaceId())).networkInterfaces().get(0);
                assertThat(eni.privateIpAddress()).isEqualTo(target.ipAddress());
                assertThat(eni.subnetId()).isEqualTo(subnet.subnetId());
                assertThat(eni.groups()).extracting(GroupIdentifier::groupId).containsExactly(defaultGroupId);

                assertThatThrownBy(() -> efs.createMountTarget(r -> r.fileSystemId(fsId)
                        .subnetId(sameAz.subnetId()))).isInstanceOf(MountTargetConflictException.class);
                assertThatThrownBy(() -> efs.createMountTarget(r -> r.fileSystemId(fsId)
                        .subnetId(foreignSubnet.subnetId()))).isInstanceOf(BadRequestException.class);
                assertThatThrownBy(() -> efs.createMountTarget(r -> r.fileSystemId(fsId)
                        .subnetId("subnet-00000000000000000"))).isInstanceOf(SubnetNotFoundException.class);
                assertThatThrownBy(() -> efs.createMountTarget(r -> r.fileSystemId(fsId)
                        .subnetId(otherAz.subnetId()).securityGroups(foreignGroupId)))
                        .isInstanceOf(SecurityGroupNotFoundException.class);

                efs.modifyMountTargetSecurityGroups(r -> r.mountTargetId(target.mountTargetId())
                        .securityGroups(defaultGroupId, extraGroupId));
                assertThat(efs.describeMountTargetSecurityGroups(r -> r.mountTargetId(target.mountTargetId()))
                        .securityGroups()).containsExactly(defaultGroupId, extraGroupId);
                assertThat(ec2.describeNetworkInterfaces(r -> r.networkInterfaceIds(target.networkInterfaceId()))
                        .networkInterfaces().get(0).groups()).extracting(GroupIdentifier::groupId)
                        .containsExactly(defaultGroupId, extraGroupId);
                assertThatThrownBy(() -> efs.modifyMountTargetSecurityGroups(r -> r
                        .mountTargetId(target.mountTargetId()).securityGroups(foreignGroupId)))
                        .isInstanceOf(SecurityGroupNotFoundException.class);
                assertThat(efs.describeMountTargetSecurityGroups(r -> r.mountTargetId(target.mountTargetId()))
                        .securityGroups()).containsExactly(defaultGroupId, extraGroupId);

                String occupiedEni = ec2.createNetworkInterface(r -> r.subnetId(otherAz.subnetId())
                        .privateIpAddress("10.86.3.25")).networkInterface().networkInterfaceId();
                try {
                    assertThatThrownBy(() -> efs.createMountTarget(r -> r.fileSystemId(fsId)
                            .subnetId(otherAz.subnetId()).ipAddress("10.86.3.25")))
                            .isInstanceOfSatisfying(IpAddressInUseException.class, error -> {
                                assertThat(error.statusCode()).isEqualTo(409);
                                assertThat(error.errorCode()).isEqualTo("IpAddressInUse");
                            });
                    assertThat(efs.describeFileSystems(r -> r.fileSystemId(fsId)).fileSystems().get(0)
                            .numberOfMountTargets()).isEqualTo(1);
                } finally {
                    ec2.deleteNetworkInterface(r -> r.networkInterfaceId(occupiedEni));
                }
                CreateMountTargetResponse second = efs.createMountTarget(r -> r.fileSystemId(fsId)
                        .subnetId(otherAz.subnetId()).ipAddress("10.86.3.25").securityGroups(extraGroupId));
                targetIds.add(second.mountTargetId());
                assertThat(second.availabilityZoneName()).isNotEqualTo(target.availabilityZoneName());
                assertThat(second.ipAddress()).isEqualTo("10.86.3.25");
                assertThat(efs.describeMountTargets(r -> r.fileSystemId(fsId)).mountTargets()).hasSize(2);
                assertThat(efs.describeFileSystems(r -> r.fileSystemId(fsId)).fileSystems().get(0)
                        .numberOfMountTargets()).isEqualTo(2);
                assertThatThrownBy(() -> efs.deleteFileSystem(r -> r.fileSystemId(fsId)))
                        .isInstanceOf(FileSystemInUseException.class);

                for (CreateMountTargetResponse created : List.of(target, second)) {
                    efs.deleteMountTarget(r -> r.mountTargetId(created.mountTargetId()));
                    targetIds.remove(created.mountTargetId());
                    assertThatThrownBy(() -> efs.describeMountTargets(r -> r.mountTargetId(created.mountTargetId())))
                            .isInstanceOf(MountTargetNotFoundException.class);
                    assertThatThrownBy(() -> ec2.describeNetworkInterfaces(r -> r
                            .networkInterfaceIds(created.networkInterfaceId())))
                            .isInstanceOfSatisfying(Ec2Exception.class, error -> assertThat(error.awsErrorDetails().errorCode())
                                    .isEqualTo("InvalidNetworkInterfaceID.NotFound"));
                }
                assertThat(efs.describeFileSystems(r -> r.fileSystemId(fsId)).fileSystems().get(0)
                        .numberOfMountTargets()).isZero();
            } finally {
                for (String targetId : targetIds) {
                    efs.deleteMountTarget(r -> r.mountTargetId(targetId));
                }
                efs.deleteFileSystem(r -> r.fileSystemId(fsId));
                for (String subnetId : subnetIds) {
                    ec2.deleteSubnet(r -> r.subnetId(subnetId));
                }
                ec2.deleteSecurityGroup(r -> r.groupId(extraGroupId));
                ec2.deleteVpc(r -> r.vpcId(vpcId));
                ec2.deleteVpc(r -> r.vpcId(otherVpcId));
            }
        }
    }
}
