package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3HeaderSignatureFilterTest {

    @Test
    void canonicalPathDecodesEncodedSlashesOnce() {
        assertEquals("/bucket/lambda/hash.zip", S3HeaderSignatureFilter.canonicalPath("/bucket/lambda%2Fhash.zip"));
        assertEquals("/bucket/lambda/hash.zip", S3HeaderSignatureFilter.canonicalPath("/bucket/lambda%2fhash.zip"));
        assertEquals("/bucket/lambda%252Fhash.zip", S3HeaderSignatureFilter.canonicalPath("/bucket/lambda%252Fhash.zip"));
        assertNotEquals(S3HeaderSignatureFilter.canonicalPath("/bucket/lambda%2Fhash.zip"),
                S3HeaderSignatureFilter.canonicalPath("/bucket/lambda%252Fhash.zip"));
    }

    @Test
    void canonicalPathPreservesObjectKeyIdentity() {
        assertEquals("/bucket/a%2Bb", S3HeaderSignatureFilter.canonicalPath("/bucket/a+b"));
        assertEquals("/bucket/a%2Bb", S3HeaderSignatureFilter.canonicalPath("/bucket/a%2bb"));
        assertEquals("/bucket/a%20b", S3HeaderSignatureFilter.canonicalPath("/bucket/a%20b"));
        assertEquals("/bucket/a//b/./c", S3HeaderSignatureFilter.canonicalPath("/bucket/a/%2Fb/./c"));
        assertEquals("/bucket/caf%C3%A9%21", S3HeaderSignatureFilter.canonicalPath("/bucket/caf%c3%a9!"));
        assertNotEquals(S3HeaderSignatureFilter.canonicalPath("/bucket/a+b"),
                S3HeaderSignatureFilter.canonicalPath("/bucket/a%20b"));
    }

    @Test
    void canonicalPathHandlesRootAndRejectsMalformedEscapes() {
        assertEquals("/", S3HeaderSignatureFilter.canonicalPath(null));
        assertEquals("/", S3HeaderSignatureFilter.canonicalPath(""));
        assertEquals("/", S3HeaderSignatureFilter.canonicalPath("/"));
        assertThrows(IllegalArgumentException.class, () -> S3HeaderSignatureFilter.canonicalPath("/bucket/%2"));
        assertThrows(IllegalArgumentException.class, () -> S3HeaderSignatureFilter.canonicalPath("/bucket/%zz"));
    }
}
