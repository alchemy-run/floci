package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for AppConfig.
 *
 * <p>Supported ARN formats:
 * <ul>
 *   <li>{@code arn:aws:appconfig:<region>:<account>:application/<appId>}
 *   <li>{@code arn:aws:appconfig:<region>:<account>:application/<appId>/environment/<envId>}
 *   <li>{@code arn:aws:appconfig:<region>:<account>:application/<appId>/configurationprofile/<profileId>}
 *   <li>{@code arn:aws:appconfig:<region>:<account>:deploymentstrategy/<id>}
 *   <li>{@code arn:aws:appconfig:<region>:<account>:extension/<id>[/<version>]}
 *   <li>{@code arn:aws:appconfig:<region>:<account>:extensionassociation/<id>}
 * </ul>
 */
@ApplicationScoped
public class AppConfigTagHandler implements TagHandler {

    private final AppConfigService service;

    @Inject
    public AppConfigTagHandler(AppConfigService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "appconfig";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        ResourceRef ref = parseArn(arn);
        return switch (ref.type()) {
            case "application" -> service.getApplicationTags(ref.id());
            case "environment" -> service.getEnvironmentTags(ref.id());
            case "configurationprofile" -> service.getProfileTags(ref.id());
            case "deploymentstrategy" -> service.getStrategyTags(ref.id());
            case "extension" -> service.getExtensionTags(ref.id());
            case "extensionassociation" -> service.getAssociationTags(ref.id());
            default -> Map.of();
        };
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        ResourceRef ref = parseArn(arn);
        switch (ref.type()) {
            case "application" -> service.tagApplication(ref.id(), tags);
            case "environment" -> service.tagEnvironment(ref.id(), tags);
            case "configurationprofile" -> service.tagProfile(ref.id(), tags);
            case "deploymentstrategy" -> service.tagStrategy(ref.id(), tags);
            case "extension" -> service.tagExtension(ref.id(), tags);
            case "extensionassociation" -> service.tagAssociation(ref.id(), tags);
            default -> {
            }
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        ResourceRef ref = parseArn(arn);
        switch (ref.type()) {
            case "application" -> service.untagApplication(ref.id(), tagKeys);
            case "environment" -> service.untagEnvironment(ref.id(), tagKeys);
            case "configurationprofile" -> service.untagProfile(ref.id(), tagKeys);
            case "deploymentstrategy" -> service.untagStrategy(ref.id(), tagKeys);
            case "extension" -> service.untagExtension(ref.id(), tagKeys);
            case "extensionassociation" -> service.untagAssociation(ref.id(), tagKeys);
            default -> {
            }
        }
    }

    private record ResourceRef(String type, String id) {}

    private static ResourceRef parseArn(String arn) {
        String resource;
        try {
            resource = AwsArnUtils.parse(arn).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
        }
        String[] parts = resource.split("/");
        if (parts.length >= 2 && "application".equals(parts[0])) {
            if (parts.length == 2) return new ResourceRef("application", parts[1]);
            if (parts.length == 4) return new ResourceRef(parts[2], parts[3]);
            if (parts.length == 6) return new ResourceRef(parts[4], parts[5]);
        }
        if (parts.length >= 2 && "deploymentstrategy".equals(parts[0])) {
            return new ResourceRef("deploymentstrategy", parts[1]);
        }
        if (parts.length >= 2 && "extension".equals(parts[0])) {
            return new ResourceRef("extension", parts[1]);
        }
        if (parts.length >= 2 && "extensionassociation".equals(parts[0])) {
            return new ResourceRef("extensionassociation", parts[1]);
        }
        throw new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
    }
}
