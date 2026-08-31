package io.github.hectorvent.floci.services.amp;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** AMP scrapers use ARN service {@code aps} and a lowercase {@code tags} map. */
@ApplicationScoped
public class AmpTagHandler implements TagHandler {

    private final AmpService service;

    @Inject
    public AmpTagHandler(AmpService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "aps";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.listTags(region, arn);
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
