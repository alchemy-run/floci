package io.github.hectorvent.floci.services.rum;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} for CloudWatch RUM app monitors on {@code /tags/{ResourceArn}}.
 *
 * <p>RUM uses a capitalized {@code Tags} map, a lowercase {@code tagKeys} query parameter on
 * untag, POST for TagResource, and answers both mutations with an empty HTTP 200.
 */
@ApplicationScoped
public class RumTagHandler implements TagHandler {

    private final RumService service;

    @Inject
    public RumTagHandler(RumService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "rum";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public boolean strictTagValidation() {
        return true;
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
        return service.listTagsForResource(region, arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(region, arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(region, arn, tagKeys);
    }
}
