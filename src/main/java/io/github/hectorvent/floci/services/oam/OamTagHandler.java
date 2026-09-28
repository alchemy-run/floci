package io.github.hectorvent.floci.services.oam;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OamTagHandler implements TagHandler {
    private final OamService service;

    @Inject
    public OamTagHandler(OamService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "oam";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public boolean tagResourceUsesPut() {
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
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.tags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tag(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untag(arn, tagKeys);
    }
}
