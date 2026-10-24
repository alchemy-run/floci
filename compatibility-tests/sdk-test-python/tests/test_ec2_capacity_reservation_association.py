"""Exercise capacity associations through the EC2 SDK against an isolated Floci image."""
import json
import os
import subprocess
import time
import urllib.request
import uuid

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

IMAGE = os.environ.get("FLOCI_CAPACITY_ASSOCIATION_TEST_IMAGE")
pytestmark = pytest.mark.skipif(not IMAGE, reason="Set FLOCI_CAPACITY_ASSOCIATION_TEST_IMAGE for Docker checks")


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


@pytest.fixture(scope="module")
def endpoint():
    name = "floci-capacity-association-" + uuid.uuid4().hex[:12]
    container = docker(
        "run", "-d", "--name", name, "--publish", "127.0.0.1::4566",
        "--env", "FLOCI_SERVICES_EC2_MOCK=true",
        "--env", "FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false",
        "--env", "FLOCI_STORAGE_MODE=memory", IMAGE,
    )
    try:
        port = json.loads(docker("inspect", container))[0]["NetworkSettings"]["Ports"]["4566/tcp"][0]["HostPort"]
        url = "http://127.0.0.1:" + port
        deadline = time.monotonic() + 60
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while True:
            try:
                with opener.open(url + "/_localstack/health", timeout=2) as response:
                    json.load(response)
                break
            except (OSError, ValueError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
        yield url
    finally:
        docker("rm", "-f", "-v", container)
        assert not docker("ps", "-aq", "--filter", "id=" + container)


def client(endpoint, account="111111111111", region="us-east-1"):
    return boto3.client(
        "ec2", endpoint_url=endpoint, region_name=region,
        aws_access_key_id=account, aws_secret_access_key="test",
        config=Config(proxies={}, connect_timeout=2, read_timeout=5,
                      retries={"total_max_attempts": 1}),
    )


@pytest.fixture
def reservations(endpoint):
    ec2 = client(endpoint)
    created = []

    def create(count, **overrides):
        arguments = dict(InstanceType="t3.micro", InstancePlatform="Linux/UNIX",
                         AvailabilityZone="us-east-1a", InstanceCount=count)
        arguments.update(overrides)
        if "AvailabilityZoneId" in overrides:
            arguments.pop("AvailabilityZone")
        reservation = ec2.create_capacity_reservation(**arguments)["CapacityReservation"]
        created.append(reservation["CapacityReservationId"])
        for specification in arguments.get("TagSpecifications", []):
            if specification["ResourceType"] == "capacity-reservation":
                assert reservation["Tags"] == specification["Tags"]
        return reservation["CapacityReservationId"]

    try:
        yield ec2, create
    finally:
        for reservation_id in created:
            record = describe(ec2, reservation_id)
            if record["State"] == "active":
                ec2.cancel_capacity_reservation(CapacityReservationId=reservation_id)
            assert describe(ec2, reservation_id)["State"] == "cancelled"


def describe(ec2, reservation_id):
    return ec2.describe_capacity_reservations(
        CapacityReservationIds=[reservation_id])["CapacityReservations"][0]


def test_targeted_launch_and_none_preference_preserve_eligibility(reservations):
    ec2, create = reservations
    target = create(1, InstanceMatchCriteria="targeted")
    instance_ids = []
    try:
        for specification in [
                {"CapacityReservationPreference": "none"},
                {"CapacityReservationTarget": {"CapacityReservationId": target}}]:
            instance = ec2.run_instances(
                ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
                CapacityReservationSpecification=specification)["Instances"][0]
            instance_ids.append(instance["InstanceId"])
            if "CapacityReservationTarget" in specification:
                assert instance["CapacityReservationId"] == target
                assert instance["CapacityReservationSpecification"] == specification
            else:
                assert "CapacityReservationId" not in instance
        assert describe(ec2, target)["AvailableInstanceCount"] == 0
        with pytest.raises(ClientError):
            ec2.run_instances(
                ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
                CapacityReservationSpecification={"CapacityReservationTarget": {
                    "CapacityReservationId": target}})
        ec2.stop_instances(InstanceIds=[instance_ids[1]])
        assert describe(ec2, target)["AvailableInstanceCount"] == 1
        ec2.start_instances(InstanceIds=[instance_ids[1]])
        assert describe(ec2, target)["AvailableInstanceCount"] == 0
        ec2.cancel_capacity_reservation(CapacityReservationId=target)
        current = ec2.describe_instances(InstanceIds=[instance_ids[1]])["Reservations"][0]["Instances"][0]
        assert "CapacityReservationId" not in current
        assert current["State"]["Name"] == "running"
    finally:
        if instance_ids:
            ec2.terminate_instances(InstanceIds=instance_ids)


def test_new_open_reservation_adopts_running_open_but_not_none(reservations):
    ec2, create = reservations
    instances = []
    try:
        for preference in ("open", "none"):
            instance = ec2.run_instances(
                ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
                CapacityReservationSpecification={"CapacityReservationPreference": preference},
            )["Instances"][0]
            instances.append(instance["InstanceId"])
        before = ec2.describe_instances(InstanceIds=instances)
        assert all(i["State"]["Name"] == "running" for r in before["Reservations"] for i in r["Instances"])
        capacity_id = create(2)
        described = ec2.describe_instances(InstanceIds=instances)
        by_id = {i["InstanceId"]: i for r in described["Reservations"] for i in r["Instances"]}
        assert by_id[instances[0]]["CapacityReservationId"] == capacity_id
        assert "CapacityReservationId" not in by_id[instances[1]]
        assert describe(ec2, capacity_id)["AvailableInstanceCount"] == 1
    finally:
        if instances:
            ec2.terminate_instances(InstanceIds=instances)


@pytest.mark.parametrize("preference", ["none", "targeted"])
def test_fleet_retains_template_capacity_specification(reservations, preference):
    ec2, create = reservations
    capacity_id = create(1, InstanceMatchCriteria="targeted" if preference == "targeted" else "open")
    specification = ({"CapacityReservationPreference": "none"} if preference == "none" else
                     {"CapacityReservationTarget": {"CapacityReservationId": capacity_id}})
    template_id = None
    instances = []
    try:
        template_id = ec2.create_launch_template(
            LaunchTemplateName="capacity-fleet-" + uuid.uuid4().hex,
            LaunchTemplateData={"ImageId": "ami-test", "InstanceType": "t3.micro",
                                "Placement": {"AvailabilityZone": "us-east-1a"},
                                "CapacityReservationSpecification": specification},
        )["LaunchTemplate"]["LaunchTemplateId"]
        fleet = ec2.create_fleet(
            Type="instant", LaunchTemplateConfigs=[{"LaunchTemplateSpecification": {
                "LaunchTemplateId": template_id, "Version": "$Latest"}}],
            TargetCapacitySpecification={"TotalTargetCapacity": 1, "DefaultTargetCapacityType": "on-demand"},
        )
        instances = [i for group in fleet["Instances"] for i in group["InstanceIds"]]
        assert len(instances) == 1
        instance = ec2.describe_instances(InstanceIds=instances)["Reservations"][0]["Instances"][0]
        assert instance.get("CapacityReservationId") == (capacity_id if preference == "targeted" else None)
        assert describe(ec2, capacity_id)["AvailableInstanceCount"] == (0 if preference == "targeted" else 1)
    finally:
        if instances:
            ec2.terminate_instances(InstanceIds=instances)
        if template_id:
            ec2.delete_launch_template(LaunchTemplateId=template_id)


def test_unknown_zone_id_rejected_without_reservation_publication(reservations):
    ec2, create = reservations
    before = ec2.describe_capacity_reservations()["CapacityReservations"]
    with pytest.raises(ClientError) as rejected:
        create(1, AvailabilityZoneId="use1-az99")
    assert rejected.value.response["Error"]["Code"] == "InvalidParameterValue"
    assert ec2.describe_capacity_reservations()["CapacityReservations"] == before
    zone = ec2.describe_availability_zones()["AvailabilityZones"][0]
    reservation_id = create(1, AvailabilityZoneId=zone["ZoneId"])
    assert describe(ec2, reservation_id)["AvailabilityZoneId"] == zone["ZoneId"]
