package io.github.hectorvent.floci.services.backupsearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupSearchRoutingFilterTest {

    private static final String BACKUP_SEARCH_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup-search/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesBackupSearchCredentialScope() {
        assertTrue(BackupSearchRoutingFilter.isBackupSearch(BACKUP_SEARCH_AUTH));
        assertFalse(BackupSearchRoutingFilter.isBackupSearch(S3_AUTH));
        assertFalse(BackupSearchRoutingFilter.isBackupSearch(null));
        assertFalse(BackupSearchRoutingFilter.isBackupSearch(""));
    }

    @Test
    void prefixesSearchJobPathsAndStripsTrailingSlash() {
        assertEquals("/aws-backup-search/search-jobs",
                BackupSearchRoutingFilter.rewritePath("/search-jobs"));
        assertEquals("/aws-backup-search/search-jobs",
                BackupSearchRoutingFilter.rewritePath("/search-jobs/"));
        assertEquals("/aws-backup-search/search-jobs/00000000-0000-0000-0000-000000000000",
                BackupSearchRoutingFilter.rewritePath(
                        "/search-jobs/00000000-0000-0000-0000-000000000000"));
        assertEquals(
                "/aws-backup-search/search-jobs/00000000-0000-0000-0000-000000000000/search-results",
                BackupSearchRoutingFilter.rewritePath(
                        "/search-jobs/00000000-0000-0000-0000-000000000000/search-results"));
        assertEquals(
                "/aws-backup-search/search-jobs/00000000-0000-0000-0000-000000000000/backups",
                BackupSearchRoutingFilter.rewritePath(
                        "/search-jobs/00000000-0000-0000-0000-000000000000/backups"));
        assertEquals(
                "/aws-backup-search/search-jobs/00000000-0000-0000-0000-000000000000/actions/cancel",
                BackupSearchRoutingFilter.rewritePath(
                        "/search-jobs/00000000-0000-0000-0000-000000000000/actions/cancel"));
    }

    @Test
    void prefixesExportJobPaths() {
        assertEquals("/aws-backup-search/export-search-jobs",
                BackupSearchRoutingFilter.rewritePath("/export-search-jobs"));
        assertEquals("/aws-backup-search/export-search-jobs/00000000-0000-0000-0000-000000000000",
                BackupSearchRoutingFilter.rewritePath(
                        "/export-search-jobs/00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:backup-search:us-east-1:000000000000:search-job/abc",
                BackupSearchRoutingFilter.rewritePath(
                        "/tags/arn:aws:backup-search:us-east-1:000000000000:search-job/abc"));
        assertEquals("/aws-backup-search/search-jobs",
                BackupSearchRoutingFilter.rewritePath("/aws-backup-search/search-jobs"));
    }
}
