package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for GuardDuty.
 *
 * <p>Supports detector ARNs and their filter, ipset, and threatintelset children.
 * The wire shape is a {@code tags} map and a {@code tagKeys} query parameter on untag.
 */
@ApplicationScoped
public class GuardDutyTagHandler implements TagHandler {

    private final GuardDutyService service;

    @Inject
    public GuardDutyTagHandler(GuardDutyService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "guardduty";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        validateRegion(region, arn);
        return service.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        validateRegion(region, arn);
        service.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        validateRegion(region, arn);
        service.untagResource(arn, tagKeys);
    }

    private static void validateRegion(String region, String arn) {
        try {
            if (!region.equals(AwsArnUtils.parse(arn).region())) {
                throw new AwsException("BadRequestException", "The resource ARN belongs to a different region.", 400);
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "The resource ARN is invalid.", 400);
        }
    }
}
