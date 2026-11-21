package io.floci.aws.services.appregistry;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Tags on AppRegistry applications and attribute groups, through the shared {@code /tags/{resourceArn}} routes. */
@ApplicationScoped
public class AppRegistryTagHandler implements TagHandler {
    private final AppRegistryService service;

    @Inject
    public AppRegistryTagHandler(AppRegistryService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "servicecatalog";
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
        return service.listTags(arn, regionOf(arn, region));
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tag(arn, tags, regionOf(arn, region));
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untag(arn, tagKeys, regionOf(arn, region));
    }

    private static String regionOf(String arn, String region) {
        return AwsArnUtils.isArn(arn) && !AwsArnUtils.parse(arn).region().isEmpty()
                ? AwsArnUtils.parse(arn).region() : region;
    }
}
