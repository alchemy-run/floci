package io.github.hectorvent.floci.core.common;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the parts of an Authorization header's SigV4 credential scope
 * ({@code Credential=<key>/<date>/<region>/<service>/aws4_request}): the signing service name,
 * and the access key id the request signed with.
 */
public final class SigV4CredentialScope {

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=[^,\\s]+/\\d{8}/[^/,\\s]+/([^/,\\s]+)/aws4_request");
    // SigV4A drops the region segment: Credential=<key>/<date>/<service>/aws4_request
    private static final Pattern SIGV4A_SERVICE_PATTERN =
            Pattern.compile("Credential=[^,\\s]+/\\d{8}/([^/,\\s]+)/aws4_request");
    private static final Pattern ACCESS_KEY_PATTERN = Pattern.compile("Credential=([^/\\s,]+)/");

    private SigV4CredentialScope() {
    }

    public static Optional<String> serviceName(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher sigV4a = SIGV4A_SERVICE_PATTERN.matcher(authorization);
        return sigV4a.find() ? Optional.of(sigV4a.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    /**
     * The access key id the request signed with, which is the credential scope's first segment.
     * Not lower-cased, unlike the service name: an access key id is case sensitive, and in Floci
     * that segment may also carry an account id used for routing.
     */
    public static Optional<String> accessKeyId(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        Matcher matcher = ACCESS_KEY_PATTERN.matcher(authorization);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static Optional<String> serviceNameFromCredential(String credential) {
        if (credential == null) {
            return Optional.empty();
        }
        return serviceName("Credential=" + credential);
    }
}
