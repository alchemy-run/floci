package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The domain data plane accepts SigV4 ({@code es}) requests whose caller is granted the
 * {@code es:ESHttp<Method>} action on {@code <domainArn>/<path>}, the grant Alchemy's DomainRead /
 * DomainWrite bindings attach to a Lambda role, and refuses unsigned, tampered, or unauthorized
 * requests.
 */
class OpenSearchDataPlaneAuthTest {

    private static final String HOST = "songs.us-east-1.es.localhost.floci.io:4566";
    private static final String DOMAIN_ARN = "arn:aws:es:us-east-1:000000000000:domain/songs";
    private static final String ROLE_KEY = "ASIAROLESESSION";
    private static final String ROLE_SECRET = "role-secret";
    private static final String ROLE_TOKEN = "role-token";
    private static final String ROLE_ARN = "arn:aws:sts::000000000000:assumed-role/fn/session";
    private static final String GRANT = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":[\"es:ESHttpGet\",\"es:ESHttpHead\"],"
            + "\"Resource\":[\"" + DOMAIN_ARN + "\",\"" + DOMAIN_ARN + "/*\"]}]}";

    private IamService iam;
    private OpenSearchDataPlaneAuth auth;

    @BeforeEach
    void setUp() {
        iam = mock(IamService.class);
        when(iam.findSecretKey(ROLE_KEY, ROLE_TOKEN)).thenReturn(Optional.of(ROLE_SECRET));
        when(iam.resolveCallerContext(ROLE_KEY)).thenReturn(CallerContext.of(List.of(GRANT)));
        when(iam.resolveCallerArn(ROLE_KEY)).thenReturn(Optional.of(ROLE_ARN));
        ObjectMapper mapper = new ObjectMapper();
        auth = new OpenSearchDataPlaneAuth(iam, new IamPolicyEvaluator(mapper), mapper);
    }

    @Test
    void actionAndResourceFollowTheHttpMethodAndPath() {
        assertEquals("es:ESHttpGet", OpenSearchDataPlaneAuth.action("GET"));
        assertEquals("es:ESHttpHead", OpenSearchDataPlaneAuth.action("HEAD"));
        assertEquals("es:ESHttpDelete", OpenSearchDataPlaneAuth.action("delete"));
        assertEquals(DOMAIN_ARN + "/songs/_doc/1", OpenSearchDataPlaneAuth.resourceArn(DOMAIN_ARN, "/songs/_doc/1"));
        assertEquals(DOMAIN_ARN + "/", OpenSearchDataPlaneAuth.resourceArn(DOMAIN_ARN, "/"));
    }

    @Test
    void grantedRoleMayReadWithASignedRequest() throws Exception {
        String query = "source=%7B%22query%22%3A%7B%22match%22%3A%7B%22title%22%3A%22wind%22%7D%7D%7D"
                + "&source_content_type=application%2Fjson";
        Map<String, String> headers = sign("GET", "/songs/_search", query, new byte[0], null);
        assertDoesNotThrow(() -> auth.authorize("GET", "/songs/_search", query, headers, new byte[0],
                DOMAIN_ARN, null));
        Map<String, String> head = sign("HEAD", "/songs/_doc/1", null, new byte[0], null);
        assertDoesNotThrow(() -> auth.authorize("HEAD", "/songs/_doc/1", null, head, new byte[0],
                DOMAIN_ARN, null));
    }

    @Test
    void writeWithoutTheWriteGrantIsDenied() throws Exception {
        byte[] body = "{\"title\":\"The Wind Cries Mary\",\"plays\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = sign("PUT", "/songs/_doc/1", "refresh=true", body, "application/json");
        OpenSearchDataPlaneAuth.Denied denied = assertThrows(OpenSearchDataPlaneAuth.Denied.class,
                () -> auth.authorize("PUT", "/songs/_doc/1", "refresh=true", headers, body, DOMAIN_ARN, null));
        assertEquals(403, denied.status());
        assertTrue(denied.getMessage().contains("es:ESHttpPut"), denied.getMessage());
    }

