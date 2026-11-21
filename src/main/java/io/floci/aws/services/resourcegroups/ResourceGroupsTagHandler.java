package io.floci.aws.services.resourcegroups;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Lets the Resource Groups Tagging API read and change a group's tags, which the group itself
 * holds. The account is the one in the group ARN.
 */
@ApplicationScoped
public class ResourceGroupsTagHandler implements TagHandler {
    private final ResourceGroupsService service;

    @Inject
    public ResourceGroupsTagHandler(ResourceGroupsService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "resource-groups";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.getTags(region, account(arn), arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        synchronized (service) {
            service.tagGroup(region, account(arn), service.byArn(region, account(arn), arn), tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        synchronized (service) {
            service.untagGroup(region, account(arn), service.byArn(region, account(arn), arn), tagKeys);
        }
    }

    private static String account(String arn) {
        return AwsArnUtils.parse(arn).accountId();
    }
}
