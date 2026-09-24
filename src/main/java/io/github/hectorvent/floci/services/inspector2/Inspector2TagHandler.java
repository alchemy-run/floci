package io.github.hectorvent.floci.services.inspector2;

import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class Inspector2TagHandler implements TagHandler {
    private final Inspector2Service service;
    private final RequestContext requestContext;

    @Inject
    public Inspector2TagHandler(Inspector2Service service, RequestContext requestContext) {
        this.service = service;
        this.requestContext = requestContext;
    }

    @Override
    public String serviceKey() {
        return "inspector2";
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
        return service.listFilterTags(region, requestContext.getAccountId(), arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagFilter(region, requestContext.getAccountId(), arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagFilter(region, requestContext.getAccountId(), arn, tagKeys);
    }
}
