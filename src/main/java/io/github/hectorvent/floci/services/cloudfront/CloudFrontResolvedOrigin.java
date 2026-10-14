package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Origin settings for one viewer request, never written to the distribution or OAC stores. */
public record CloudFrontResolvedOrigin(Origin origin, AccessControl accessControl) {

    public record AccessControl(boolean enabled, String signingBehavior, String signingProtocol, String originType) {
        public static AccessControl configured(OriginAccessControl control) {
            return control == null ? null : new AccessControl(true, control.getSigningBehavior(),
                    control.getSigningProtocol(), control.getOriginAccessControlOriginType());
        }

        private static AccessControl parse(JsonNode node) {
            if (!node.isObject() || !node.path("enabled").isBoolean()) {
                throw new IllegalArgumentException("originAccessControlConfig.enabled must be a boolean.");
            }
            boolean enabled = node.path("enabled").booleanValue();
            String behavior = setting(node, "signingBehavior", enabled, Set.of("always", "never", "no-override"));
            String protocol = setting(node, "signingProtocol", enabled, Set.of("sigv4"));
            String type = setting(node, "originType", enabled, Set.of("s3", "lambda", "mediastore", "mediapackagev2"));
            return new AccessControl(enabled, behavior, protocol, type);
        }

        private static String setting(JsonNode node, String name, boolean required, Set<String> allowed) {
            if (!node.has(name) && !required) {
                return null;
            }
            JsonNode value = node.path(name);
            if (!value.isTextual() || !allowed.contains(value.textValue())) {
                throw new IllegalArgumentException("Invalid originAccessControlConfig." + name + ".");
            }
            return value.textValue();
        }
    }

    public static CloudFrontResolvedOrigin resolve(Origin assigned, JsonNode override, OriginAccessControl control) {
        AccessControl access = AccessControl.configured(control);
        if (override == null) {
            return new CloudFrontResolvedOrigin(assigned, access);
        }
        if (!override.isObject()) {
            throw new IllegalArgumentException("The origin override must be an object.");
        }
        if (assigned.getVpcOriginConfig() != null) {
            throw new IllegalArgumentException("CloudFront Functions cannot update a VPC origin.");
        }
        Origin origin = copy(assigned);
        if (override.has("domainName")) {
            String domain = text(override, "domainName");
            if (domain.isBlank()) {
                throw new IllegalArgumentException("The origin domainName must not be empty.");
            }
            origin.setDomainName(domain);
            if (isS3RestEndpoint(domain)) {
                origin.setS3OriginConfig(origin.getS3OriginConfig() == null ? Map.of() : origin.getS3OriginConfig());
                origin.setCustomOriginConfig(null);
            } else {
                origin.setS3OriginConfig(null);
                if (origin.getCustomOriginConfig() == null) {
                    origin.setCustomOriginConfig(Map.of());
                }
                // An explicit loopback port is Floci's plain-HTTP development-origin extension.
                if (CloudFrontServingController.isLocalDevOrigin(domain) && !override.has("customOriginConfig")) {
                    origin.setCustomOriginConfig(null);
                }
            }
        }
        if (override.has("originPath")) {
            origin.setOriginPath(text(override, "originPath"));
        }
        if (override.has("originAccessControlConfig")) {
            access = AccessControl.parse(override.get("originAccessControlConfig"));
        }
        if (access != null && access.enabled()) {
            if (!"sigv4".equals(access.signingProtocol())) {
                throw new IllegalArgumentException("Invalid originAccessControlConfig.signingProtocol.");
            }
            if (!"s3".equals(access.originType())) {
                throw new IllegalArgumentException("Dynamic OAC signing is only supported for S3 origins.");
            }
            if (!isS3RestEndpoint(origin.getDomainName())) {
                throw new IllegalArgumentException("S3 origin access control requires an S3 REST endpoint.");
            }
            origin.setS3OriginConfig(origin.getS3OriginConfig() == null ? Map.of() : origin.getS3OriginConfig());
            origin.setCustomOriginConfig(null);
        }
        if (override.has("customOriginConfig") && !CloudFrontRequestRouter.isS3Origin(origin)) {
            JsonNode custom = override.get("customOriginConfig");
            if (!custom.isObject()) {
                throw new IllegalArgumentException("customOriginConfig must be an object.");
            }
            Map<String, Object> settings = origin.getCustomOriginConfig() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(origin.getCustomOriginConfig());
            if (custom.has("protocol")) {
                String protocol = text(custom, "protocol");
                if (!Set.of("http", "https").contains(protocol)) {
                    throw new IllegalArgumentException("Invalid customOriginConfig.protocol.");
                }
                settings.put("OriginProtocolPolicy", protocol + "-only");
            }
            if (custom.has("port")) {
                if (!custom.get("port").isIntegralNumber() || !custom.get("port").canConvertToInt()
                        || custom.get("port").intValue() < 1 || custom.get("port").intValue() > 65535) {
                    throw new IllegalArgumentException("Invalid customOriginConfig.port.");
                }
                settings.put("HTTPPort", custom.get("port").asText());
                settings.put("HTTPSPort", custom.get("port").asText());
            }
            origin.setCustomOriginConfig(settings);
        }
        return new CloudFrontResolvedOrigin(origin, access);
    }

    private static String text(JsonNode node, String name) {
        if (!node.path(name).isTextual()) {
            throw new IllegalArgumentException(name + " must be a string.");
        }
        return node.path(name).textValue();
    }

    private static boolean isS3RestEndpoint(String domain) {
        if (domain == null) {
            return false;
        }
        String bucket = CloudFrontRequestRouter.bucketFromS3Domain(domain);
        return bucket != null && bucket.length() < domain.length() && !domain.contains(":")
                && !domain.substring(bucket.length() + 1).toLowerCase(Locale.ROOT).startsWith("s3-website");
    }

    private static Origin copy(Origin assigned) {
        Origin origin = new Origin();
        origin.setId(assigned.getId());
        origin.setDomainName(assigned.getDomainName());
        origin.setOriginPath(assigned.getOriginPath());
        origin.setOriginAccessControlId(assigned.getOriginAccessControlId());
        origin.setS3OriginConfig(assigned.getS3OriginConfig());
        origin.setCustomOriginConfig(assigned.getCustomOriginConfig());
        origin.setVpcOriginConfig(assigned.getVpcOriginConfig());
        origin.setConnectionAttempts(assigned.getConnectionAttempts());
        origin.setConnectionTimeout(assigned.getConnectionTimeout());
        origin.setResponseCompletionTimeout(assigned.getResponseCompletionTimeout());
        origin.setCustomHeaders(assigned.getCustomHeaders());
        return origin;
    }
}
