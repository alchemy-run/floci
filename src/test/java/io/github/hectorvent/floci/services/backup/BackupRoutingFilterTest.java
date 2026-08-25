package io.github.hectorvent.floci.services.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupRoutingFilterTest {

    private static final String BACKUP_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesBackupCredentialScope() {
        assertTrue(BackupRoutingFilter.isBackup(BACKUP_AUTH));
        assertFalse(BackupRoutingFilter.isBackup(S3_AUTH));
        assertFalse(BackupRoutingFilter.isBackup(null));
        assertFalse(BackupRoutingFilter.isBackup(""));
    }

    @Test
    void prefixesBackupVaultPathsAndStripsTrailingSlash() {
        assertEquals("/aws-backup/backup-vaults",
                BackupRoutingFilter.rewritePath("/backup-vaults"));
        assertEquals("/aws-backup/backup-vaults",
                BackupRoutingFilter.rewritePath("/backup-vaults/"));
        assertEquals("/aws-backup/backup-vaults/my-vault",
                BackupRoutingFilter.rewritePath("/backup-vaults/my-vault"));
        assertEquals("/aws-backup/backup-vaults/my-vault/access-policy",
                BackupRoutingFilter.rewritePath("/backup-vaults/my-vault/access-policy"));
    }

    @Test
    void prefixesPlanAndUntagPaths() {
        assertEquals("/aws-backup/backup/plans",
                BackupRoutingFilter.rewritePath("/backup/plans/"));
        assertEquals("/aws-backup/backup/plans/abc/selections",
                BackupRoutingFilter.rewritePath("/backup/plans/abc/selections/"));
        assertEquals("/aws-backup/untag/arn:aws:backup:us-east-1:000000000000:backup-vault:v",
                BackupRoutingFilter.rewritePath(
                        "/untag/arn:aws:backup:us-east-1:000000000000:backup-vault:v"));
    }

    @Test
    void prefixesRestoreCopyAndResourcePaths() {
        assertEquals("/aws-backup/restore-jobs",
                BackupRoutingFilter.rewritePath("/restore-jobs"));
        assertEquals("/aws-backup/restore-jobs/00000000-0000-0000-0000-000000000000/metadata",
                BackupRoutingFilter.rewritePath("/restore-jobs/00000000-0000-0000-0000-000000000000/metadata"));
        assertEquals("/aws-backup/copy-jobs",
                BackupRoutingFilter.rewritePath("/copy-jobs"));
        assertEquals("/aws-backup/resources",
                BackupRoutingFilter.rewritePath("/resources"));
        assertEquals("/aws-backup/supported-resource-types",
                BackupRoutingFilter.rewritePath("/supported-resource-types"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:backup:us-east-1:000000000000:backup-vault:v",
                BackupRoutingFilter.rewritePath(
                        "/tags/arn:aws:backup:us-east-1:000000000000:backup-vault:v"));
        assertEquals("/aws-backup/backup-vaults/my-vault",
                BackupRoutingFilter.rewritePath("/aws-backup/backup-vaults/my-vault"));
    }
}
