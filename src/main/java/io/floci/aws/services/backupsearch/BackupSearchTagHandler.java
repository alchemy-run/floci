package io.floci.aws.services.backupsearch;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Tags on Backup Search's search and export jobs, whose ARNs are AWS Backup's ({@code arn:aws:backup:...}). */
@ApplicationScoped
public class BackupSearchTagHandler implements TagHandler {
    private final BackupSearchService service;

    @Inject
    public BackupSearchTagHandler(BackupSearchService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "backup";
    }

    @Override
    public boolean handles(String arn) {
        return BackupSearchService.owns(arn);
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
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
        String own = AwsArnUtils.parse(arn).region();
        return own.isEmpty() ? region : own;
    }
}