    @Test
    void domainAccessPolicyGrantsTheCallerWithoutAnIdentityGrant() throws Exception {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},\"Action\":\"es:*\","
                + "\"Resource\":\"" + DOMAIN_ARN + "/*\"}]}";
        when(iam.resolveCallerContext(ROLE_KEY)).thenReturn(CallerContext.of(List.of()));
        byte[] body = "{\"doc\":{\"plays\":2}}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = sign("POST", "/songs/_update/1", "refresh=true", body, "application/json");
        assertDoesNotThrow(() -> auth.authorize("POST", "/songs/_update/1", "refresh=true", headers, body,
                DOMAIN_ARN, policy));
    }

    @Test
    void tamperedBodyPathOrForeignScopeIsRejected() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = sign("GET", "/songs/_search", null, body, "application/json");
        assertThrows(OpenSearchDataPlaneAuth.Denied.class, () -> auth.authorize("GET", "/songs/_search", null,
                headers, "{\"a\":2}".getBytes(StandardCharsets.UTF_8), DOMAIN_ARN, null));
        assertThrows(OpenSearchDataPlaneAuth.Denied.class, () -> auth.authorize("GET", "/other/_search", null,
                headers, body, DOMAIN_ARN, null));
        Map<String, String> wrongToken = new TreeMap<>(headers);
        wrongToken.put("x-amz-security-token", "forged");
        when(iam.findSecretKey(ROLE_KEY, "forged")).thenReturn(Optional.empty());
        assertThrows(OpenSearchDataPlaneAuth.Denied.class, () -> auth.authorize("GET", "/songs/_search", null,
                wrongToken, body, DOMAIN_ARN, null));
        Map<String, String> s3Scoped = new TreeMap<>(headers);
        s3Scoped.put("authorization", headers.get("authorization").replace("/es/aws4_request", "/s3/aws4_request"));
        OpenSearchDataPlaneAuth.Denied denied = assertThrows(OpenSearchDataPlaneAuth.Denied.class,
                () -> auth.authorize("GET", "/songs/_search", null, s3Scoped, body, DOMAIN_ARN, null));
        assertTrue(denied.getMessage().contains("'es'"), denied.getMessage());
    }

    @Test
    void unsignedRequestsNeedAnOpenAccessPolicy() {
        Map<String, String> headers = Map.of("host", HOST);
        OpenSearchDataPlaneAuth.Denied denied = assertThrows(OpenSearchDataPlaneAuth.Denied.class,
                () -> auth.authorize("GET", "/_cluster/health", null, headers, new byte[0], DOMAIN_ARN, null));
        assertTrue(denied.getMessage().startsWith("User: anonymous is not authorized"), denied.getMessage());
        String open = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"es:*\",\"Resource\":\"" + DOMAIN_ARN + "/*\"}]}";
        assertDoesNotThrow(() -> auth.authorize("GET", "/_cluster/health", null, headers, new byte[0],
                DOMAIN_ARN, open));
    }

    @Test
    void canonicalRequestMatchesTheNonS3SigV4Encoding() {
        assertEquals("/songs/_doc/a%252Fb", OpenSearchDataPlaneAuth.canonicalPath("/songs/_doc/a%2Fb"));
        assertEquals("/", OpenSearchDataPlaneAuth.canonicalPath(""));
        assertEquals("refresh=true&source=%7B%22q%22%3A%22a%20b%22%7D",
                OpenSearchDataPlaneAuth.canonicalQuery("source=%7B%22q%22%3A%22a+b%22%7D&refresh=true"));
    }

    /**
     * Signs the way distilled's SigV4 signer does for {@code es} with {@code allHeaders}: host,
     * content-type, x-amz-date and x-amz-security-token signed, payload hashed, path and query
     * canonicalized independently of the class under test.
     */
    private static Map<String, String> sign(String method, String path, String query, byte[] body,
                                            String contentType) throws Exception {
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", HOST);
        String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        headers.put("x-amz-date", date);
        headers.put("x-amz-security-token", ROLE_TOKEN);
        if (contentType != null) {
            headers.put("content-type", contentType);
        }
        String signedHeaders = String.join(";", headers.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        headers.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String canonicalQuery = query == null ? "" : sortedQuery(query);
        String canonical = method + "\n" + path + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + SigV4RequestValidator.sha256Hex(body);
        String scope = date.substring(0, 8) + "/us-east-1/es/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n"
                + SigV4RequestValidator.sha256Hex(canonical);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(ROLE_SECRET, date.substring(0, 8), "us-east-1", "es"),
                stringToSign));
        headers.put("authorization", "AWS4-HMAC-SHA256 Credential=" + ROLE_KEY + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return headers;
    }

    /** The test queries are already RFC 3986 encoded, so canonical form is just key order. */
    private static String sortedQuery(String query) {
        return String.join("&", new java.util.TreeSet<>(List.of(query.split("&"))));
    }
}
