package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.Tag;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Recycle Bin retention for EC2: how long a deleted EBS snapshot ({@code EBS_SNAPSHOT}), AMI ({@code EC2_IMAGE}) or
 * EBS volume ({@code EBS_VOLUME}) with these tags stays recoverable, or empty when no retention rule keeps it.
 */
public interface Ec2RecycleBin {

    Optional<Duration> retention(String region, String resourceType, List<Tag> tags);
}
