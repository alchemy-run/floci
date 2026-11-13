package io.floci.aws.services.backup;

import io.floci.aws.core.common.TagHandler;
import io.floci.aws.services.backupsearch.BackupSearchService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BackupTagHandler implements TagHandler {

    private final BackupService service;

    @Inject
    public BackupTagHandler(BackupService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "backup";
    }

    /** Search and export job ARNs are AWS Backup Search's ({@code BackupSearchTagHandler}). */
    @Override
    public boolean handles(String arn) {
        return !BackupSearchService.owns(arn);
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(arn, tagKeys);
    }
}
