package io.floci.aws.services.auditmanager;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tags on Audit Manager assessments, controls and frameworks, through the shared {@code /tags/{resourceArn}} routes. */
@ApplicationScoped
public class AuditManagerTagHandler implements TagHandler {
    private static final int MAX_TAGS = 50;

    private final AuditManagerService service;
    private final AuditManagerAssessments assessments;

    @Inject
    public AuditManagerTagHandler(AuditManagerService service, AuditManagerAssessments assessments) {
        this.service = service;
        this.assessments = assessments;
    }

    @Override
    public String serviceKey() {
        return "auditmanager";
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = new LinkedHashMap<>();
        tagged(arn, region).resource().path("tags").fields()
                .forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        return tags;
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        AuditManagerService.Tagged tagged = tagged(arn, region);
        tags.forEach(tagged.resource().withObject("tags")::put);
        if (tagged.resource().path("tags").size() > MAX_TAGS) {
            throw AuditManagerService.validation("A resource can have at most " + MAX_TAGS + " tags.");
        }
        tagged.save();
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        AuditManagerService.Tagged tagged = tagged(arn, region);
        tagKeys.forEach(tagged.resource().withObject("tags")::remove);
        tagged.save();
    }

    private AuditManagerService.Tagged tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "auditmanager")) {
            throw AuditManagerService.validation("The resource ARN " + arn + " isn't an Audit Manager ARN.");
        }
        String in = AwsArnUtils.parse(arn).region().isEmpty() ? region : AwsArnUtils.parse(arn).region();
        return service.tagged(arn, in).or(() -> assessments.tagged(arn, in)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The resource " + arn + " doesn't exist.", 404));
    }
}
