package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;

import java.util.Optional;

/**
 * Something attached to a bucket that keeps it from being deleted, such as an S3 Files file system. Discovered
 * through CDI, so S3 doesn't import the service that owns the attachment. Asked in the deleting caller's request
 * context, before the bucket's emptiness is checked.
 */
public interface S3BucketDeletionGuard {

    /** The error {@code DeleteBucket} fails with while something is attached to the bucket, if anything is. */
    Optional<AwsException> refuseDeletion(String bucketName);
}
