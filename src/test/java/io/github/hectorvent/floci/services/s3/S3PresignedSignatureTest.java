package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3PresignedSignatureTest {

    private static final String AMZ_DATE = "20260818T120000Z";
    private static final String CREDENTIAL = "test/20260818/us-east-1/s3/aws4_request";
    private static final String SCOPE = "20260818/us-east-1/s3/aws4_request";
    private static final String SIGNED_HEADERS = "content-type;host";

    @Test
    void signedHeadersIncludeIsCaseInsensitive() {
        assertTrue(S3PresignedSignature.signedHeadersInclude("content-type;host", "Content-Type"));
        assertTrue(S3PresignedSignature.signedHeadersInclude("host", "host"));
        assertFalse(S3PresignedSignature.signedHeadersInclude("host", "content-type"));
    }

    @Test
    void matchingContentTypeProducesStableSignature() {
        String matching = sign("text/plain");
        String again = sign("text/plain");
        assertEquals(matching, again);
        assertTrue(S3PresignedSignature.matches(matching, again));
    }

    @Test
    void mismatchedContentTypeChangesSignature() {
        String signedForPlain = sign("text/plain");
        String signedForJson = sign("application/json");
        assertFalse(S3PresignedSignature.matches(signedForPlain, signedForJson));
    }

    private static String sign(String contentType) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", CREDENTIAL);
        query.put("X-Amz-Date", AMZ_DATE);
        query.put("X-Amz-Expires", "900");
        query.put("X-Amz-SignedHeaders", SIGNED_HEADERS);
        String encodedQuery = S3PresignedSignature.encodeQuery(query);
        String encodedPath = S3PresignedSignature.encodeS3Path("/presign-bucket/file.txt");
        String canonicalHeaders = S3PresignedSignature.canonicalHeaders(
                SIGNED_HEADERS, "localhost:4566", Map.of("content-type", contentType));
        return S3PresignedSignature.signature(
                "PUT", encodedPath, encodedQuery, canonicalHeaders, SIGNED_HEADERS,
                AMZ_DATE, SCOPE, "test");
    }
}
