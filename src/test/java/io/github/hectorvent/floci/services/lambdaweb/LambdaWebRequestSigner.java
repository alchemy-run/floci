package io.github.hectorvent.floci.services.lambdaweb;

import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner.AuthLocation;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Signs a request to a Lambda Web endpoint with the AWS SDK signer, as a client would. */
final class LambdaWebRequestSigner {

    /** What goes on the wire: the method, the path with its query, and the headers to add. */
    record Signed(String method, String pathAndQuery, Map<String, String> headers) {

        Signed withPathAndQuery(String other) {
            return new Signed(method, other, headers);
        }

        Signed withHeader(String name, String value) {
            Map<String, String> copy = new LinkedHashMap<>(headers);
            if (value == null) {
                copy.remove(name);
            } else {
                copy.put(name, value);
            }
            return new Signed(method, pathAndQuery, copy);
        }
    }

    private String accessKeyId = "test";
    private String secretKey = "test";
    private String sessionToken;
    private String region = "us-east-1";
    private String service = "lambda";
    private Instant signedAt = Instant.now();
    private boolean presigned;
    private boolean normalizePath = true;
    private String contentSha256 = "UNSIGNED-PAYLOAD";

    LambdaWebRequestSigner credentials(String accessKeyId, String secretKey, String sessionToken) {
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        return this;
    }

    LambdaWebRequestSigner region(String region) {
        this.region = region;
        return this;
    }

    LambdaWebRequestSigner service(String service) {
        this.service = service;
        return this;
    }

    LambdaWebRequestSigner signedAt(Instant signedAt) {
        this.signedAt = signedAt;
        return this;
    }

    LambdaWebRequestSigner presigned() {
        this.presigned = true;
        return this;
    }

    /** Signs the path as it is sent, without resolving its dot segments. */
    LambdaWebRequestSigner rawPath() {
        this.normalizePath = false;
        return this;
    }

    /** The {@code X-Amz-Content-SHA256} value to send and sign, or null to send none. */
    LambdaWebRequestSigner contentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
        return this;
    }

    Signed sign(String method, String host, String pathAndQuery) {
        URI uri = URI.create("http://" + host + pathAndQuery);
        SdkHttpFullRequest.Builder builder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(uri);
        if (contentSha256 != null) {
            builder.putHeader("X-Amz-Content-SHA256", contentSha256);
        }
        AwsCredentialsIdentity identity = sessionToken == null
                ? AwsCredentialsIdentity.create(accessKeyId, secretKey)
                : AwsSessionCredentialsIdentity.create(accessKeyId, secretKey, sessionToken);
        SdkHttpRequest signed = AwsV4HttpSigner.create().sign(request -> request
                .identity(identity)
                .request(builder.build())
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, normalizePath)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, presigned ? AuthLocation.QUERY_STRING : AuthLocation.HEADER)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, presigned ? Duration.ofMinutes(5) : null)
                .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC))).request();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : signed.headers().entrySet()) {
            if (!"Host".equalsIgnoreCase(header.getKey())) {
                headers.put(header.getKey(), String.join(",", header.getValue()));
            }
        }
        if (contentSha256 != null) {
            headers.put("X-Amz-Content-SHA256", contentSha256);
        }
        String query = signed.encodedQueryParameters().map(encoded -> "?" + encoded).orElse("");
        return new Signed(method, signed.encodedPath() + query, headers);
    }
}
