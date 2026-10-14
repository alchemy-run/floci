package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.S3Object;

import java.time.Instant;

/**
 * A request that resolved to a delete marker. S3 answers a request naming the marker's version id
 * with 405 MethodNotAllowed plus {@code Last-Modified}, and a request for a key whose current
 * version is a marker with 404 NoSuchKey; both carry {@code x-amz-delete-marker: true} and the
 * marker's {@code x-amz-version-id}.
 */
final class S3DeleteMarkerException extends AwsException {

    private final String versionId;
    private final Instant lastModified;
    private final boolean selectedByVersionId;

    private S3DeleteMarkerException(String code, String message, int status, S3Object marker,
                                    boolean selectedByVersionId) {
        super(code, message, status);
        this.versionId = marker.getVersionId() != null ? marker.getVersionId() : "null";
        this.lastModified = marker.getLastModified();
        this.selectedByVersionId = selectedByVersionId;
    }

    static S3DeleteMarkerException selected(S3Object marker) {
        return new S3DeleteMarkerException("MethodNotAllowed",
                "The specified method is not allowed against this resource.", 405, marker, true);
    }

    static S3DeleteMarkerException current(S3Object marker) {
        return new S3DeleteMarkerException("NoSuchKey", "The specified key does not exist.", 404, marker, false);
    }

    String versionId() {
        return versionId;
    }

    Instant lastModified() {
        return lastModified;
    }

    boolean selectedByVersionId() {
        return selectedByVersionId;
    }
}
