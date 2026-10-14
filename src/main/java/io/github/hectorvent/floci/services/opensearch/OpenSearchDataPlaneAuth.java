package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4AuthorizationHeader;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authenticates and authorizes a request to a domain's REST data plane the way the service
 * front end does: the request must be SigV4-signed for {@code es} with a known credential, and
 * the {@code es:ESHttp<Method>} action on {@code <domainArn>/<path>} must be allowed by the
 * caller's identity policies or the domain's access policy. An unsigned request is served only
 * when the access policy allows it to anyone.
 */
@ApplicationScoped
public class OpenSearchDataPlaneAuth {

    private static final Logger LOG = Logger.getLogger(OpenSearchDataPlaneAuth.class);
    private static final String SERVICE = "es";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(15);

    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final ObjectMapper objectMapper;

    /** Denial carrying the HTTP status and message the data plane answers with. */
    public static final class Denied extends RuntimeException {
        private final int status;

        Denied(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    @Inject
    public OpenSearchDataPlaneAuth(IamService iamService, IamPolicyEvaluator evaluator,
                                   ObjectMapper objectMapper) {
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
    }

    /** {@code es:ESHttpGet}, {@code es:ESHttpPut}, ... for an HTTP method. */
    public static String action(String method) {
        String upper = method.toUpperCase(Locale.ROOT);
        return "es:ESHttp" + upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
    }

    /** {@code <domainArn>/<path>}, the resource an ESHttp action is evaluated against. */
    public static String resourceArn(String domainArn, String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return domainArn + "/" + URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * Throws {@link Denied} unless the request may proceed.
     *
     * @param headers   request headers with lower-cased names
     * @param rawPath   the raw request path as the client sent it
     * @param rawQuery  the raw query string, or null
     */
    public void authorize(String method, String rawPath, String rawQuery, Map<String, String> headers,
                          byte[] body, String domainArn, String accessPolicy) {
        String action = action(method);
        String resource = resourceArn(domainArn, rawPath);
        String authorization = headers.get("authorization");
        if (authorization == null || authorization.isBlank()) {
            if (anonymousAllowed(accessPolicy, action, resource)) {
                return;
            }
            throw new Denied(403, "User: anonymous is not authorized to perform: " + action
                    + " because no resource-based policy allows the " + action + " action");
        }
        CredentialScope scope = verify(method, rawPath, rawQuery, headers, body);
        if ("test".equals(scope.accessKeyId())) {
            return;
        }
        CallerContext caller = iamService.resolveCallerContext(scope.accessKeyId());
        if (caller == null) {
            throw new Denied(403, "The security token included in the request is invalid.");
        }
        String principalArn = iamService.resolveCallerArn(scope.accessKeyId()).orElse(null);
        caller = caller.withPrincipalArn(principalArn);
        String accountId = accountOf(domainArn);
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                principalArn == null ? null : Map.of("aws:PrincipalArn", List.of(principalArn)),
                resource, scope.region(), accountId, accountId);
        List<String> resourcePolicies = accessPolicy == null || accessPolicy.isBlank()
                ? null : List.of(accessPolicy);
        if (evaluator.evaluate(caller, resourcePolicies, action, resource, context)
                == IamPolicyEvaluator.Decision.DENY) {
            throw new Denied(403, "User: " + (principalArn != null ? principalArn : scope.accessKeyId())
                    + " is not authorized to perform: " + action + " on resource: " + resource);
        }
    }

    CredentialScope verify(String method, String rawPath, String rawQuery, Map<String, String> headers,
                           byte[] body) {
        SigV4AuthorizationHeader signed = SigV4AuthorizationHeader.parse(headers.get("authorization"));
        if (signed == null || signed.credential() == null || signed.signedHeaders() == null
                || signed.signature() == null || !signed.signature().matches("[0-9a-f]{64}")) {
            throw new Denied(403, "Authorization header requires 'Credential', 'SignedHeaders' and "
                    + "'Signature' parameters.");
        }
        CredentialScope scope = CredentialScope.parse(signed.credential());
        if (scope == null) {
            throw new Denied(403, "Credential should be scoped to a valid region.");
        }
        if (!SERVICE.equals(scope.service())) {
            throw new Denied(403, "Credential should be scoped to correct service: '" + SERVICE + "'.");
        }
        String date = headers.get("x-amz-date");
        try {
            if (date == null || !date.startsWith(scope.date())
                    || Duration.between(Instant.from(AMZ_DATE.parse(date)), Instant.now()).abs()
                            .compareTo(MAX_CLOCK_SKEW) > 0) {
                throw new Denied(403, "Signature expired or the request date is invalid.");
            }
        } catch (DateTimeParseException e) {
            throw new Denied(403, "The request date is invalid.");
        }
        String secret = "test".equals(scope.accessKeyId()) ? "test"
                : iamService.findSecretKey(scope.accessKeyId(), headers.get("x-amz-security-token"))
                        .orElseThrow(() -> new Denied(403,
                                "The security token included in the request is invalid."));
        if (!SigV4RequestValidator.containsHeader(signed.signedHeaders(), "host")) {
            throw new Denied(403, "The host header must be signed.");
        }
        StringBuilder canonicalHeaders = new StringBuilder();
        String previous = "";
        for (String name : signed.signedHeaders().split(";", -1)) {
            if (!name.matches("[a-z0-9-]+") || name.compareTo(previous) <= 0 || !headers.containsKey(name)) {
                throw new Denied(403, "SignedHeaders must be sorted, unique, and present in the request.");
            }
            canonicalHeaders.append(name).append(':')
                    .append(SigV4RequestValidator.normalizeHeaderValue(headers.get(name))).append('\n');
            previous = name;
        }
        try {
            String payloadHash = SigV4RequestValidator.sha256Hex(body == null ? new byte[0] : body);
            String declaredHash = headers.get("x-amz-content-sha256");
            if (declaredHash != null && !"UNSIGNED-PAYLOAD".equals(declaredHash)
                    && !payloadHash.equals(declaredHash)) {
                throw new Denied(403, "The provided 'x-amz-content-sha256' header does not match "
                        + "what was computed.");
            }
            String signedPayload = declaredHash != null ? declaredHash : payloadHash;
            String canonicalRequest = method.toUpperCase(Locale.ROOT) + "\n" + canonicalPath(rawPath) + "\n"
                    + canonicalQuery(rawQuery) + "\n" + canonicalHeaders + "\n"
                    + signed.signedHeaders() + "\n" + signedPayload;
            String stringToSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope.credentialScope() + "\n"
                    + SigV4RequestValidator.sha256Hex(canonicalRequest);
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(
                    secret, scope.date(), scope.region(), SERVICE);
            String expected = SigV4RequestValidator.hexEncode(
                    SigV4RequestValidator.hmacSha256(signingKey, stringToSign));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signed.signature().getBytes(StandardCharsets.UTF_8))) {
                throw new Denied(403, "The request signature we calculated does not match the signature "
                        + "you provided. Check your AWS Secret Access Key and signing method.");
            }
        } catch (Denied e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv(e, "OpenSearch data-plane SigV4 verification failed");
            throw new Denied(403, "The request signature could not be verified.");
        }
        return scope;
    }

