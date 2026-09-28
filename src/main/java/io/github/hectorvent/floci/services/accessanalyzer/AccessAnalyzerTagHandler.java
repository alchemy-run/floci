package io.github.hectorvent.floci.services.accessanalyzer;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AccessAnalyzerTagHandler implements TagHandler {
    private final AccessAnalyzerService service;

    @Inject
    public AccessAnalyzerTagHandler(AccessAnalyzerService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "access-analyzer";
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
        return service.listTags(region, arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tag(region, arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untag(region, arn, tagKeys);
    }
}
