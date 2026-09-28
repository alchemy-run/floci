package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4AuthorizationHeader;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
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
import java.util.Map;

/** Verifies the original bytes before translating AMP's remote_write path to Prometheus's write path. */
@ApplicationScoped
public class ApsDataPlaneAuth {

    private static final Logger LOG = Logger.getLogger(ApsDataPlaneAuth.class);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final IamEnforcementFilter enforcement;
    private final RegionResolver regionResolver;

    @Inject
    public ApsDataPlaneAuth(IamService iamService, IamPolicyEvaluator evaluator,
                            IamEnforcementFilter enforcement, RegionResolver regionResolver) {
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.enforcement = enforcement;
        this.regionResolver = regionResolver;
    }

    public CredentialScope verify(String method, URI uri, Map<String, String> headers, byte[] body) {
        String authorization = headers.get("authorization");
        SigV4AuthorizationHeader signed = SigV4AuthorizationHeader.parse(authorization);
        if (signed == null || signed.signedHeaders() == null || signed.signature() == null
                || !signed.signature().matches("[0-9a-f]{64}")) {
            throw denied("A valid SigV4 Authorization header is required");
        }
        CredentialScope scope = CredentialScope.parse(signed.credential());
        if (scope == null || !"aps".equals(scope.service())) {
            throw denied("The request must be signed for aps");
        }
        String date = headers.get("x-amz-date");
        try {
            if (date == null || !date.startsWith(scope.date())
                    || Duration.between(Instant.from(AMZ_DATE.parse(date)), Instant.now()).abs()
                            .compareTo(Duration.ofMinutes(5)) > 0) {
                throw denied("The request signing date is invalid or expired");
            }
        } catch (DateTimeParseException e) {
            throw denied("The request signing date is invalid");
        }
        String secret = "test".equals(scope.accessKeyId()) ? "test"
                : iamService.findSecretKey(scope.accessKeyId(), headers.get("x-amz-security-token"))
                        .orElseThrow(() -> denied("The security token included in the request is invalid"));
        StringBuilder canonicalHeaders = new StringBuilder();
        String previous = "";
        for (String name : signed.signedHeaders().split(";", -1)) {
            if (!name.matches("[a-z0-9-]+") || name.compareTo(previous) <= 0 || !headers.containsKey(name)) {
                throw denied("SignedHeaders must be sorted, unique, and present in the request");
            }
            canonicalHeaders.append(name).append(':')
                    .append(SigV4RequestValidator.normalizeHeaderValue(headers.get(name))).append('\n');
            previous = name;
        }
        if (!SigV4RequestValidator.containsHeader(signed.signedHeaders(), "host")
                || !SigV4RequestValidator.containsHeader(signed.signedHeaders(), "x-amz-date")) {
            throw denied("Host and X-Amz-Date must be signed");
        }
        try {
            String payloadHash = SigV4RequestValidator.sha256Hex(body);
            String declaredHash = headers.get("x-amz-content-sha256");
            if (declaredHash != null && !payloadHash.equals(declaredHash)) {
                throw denied("The request payload does not match its signed hash");
            }
            String canonicalRequest = method + "\n" + canonicalPath(uri) + "\n"
                    + canonicalQuery(uri.getRawQuery()) + "\n" + canonicalHeaders + "\n"
                    + signed.signedHeaders() + "\n" + payloadHash;
            String stringToSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope.credentialScope() + "\n"
                    + SigV4RequestValidator.sha256Hex(canonicalRequest);
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(secret, scope.date(), scope.region(), "aps");
            String expected = SigV4RequestValidator.hexEncode(
                    SigV4RequestValidator.hmacSha256(signingKey, stringToSign));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signed.signature().getBytes(StandardCharsets.UTF_8))) {
                throw denied("The request signature does not match");
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv(e, "AMP SigV4 verification failed");
            throw denied("The request signature could not be verified");
        }
        return scope;
    }

    public void authorize(CredentialScope scope, String authorization, String arn, String action) {
        enforcement.authorizeAdditionalResource(authorization, action, arn);
        if ("test".equals(scope.accessKeyId())) {
            return;
        }
        CallerContext caller = iamService.resolveCallerContext(scope.accessKeyId());
        if (caller == null) {
            throw denied("The caller has no IAM identity");
        }
        String principalArn = iamService.resolveCallerArn(scope.accessKeyId()).orElse(null);
        caller = caller.withPrincipalArn(principalArn);
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                principalArn == null ? null : Map.of("aws:PrincipalArn", List.of(principalArn)),
                arn, scope.region(), regionResolver.getAccountId(), regionResolver.getAccountId());
        if (evaluator.evaluate(caller, null, action, arn, context) == IamPolicyEvaluator.Decision.DENY) {
            throw denied("The caller is not authorized to perform " + action + " on " + arn);
        }
    }

    static String canonicalPath(URI uri) {
        return encode(uri.normalize().getRawPath().replaceAll("/+", "/")).replace("%2F", "/");
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<QueryParameter> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            pairs.add(new QueryParameter(encode(URLDecoder.decode(parts[0], StandardCharsets.UTF_8)),
                    encode(URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8))));
        }
        pairs.sort(Comparator.comparing(QueryParameter::name).thenComparing(QueryParameter::value));
        return String.join("&", pairs.stream().map(pair -> pair.name() + "=" + pair.value()).toList());
    }

    private record QueryParameter(String name, String value) {}

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~");
    }

    private static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }
}