    /** Non-S3 SigV4 canonical URI: the raw path with each byte URI-encoded once more. */
    static String canonicalPath(String rawPath) {
        String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath.replaceAll("/+", "/");
        return encode(path).replace("%2F", "/");
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            pairs.add(new String[]{
                    encode(URLDecoder.decode(parts[0], StandardCharsets.UTF_8)),
                    encode(URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8))});
        }
        pairs.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        List<String> joined = new ArrayList<>();
        for (String[] pair : pairs) {
            joined.add(pair[0] + "=" + pair[1]);
        }
        return String.join("&", joined);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~");
    }

    /**
     * True when an unconditional Allow statement grants the action on the resource to every
     * principal ({@code "*"} or {@code {"AWS": "*"}}) and no unconditional statement denies it.
     */
    boolean anonymousAllowed(String accessPolicy, String action, String resource) {
        if (accessPolicy == null || accessPolicy.isBlank()) {
            return false;
        }
        JsonNode document;
        try {
            document = objectMapper.readTree(accessPolicy);
        } catch (Exception e) {
            return false;
        }
        JsonNode statements = document.path("Statement");
        List<JsonNode> list = new ArrayList<>();
        if (statements.isArray()) {
            statements.forEach(list::add);
        } else if (statements.isObject()) {
            list.add(statements);
        }
        boolean allowed = false;
        for (JsonNode statement : list) {
            if (!matchesAny(statement.path("Action"), action) || !matchesAny(statement.path("Resource"), resource)) {
                continue;
            }
            String effect = statement.path("Effect").asText("");
            if ("Deny".equals(effect)) {
                return false;
            }
            if ("Allow".equals(effect) && !statement.has("Condition") && everyone(statement.path("Principal"))) {
                allowed = true;
            }
        }
        return allowed;
    }

    private static boolean everyone(JsonNode principal) {
        if (principal.isTextual()) {
            return "*".equals(principal.asText());
        }
        JsonNode aws = principal.path("AWS");
        if (aws.isTextual()) {
            return "*".equals(aws.asText());
        }
        if (aws.isArray()) {
            for (JsonNode value : aws) {
                if ("*".equals(value.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesAny(JsonNode patterns, String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        if (patterns.isTextual()) {
            return IamPolicyEvaluator.globMatches(patterns.asText().toLowerCase(Locale.ROOT), lowered);
        }
        if (patterns.isArray()) {
            for (JsonNode pattern : patterns) {
                if (IamPolicyEvaluator.globMatches(pattern.asText().toLowerCase(Locale.ROOT), lowered)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String accountOf(String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":", 6);
        return parts.length > 4 ? parts[4] : null;
    }
}
