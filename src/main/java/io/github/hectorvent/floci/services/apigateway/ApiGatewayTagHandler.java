package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for API Gateway.
 *
 * <p>Tags belong to the exact REST API, stage, or custom domain named by the ARN.
 * Stage ARNs use {@code /restapis/<apiId>/stages/<stageName>}; other REST API subresources
 * are not taggable.
 */
@ApplicationScoped
public class ApiGatewayTagHandler implements TagHandler {

    private record TagTarget(String resourceType, String resourceId, String stageName) {}

    private final ApiGatewayService service;

    @Inject
    public ApiGatewayTagHandler(ApiGatewayService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "apigateway";
    }

    @Override
    public boolean tagResourceUsesPut() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        TagTarget target = targetFromArn(arn);
        if ("domainnames".equals(target.resourceType())) {
            return service.getDomainNameTags(region, target.resourceId());
        }
        return target.stageName() != null
                ? service.getStageTags(region, target.resourceId(), target.stageName())
                : service.getTags(region, target.resourceId());
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        TagTarget target = targetFromArn(arn);
        if ("domainnames".equals(target.resourceType())) {
            service.tagDomainName(region, target.resourceId(), tags);
        } else if (target.stageName() != null) {
            service.tagStage(region, target.resourceId(), target.stageName(), tags);
        } else {
            service.tagResource(region, target.resourceId(), tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        TagTarget target = targetFromArn(arn);
        if ("domainnames".equals(target.resourceType())) {
            service.untagDomainName(region, target.resourceId(), tagKeys);
        } else if (target.stageName() != null) {
            service.untagStage(region, target.resourceId(), target.stageName(), tagKeys);
        } else {
            service.untagResource(region, target.resourceId(), tagKeys);
        }
    }

    private static TagTarget targetFromArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw invalidArn(arn);
        }
        if (!"apigateway".equals(parsed.service()) || parsed.partition().isBlank()
                || parsed.region().isBlank() || !parsed.accountId().isEmpty()) {
            throw invalidArn(arn);
        }
        String[] parts = parsed.resource().split("/", -1);
        if (parts.length < 3 || !parts[0].isEmpty() || !validSegment(parts[2])) {
            throw invalidArn(arn);
        }
        if (parts.length == 3 && ("restapis".equals(parts[1]) || "domainnames".equals(parts[1]))) {
            return new TagTarget(parts[1], parts[2], null);
        }
        if (parts.length == 5 && "restapis".equals(parts[1]) && "stages".equals(parts[3])
                && validSegment(parts[4])) {
            return new TagTarget(parts[1], parts[2], parts[4]);
        }
        throw invalidArn(arn);
    }

    private static boolean validSegment(String value) {
        return !value.isBlank() && !value.contains("?") && !value.contains("#")
                && value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    private static AwsException invalidArn(String arn) {
        return new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
    }
}
