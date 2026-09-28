package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class MacieTagHandler implements TagHandler {
    private final MacieService service;
    private final RegionResolver regionResolver;

    @Inject
    public MacieTagHandler(MacieService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    @Override
    public String serviceKey() {
        return "macie2";
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
        return service.resourceTags(region, regionResolver.getAccountId(), arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.changeTags(region, regionResolver.getAccountId(), arn, tags, List.of());
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.changeTags(region, regionResolver.getAccountId(), arn, Map.of(), tagKeys);
    }
}
