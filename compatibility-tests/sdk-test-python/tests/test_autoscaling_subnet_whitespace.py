"""CreateAutoScalingGroup delimiter parity observed on AWS, 2026-10-12."""

import uuid
from contextlib import ExitStack, closing

import boto3


def test_subnet_comma_space_is_normalized(ec2_client, aws_config, client_config):
    name = "subnet-whitespace-" + uuid.uuid4().hex[:12]
    region = aws_config["region_name"]
    zones = [region + "a", region + "b"]
    with closing(boto3.client("autoscaling", config=client_config, **aws_config)) as autoscaling:
        with ExitStack() as cleanup:
            vpc = ec2_client.create_vpc(CidrBlock="10.98.0.0/16")["Vpc"]["VpcId"]
            cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
            subnets = []
            for index, zone in enumerate(zones):
                subnet = ec2_client.create_subnet(
                    VpcId=vpc,
                    CidrBlock=f"10.98.{index}.0/24",
                    AvailabilityZone=zone,
                )["Subnet"]["SubnetId"]
                cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
                subnets.append(subnet)
            image = ec2_client.register_image(
                Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1"
            )["ImageId"]
            cleanup.callback(ec2_client.deregister_image, ImageId=image)
            template = ec2_client.create_launch_template(
                LaunchTemplateName=name,
                LaunchTemplateData={"ImageId": image, "InstanceType": "t3.micro"},
            )["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)

            configurations = []
            for suffix, separator in [("space", ", "), ("no-space", ",")]:
                group_name = name + "-" + suffix
                autoscaling.create_auto_scaling_group(
                    AutoScalingGroupName=group_name,
                    MinSize=0,
                    MaxSize=0,
                    DesiredCapacity=0,
                    AvailabilityZones=zones,
                    LaunchTemplate={"LaunchTemplateId": template, "Version": "1"},
                    VPCZoneIdentifier=separator.join(subnets),
                )
                cleanup.callback(
                    autoscaling.delete_auto_scaling_group,
                    AutoScalingGroupName=group_name,
                    ForceDelete=True,
                )
                groups = autoscaling.describe_auto_scaling_groups(
                    AutoScalingGroupNames=[group_name]
                )["AutoScalingGroups"]
                assert len(groups) == 1
                group = groups[0]
                assert group["VPCZoneIdentifier"] == ",".join(subnets)
                assert set(group["AvailabilityZones"]) == set(zones)
                assert group["Instances"] == []
                assert group["MinSize"] == group["MaxSize"] == group["DesiredCapacity"] == 0
                configurations.append({
                    key: value for key, value in group.items()
                    if key not in {"AutoScalingGroupName", "AutoScalingGroupARN", "CreatedTime"}
                })
            assert configurations[0] == configurations[1]
