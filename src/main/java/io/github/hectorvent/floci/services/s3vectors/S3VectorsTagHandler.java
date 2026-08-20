package io.github.hectorvent.floci.services.s3vectors;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Shared {@code /tags/{resourceArn}} handler for S3 Vectors buckets and indexes.
 *
 * <p>AWS uses the default lowercase {@code tags} map + {@code tagKeys} query shape.
 */
@ApplicationScoped
public class S3VectorsTagHandler implements TagHandler {

    private final S3VectorsService service;

    @Inject
    public S3VectorsTagHandler(S3VectorsService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "s3vectors";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.listTags(arn, region);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(arn, tags, region);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(arn, tagKeys, region);
    }
}
