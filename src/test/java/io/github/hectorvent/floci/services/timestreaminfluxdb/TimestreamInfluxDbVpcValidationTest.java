package io.github.hectorvent.floci.services.timestreaminfluxdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.timestreaminfluxdb.TimestreamInfluxDbValidation.VpcResources;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimestreamInfluxDbVpcValidationTest {

    private static final String REGION = "us-east-1";
    private static final VpcResources EC2 = vpcResources(
            Map.of("subnet-default-us-east-1-a", "vpc-default-us-east-1",
                    "subnet-default-us-east-1-b", "vpc-default-us-east-1",
                    "subnet-0abc", "vpc-0custom"),
            Map.of("sg-default-us-east-1", "vpc-default-us-east-1",
                    "sg-0abc", "vpc-0custom"));

    static VpcResources vpcResources(Map<String, String> subnets, Map<String, String> securityGroups) {
        return new VpcResources() {
            @Override
            public Optional<String> subnetVpc(String region, String subnetId) {
                return Optional.ofNullable(subnets.get(subnetId));
            }

            @Override
            public Optional<String> securityGroupVpc(String region, String groupId) {
                return Optional.ofNullable(securityGroups.get(groupId));
            }
        };
    }

    @Test
    void flociDefaultVpcResourcesAreAcceptedDespiteTheirHyphenatedIds() throws Exception {
        List<String> subnets = TimestreamInfluxDbValidation.subnetIds(new ObjectMapper().readTree("""
                {"vpcSubnetIds":["subnet-default-us-east-1-a","subnet-default-us-east-1-b"]}"""), true);
        List<String> groups = TimestreamInfluxDbValidation.securityGroupIds(new ObjectMapper().readTree("""
                {"vpcSecurityGroupIds":["sg-default-us-east-1"]}"""), true);

        assertDoesNotThrow(() -> TimestreamInfluxDbValidation.requireVpcResources(EC2, REGION, subnets, groups));
    }

    @Test
    void unknownIdsFailThePatternOrTheExistenceCheck() {
        assertEquals("The subnet ID 'subnet-abc123' does not exist.", message(List.of("subnet-abc123"), null));
        assertEquals("vpcSubnetIds contains an invalid value.", message(List.of("subnet-Not_Valid"), null));
        assertEquals("The security group 'sg-abc123' does not exist.",
                message(List.of("subnet-default-us-east-1-a"), List.of("sg-abc123")));
        assertEquals("vpcSecurityGroupIds contains an invalid value.",
                message(List.of("subnet-default-us-east-1-a"), List.of("group-1")));
    }

    @Test
    void subnetsAndSecurityGroupsMustShareOneVpc() {
        assertEquals("All vpcSubnetIds must belong to the same VPC; they span [vpc-default-us-east-1, vpc-0custom].",
                message(List.of("subnet-default-us-east-1-a", "subnet-0abc"), null));
        assertEquals("The security group 'sg-0abc' belongs to VPC vpc-0custom, not to VPC vpc-default-us-east-1 "
                        + "of the vpcSubnetIds.",
                message(List.of("subnet-default-us-east-1-a"), List.of("sg-0abc")));
        assertDoesNotThrow(() -> TimestreamInfluxDbValidation.requireVpcResources(EC2, REGION,
                List.of("subnet-0abc"), List.of("sg-0abc")));
    }

    @Test
    void omittedListsAreNotChecked() {
        assertDoesNotThrow(() -> TimestreamInfluxDbValidation.requireVpcResources(EC2, REGION, null, null));
        assertDoesNotThrow(() -> TimestreamInfluxDbValidation.requireVpcResources(EC2, REGION, null,
                List.of("sg-0abc")));
    }

    @Test
    void ec2BackedLookupReadsSubnetAndGroupVpcsAndTreatsUnknownGroupsAsAbsent() {
        Ec2Service ec2 = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-default-us-east-1-a");
        subnet.setVpcId("vpc-default-us-east-1");
        subnet.setRegion(REGION);
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-default-us-east-1");
        group.setVpcId("vpc-default-us-east-1");
        when(ec2.findSubnetById(REGION, "subnet-default-us-east-1-a")).thenReturn(Optional.of(subnet));
        when(ec2.findSubnetById(REGION, "subnet-missing")).thenReturn(Optional.empty());
        when(ec2.describeSecurityGroups(REGION, List.of("sg-default-us-east-1"), List.of(), Map.of()))
                .thenReturn(List.of(group));
        when(ec2.describeSecurityGroups(REGION, List.of("sg-missing"), List.of(), Map.of()))
                .thenThrow(new AwsException("InvalidGroup.NotFound", "missing", 400));

        VpcResources lookup = VpcResources.of(ec2);

        assertEquals(Optional.of("vpc-default-us-east-1"), lookup.subnetVpc(REGION, "subnet-default-us-east-1-a"));
        assertEquals(Optional.empty(), lookup.subnetVpc(REGION, "subnet-missing"));
        assertEquals(Optional.of("vpc-default-us-east-1"), lookup.securityGroupVpc(REGION, "sg-default-us-east-1"));
        assertEquals(Optional.empty(), lookup.securityGroupVpc(REGION, "sg-missing"));
    }

    @Test
    void subnetsOfAnotherRegionDoNotCount() {
        Ec2Service ec2 = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-0west");
        subnet.setVpcId("vpc-0west");
        subnet.setRegion("us-west-2");
        when(ec2.findSubnetById(REGION, "subnet-0west")).thenReturn(Optional.of(subnet));

        assertEquals(Optional.empty(), VpcResources.of(ec2).subnetVpc(REGION, "subnet-0west"));
    }

    private static String message(List<String> subnets, List<String> groups) {
        AwsException error = assertThrows(AwsException.class,
                () -> TimestreamInfluxDbValidation.requireVpcResources(EC2, REGION, subnets, groups));
        assertEquals("ValidationException", error.getErrorCode());
        return error.getMessage();
    }
}
