package io.floci.aws.testing;

import io.floci.aws.services.ec2.Ec2Service;

/** The ids of us-east-1's default VPC resources, which are shaped as AWS shapes ids and derived from the Region. */
public final class Ec2Defaults {

    public static final String VPC = Ec2Service.defaultVpcId("us-east-1");
    public static final String SUBNET_A = Ec2Service.defaultSubnetId("us-east-1", "a");
    public static final String SUBNET_B = Ec2Service.defaultSubnetId("us-east-1", "b");
    public static final String SUBNET_C = Ec2Service.defaultSubnetId("us-east-1", "c");
    public static final String SECURITY_GROUP = Ec2Service.defaultSecurityGroupId("us-east-1");
    public static final String DHCP_OPTIONS = Ec2Service.defaultDhcpOptionsId("us-east-1");

    private Ec2Defaults() {
    }
}
