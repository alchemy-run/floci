package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApsDataPlaneAuthTest {

    private static final String WORKSPACE = "ws-11111111-2222-3333-4444-555555555555";
    private static final URI WRITE = URI.create("https://" + WORKSPACE
            + ".localhost.floci.io:4566/workspaces/" + WORKSPACE + "/api/v1/remote_write");
    private static final String ARN = "arn:aws:aps:us-east-1:000000000000:workspace/" + WORKSPACE;
    private IamService iam;
    private IamPolicyEvaluator evaluator;
    private ApsDataPlaneAuth auth;

    @BeforeEach
    void setUp() {
        iam = mock(IamService.class);
        evaluator = mock(IamPolicyEvaluator.class);
        auth = new ApsDataPlaneAuth(iam, evaluator, mock(IamEnforcementFilter.class),
                new RegionResolver("us-east-1", "000000000000"));
    }

    @Test
    void verifiesBinaryRemoteWriteWithoutChangingPayload() throws Exception {
        byte[] bytes = {0, -1, 3, 8, 10};
        Map<String, String> headers = sign("POST", WRITE, bytes, "test", "test", "aps", null);
        CredentialScope scope = auth.verify("POST", WRITE, headers, bytes);
        assertEquals("us-east-1", scope.region());
        assertEquals("aps", scope.service());
        assertEquals("test", scope.accessKeyId());
        auth.authorize(scope, headers.get("authorization"), ARN, "aps:RemoteWrite");
        verifyNoInteractions(iam, evaluator);
    }

    @Test
    void validatesTemporaryCredentialTokenAndWorkspacePolicy() throws Exception {
        when(iam.findSecretKey("ASIAEXAMPLE", "issued-token")).thenReturn(Optional.of("secret"));
        CallerContext caller = CallerContext.of(List.of("policy"));
        when(iam.resolveCallerContext("ASIAEXAMPLE")).thenReturn(caller);
        when(iam.resolveCallerArn("ASIAEXAMPLE")).thenReturn(Optional.of("arn:aws:iam::000000000000:role/metrics"));
        when(evaluator.evaluate(any(CallerContext.class), isNull(), eq("aps:RemoteWrite"), eq(ARN), anyMap()))
                .thenReturn(IamPolicyEvaluator.Decision.DENY);
        byte[] bytes = {8, 3};
        Map<String, String> headers = sign("POST", WRITE, bytes, "ASIAEXAMPLE", "secret", "aps", "issued-token");
        CredentialScope scope = auth.verify("POST", WRITE, headers, bytes);
        assertThrows(AwsException.class,
                () -> auth.authorize(scope, headers.get("authorization"), ARN, "aps:RemoteWrite"));
        verify(iam).findSecretKey("ASIAEXAMPLE", "issued-token");
    }

    @Test
    void refusesUnsignedUnknownKeyWrongServiceAndModifiedBytes() throws Exception {
        byte[] bytes = {0, 1, 2};
        assertThrows(AwsException.class, () -> auth.verify("POST", WRITE, Map.of(), bytes));
        Map<String, String> unknown = sign("POST", WRITE, bytes, "unknown", "secret", "aps", null);
        assertThrows(AwsException.class, () -> auth.verify("POST", WRITE, unknown, bytes));
        Map<String, String> wrongService = sign("POST", WRITE, bytes, "test", "test", "s3", null);
        assertThrows(AwsException.class, () -> auth.verify("POST", WRITE, wrongService, bytes));
        Map<String, String> valid = sign("POST", WRITE, bytes, "test", "test", "aps", null);
        assertThrows(AwsException.class, () -> auth.verify("POST", WRITE, valid, new byte[]{0, 1, 3}));
    }

    @Test
    void verifiesQueryAndFormBodiesAndRejectsChangedParameters() throws Exception {
        URI query = URI.create("https://" + WORKSPACE + ".localhost.floci.io:4566/workspaces/" + WORKSPACE
                + "/api/v1/series?match%5B%5D=up&match%5B%5D=rate%28requests%5B5m%5D%29");
        Map<String, String> queryHeaders = sign("GET", query, new byte[0], "test", "test", "aps", null);
        assertNotNull(auth.verify("GET", query, queryHeaders, new byte[0]));
        URI changedQuery = URI.create(query.toString().replace("=up", "=down"));
        assertThrows(AwsException.class, () -> auth.verify("GET", changedQuery, queryHeaders, new byte[0]));
        byte[] form = "query=sum%28up%29&timeout=30s".getBytes(StandardCharsets.UTF_8);
        URI formUri = URI.create(query.toString().split("\\?")[0].replace("/series", "/query"));
        Map<String, String> formHeaders = sign("POST", formUri, form, "test", "test", "aps", null);
        assertNotNull(auth.verify("POST", formUri, formHeaders, form));
    }

    @Test
    void canonicalQuerySortsKeysAndValuesWithoutDroppingRepeatedMatchers() {
        assertEquals("a=z&a-=b&match%5B%5D=a%20b&match%5B%5D=z", ApsDataPlaneAuth.canonicalQuery(
                "match%5B%5D=z&a-=b&match%5B%5D=a+b&a=z"));
    }

    private static Map<String, String> sign(String method, URI uri, byte[] body, String key, String secret,
                                             String service, String token) throws Exception {
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", uri.getRawAuthority());
        headers.put("x-amz-date", DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now()));
        if (token != null) {
            headers.put("x-amz-security-token", token);
        }
        String signedHeaders = String.join(";", headers.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        headers.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String canonical = method + "\n" + ApsDataPlaneAuth.canonicalPath(uri) + "\n"
                + ApsDataPlaneAuth.canonicalQuery(uri.getRawQuery()) + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + SigV4RequestValidator.sha256Hex(body);
        String date = headers.get("x-amz-date").substring(0, 8);
        String scope = date + "/us-east-1/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + headers.get("x-amz-date") + "\n" + scope + "\n"
                + SigV4RequestValidator.sha256Hex(canonical);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(secret, date, "us-east-1", service), stringToSign));
        headers.put("authorization", "AWS4-HMAC-SHA256 Credential=" + key + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return headers;
    }
}
