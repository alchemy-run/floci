package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.codeartifact.CodeartifactClient;
import software.amazon.awssdk.services.codeartifact.model.*;
import software.amazon.awssdk.services.codeartifact.model.Tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CodeArtifact")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactTest {

    private static final Logger LOG = Logger.getLogger(CodeArtifactTest.class);

    private static CodeartifactClient codeArtifact;

    private static final String DOMAIN = "compat-test-domain";
    private static final String STORE_REPO = "compat-test-store";
    private static final String REPO = "compat-test-repo";

    private static String domainArn;
    private static String repositoryArn;

    @BeforeAll
    static void setup() {
        codeArtifact = TestFixtures.codeArtifactClient();
    }

    @AfterAll
    static void cleanup() {
        if (codeArtifact == null) return;
        try {
            codeArtifact.deleteRepository(r -> r.domain(DOMAIN).repository(REPO));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to delete repository %s during test cleanup", REPO);
        }
        try {
            codeArtifact.deleteRepository(r -> r.domain(DOMAIN).repository(STORE_REPO));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to delete repository %s during test cleanup", STORE_REPO);
        }
        try {
            codeArtifact.deleteDomain(r -> r.domain(DOMAIN));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to delete domain %s during test cleanup", DOMAIN);
        }
        codeArtifact.close();
    }

    @Test
    @Order(10)
    @DisplayName("CreateDomain - creates a domain with tags")
    void createDomain() {
        CreateDomainResponse resp = codeArtifact.createDomain(r -> r
                .domain(DOMAIN)
                .tags(Tag.builder().key("owner").value("platform").build()));

        domainArn = resp.domain().arn();
        assertThat(resp.domain().name()).isEqualTo(DOMAIN);
        assertThat(domainArn).contains("domain/" + DOMAIN);
        assertThat(resp.domain().status()).isEqualTo(DomainStatus.ACTIVE);
        assertThat(resp.domain().repositoryCount()).isZero();
    }

    @Test
    @Order(11)
    @DisplayName("CreateDomain - duplicate returns ConflictException")
    void createDomainDuplicateFails() {
        assertThatThrownBy(() -> codeArtifact.createDomain(r -> r.domain(DOMAIN)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.resourceId()).isEqualTo(DOMAIN);
                    assertThat(e.resourceTypeAsString()).isEqualTo("domain");
                });
    }

    @Test
    @Order(12)
    @DisplayName("GetAuthorizationToken - returns a bearer token and a future expiration")
    void getAuthorizationTokenReturnsATokenAndExpiration() {
        GetAuthorizationTokenResponse resp = codeArtifact.getAuthorizationToken(r -> r.domain(DOMAIN));

        assertThat(resp.authorizationToken()).isNotBlank();
        assertThat(resp.expiration()).isAfter(Instant.now());
    }

    @Test
    @Order(13)
    @DisplayName("GetAuthorizationToken - fails against a domain that does not exist")
    void getAuthorizationTokenMissingDomainFails() {
        assertThatThrownBy(() -> codeArtifact.getAuthorizationToken(r -> r.domain("does-not-exist-domain")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(14)
    @DisplayName("GetAuthorizationToken - rejects a duration outside 0 or 900-43200")
    void getAuthorizationTokenRejectsAnInvalidDuration() {
        assertThatThrownBy(() -> codeArtifact.getAuthorizationToken(r -> r.domain(DOMAIN).durationSeconds(60L)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @Order(20)
    @DisplayName("CreateRepository - fails against a domain that does not exist")
    void createRepositoryMissingDomainFails() {
        assertThatThrownBy(() -> codeArtifact.createRepository(r -> r
                        .domain("does-not-exist-domain")
                        .repository(REPO)))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> {
                    assertThat(e.resourceId()).isEqualTo("does-not-exist-domain");
                    assertThat(e.resourceTypeAsString()).isEqualTo("domain");
                });
    }

    @Test
    @Order(21)
    @DisplayName("CreateRepository - creates the upstream store repository")
    void createStoreRepository() {
        CreateRepositoryResponse resp = codeArtifact.createRepository(r -> r
                .domain(DOMAIN)
                .repository(STORE_REPO));

        assertThat(resp.repository().name()).isEqualTo(STORE_REPO);
        assertThat(resp.repository().upstreams()).isEmpty();
    }

    @Test
    @Order(22)
    @DisplayName("CreateRepository - creates a repository with an upstream and tags")
    void createRepository() {
        CreateRepositoryResponse resp = codeArtifact.createRepository(r -> r
                .domain(DOMAIN)
                .repository(REPO)
                .description("compat test repo")
                .upstreams(UpstreamRepository.builder().repositoryName(STORE_REPO).build())
                .tags(Tag.builder().key("team").value("data").build()));

        repositoryArn = resp.repository().arn();
        assertThat(resp.repository().domainName()).isEqualTo(DOMAIN);
        assertThat(resp.repository().upstreams()).hasSize(1);
        assertThat(resp.repository().upstreams().get(0).repositoryName()).isEqualTo(STORE_REPO);
    }

    @Test
    @Order(23)
    @DisplayName("DescribeDomain - reports the live repository count")
    void describeDomainReportsRepositoryCount() {
        DescribeDomainResponse resp = codeArtifact.describeDomain(r -> r.domain(DOMAIN));
        assertThat(resp.domain().repositoryCount()).isEqualTo(2);
    }

    @Test
    @Order(30)
    @DisplayName("TagResource / ListTagsForResource / UntagResource round-trip on the repository")
    void tagRoundTrip() {
        codeArtifact.tagResource(r -> r
                .resourceArn(repositoryArn)
                .tags(Tag.builder().key("env").value("compat").build()));

        List<Tag> tags = codeArtifact.listTagsForResource(r -> r.resourceArn(repositoryArn)).tags();
        assertThat(tags).extracting(Tag::key).contains("team", "env");

        codeArtifact.untagResource(r -> r.resourceArn(repositoryArn).tagKeys("env"));
        tags = codeArtifact.listTagsForResource(r -> r.resourceArn(repositoryArn)).tags();
        assertThat(tags).extracting(Tag::key).doesNotContain("env");
    }

    @Test
    @Order(31)
    @DisplayName("TagResource / ListTagsForResource round-trip on the domain")
    void tagRoundTripOnDomain() {
        codeArtifact.tagResource(r -> r
                .resourceArn(domainArn)
                .tags(Tag.builder().key("cost-center").value("platform-eng").build()));

        List<Tag> tags = codeArtifact.listTagsForResource(r -> r.resourceArn(domainArn)).tags();
        assertThat(tags).extracting(Tag::key).contains("owner", "cost-center");
    }

    @Test
    @Order(40)
    @DisplayName("GetRepositoryEndpoint - resolves for a real package format")
    void getRepositoryEndpoint() {
        GetRepositoryEndpointResponse resp = codeArtifact.getRepositoryEndpoint(r -> r
                .domain(DOMAIN)
                .repository(REPO)
                .format(PackageFormat.NPM));

        assertThat(resp.repositoryEndpoint()).contains("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/");
    }

    @Test
    @Order(50)
    @DisplayName("Repository permissions policy - put/get/delete with optimistic locking")
    void repositoryPermissionsPolicyRoundTrip() {
        String policyDocument = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        PutRepositoryPermissionsPolicyResponse put = codeArtifact.putRepositoryPermissionsPolicy(r -> r
                .domain(DOMAIN)
                .repository(REPO)
                .policyDocument(policyDocument));

        assertThat(put.policy().document()).isEqualTo(policyDocument);

        assertThatThrownBy(() -> codeArtifact.putRepositoryPermissionsPolicy(r -> r
                        .domain(DOMAIN)
                        .repository(REPO)
                        .policyDocument(policyDocument)
                        .policyRevision("not-the-current-revision")))
                .isInstanceOf(ConflictException.class);

        GetRepositoryPermissionsPolicyResponse got = codeArtifact.getRepositoryPermissionsPolicy(r -> r
                .domain(DOMAIN)
                .repository(REPO));
        assertThat(got.policy().revision()).isEqualTo(put.policy().revision());

        codeArtifact.deleteRepositoryPermissionsPolicy(r -> r.domain(DOMAIN).repository(REPO));
        assertThatThrownBy(() -> codeArtifact.getRepositoryPermissionsPolicy(r -> r
                        .domain(DOMAIN)
                        .repository(REPO)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(60)
    @DisplayName("AssociateExternalConnection - accepts a real AWS-hosted public upstream")
    void externalConnectionRoundTrip() {
        AssociateExternalConnectionResponse assoc = codeArtifact.associateExternalConnection(r -> r
                .domain(DOMAIN)
                .repository(STORE_REPO)
                .externalConnection("public:npmjs"));

        assertThat(assoc.repository().externalConnections()).hasSize(1);
        assertThat(assoc.repository().externalConnections().get(0).packageFormat()).isEqualTo(PackageFormat.NPM);

        assertThatThrownBy(() -> codeArtifact.associateExternalConnection(r -> r
                        .domain(DOMAIN)
                        .repository(STORE_REPO)
                        .externalConnection("public:pypi")))
                .isInstanceOf(ConflictException.class);

        DisassociateExternalConnectionResponse disassoc = codeArtifact.disassociateExternalConnection(r -> r
                .domain(DOMAIN)
                .repository(STORE_REPO)
                .externalConnection("public:npmjs"));
        assertThat(disassoc.repository().externalConnections()).isEmpty();
    }

    @Test
    @Order(70)
    @DisplayName("Authorization tokens use the duration query parameter and numeric expiration")
    void authorizationTokenDuration() {
        Instant before = Instant.now();
        GetAuthorizationTokenResponse token = codeArtifact.getAuthorizationToken(r -> r
                .domain(DOMAIN).durationSeconds(900L));
        assertThat(token.authorizationToken()).hasSizeGreaterThan(100);
        assertThat(token.expiration()).isBetween(before.plusSeconds(899), Instant.now().plusSeconds(901));
        assertThat(codeArtifact.getAuthorizationToken(r -> r.domain(DOMAIN)).expiration())
                .isBetween(before.plusSeconds(43199), Instant.now().plusSeconds(43201));
        assertThatThrownBy(() -> codeArtifact.getAuthorizationToken(r -> r.domain(DOMAIN).durationSeconds(899L)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> codeArtifact.getAuthorizationToken(r -> r.domain("missing-token-domain")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(71)
    @DisplayName("Generic package bytes survive publish, inspect, copy, dispose and delete")
    void genericPackageLifecycle() throws Exception {
        String namespace = "compat";
        String packageName = "binary-package";
        byte[] content = new byte[] {0, 1, 2, (byte) 255, 10, 13};
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        PublishPackageVersionRequest publish = PublishPackageVersionRequest.builder()
                .domain(DOMAIN).repository(REPO).format(PackageFormat.GENERIC).namespace(namespace)
                .packageValue(packageName).packageVersion("1.0.0").assetName("artifact.bin")
                .assetSHA256(hash).build();
        PublishPackageVersionResponse published = codeArtifact.publishPackageVersion(publish, RequestBody.fromBytes(content));
        assertThat(published.status()).isEqualTo(PackageVersionStatus.PUBLISHED);
        assertThat(published.asset().size()).isEqualTo(content.length);
        assertThat(published.asset().hashesAsStrings()).containsEntry("SHA-256", hash);
        assertThatThrownBy(() -> codeArtifact.publishPackageVersion(publish, RequestBody.fromBytes(content)))
                .isInstanceOf(ConflictException.class);

        assertThat(codeArtifact.describePackage(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName)).packageValue().name())
                .isEqualTo(packageName);
        assertThat(codeArtifact.describePackageVersion(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName).packageVersion("1.0.0"))
                .packageVersion().revision()).isEqualTo(published.versionRevision());
        assertThat(codeArtifact.listPackages(r -> r.domain(DOMAIN).repository(REPO)).packages())
                .extracting(PackageSummary::packageValue).contains(packageName);
        assertThat(codeArtifact.listPackageVersions(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName)).versions())
                .extracting(PackageVersionSummary::version).contains("1.0.0");
        assertThat(codeArtifact.listPackageVersionAssets(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName).packageVersion("1.0.0"))
                .assets()).extracting(AssetSummary::name).contains("artifact.bin");
        ResponseBytes<GetPackageVersionAssetResponse> downloaded = codeArtifact.getPackageVersionAssetAsBytes(r -> r
                .domain(DOMAIN).repository(REPO).format(PackageFormat.GENERIC).namespace(namespace)
                .packageValue(packageName).packageVersion("1.0.0").asset("artifact.bin"));
        assertThat(downloaded.asByteArray()).isEqualTo(content);
        assertThat(downloaded.response().assetName()).isEqualTo("artifact.bin");
        assertThat(downloaded.response().packageVersionRevision()).isEqualTo(published.versionRevision());
        assertThatThrownBy(() -> codeArtifact.getPackageVersionReadme(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName).packageVersion("1.0.0")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(codeArtifact.listPackageVersionDependencies(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName).packageVersion("1.0.0"))
                .dependencies()).isEmpty();

        PublishPackageVersionResponse unfinished = codeArtifact.publishPackageVersion(publish.toBuilder()
                .packageVersion("2.0.0").unfinished(true).build(), RequestBody.fromBytes(content));
        assertThat(unfinished.status()).isEqualTo(PackageVersionStatus.UNFINISHED);
        assertThat(codeArtifact.updatePackageVersionsStatus(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName)
                .versions("2.0.0").targetStatus(PackageVersionStatus.PUBLISHED)).successfulVersions())
                .containsKey("2.0.0");
        assertThat(codeArtifact.putPackageOriginConfiguration(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName)
                .restrictions(PackageOriginRestrictions.builder().publish(AllowPublish.ALLOW)
                        .upstream(AllowUpstream.BLOCK).build())).originConfiguration().restrictions().upstream())
                .isEqualTo(AllowUpstream.BLOCK);

        assertThat(codeArtifact.copyPackageVersions(r -> r.domain(DOMAIN).sourceRepository(REPO)
                .destinationRepository(STORE_REPO).format(PackageFormat.GENERIC).namespace(namespace)
                .packageValue(packageName).versions("1.0.0")).successfulVersions()).containsKey("1.0.0");
        assertThat(codeArtifact.getPackageVersionAssetAsBytes(r -> r.domain(DOMAIN).repository(STORE_REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName)
                .packageVersion("1.0.0").asset("artifact.bin")).asByteArray()).isEqualTo(content);
        assertThat(codeArtifact.disposePackageVersions(r -> r.domain(DOMAIN).repository(STORE_REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName).versions("1.0.0"))
                .successfulVersions().get("1.0.0").status()).isEqualTo(PackageVersionStatus.DISPOSED);
        assertThatThrownBy(() -> codeArtifact.getPackageVersionAssetAsBytes(r -> r.domain(DOMAIN)
                .repository(STORE_REPO).format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName)
                .packageVersion("1.0.0").asset("artifact.bin"))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(codeArtifact.getPackageVersionAssetAsBytes(r -> r.domain(DOMAIN).repository(REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName)
                .packageVersion("1.0.0").asset("artifact.bin")).asByteArray()).isEqualTo(content);
        assertThat(codeArtifact.deletePackageVersions(r -> r.domain(DOMAIN).repository(STORE_REPO)
                .format(PackageFormat.GENERIC).namespace(namespace).packageValue(packageName).versions("1.0.0"))
                .successfulVersions()).containsKey("1.0.0");
        assertThat(codeArtifact.deletePackage(r -> r.domain(DOMAIN).repository(STORE_REPO).format(PackageFormat.GENERIC)
                .namespace(namespace).packageValue(packageName)).deletedPackage().packageValue()).isEqualTo(packageName);
        assertThat(codeArtifact.listPackages(r -> r.domain(DOMAIN).repository(STORE_REPO)).packages())
                .extracting(PackageSummary::packageValue).doesNotContain(packageName);
        codeArtifact.deletePackage(r -> r.domain(DOMAIN).repository(REPO).format(PackageFormat.GENERIC)
                .namespace(namespace).packageValue(packageName));
    }

    @Test
    @Order(70)
    @DisplayName("PublishPackageVersion - publishes a generic asset and verifies its hashes")
    void publishPackageVersion() {
        byte[] content = "hello from the compat suite".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        PublishPackageVersionResponse resp = codeArtifact.publishPackageVersion(r -> r
                        .domain(DOMAIN)
                        .repository(REPO)
                        .format(PackageFormat.GENERIC)
                        .namespace("compat-ns")
                        .packageValue("compat-pkg")
                        .packageVersion("1.0.0")
                        .assetName("asset.txt")
                        .assetSHA256(sha256),
                RequestBody.fromBytes(content));

        assertThat(resp.status()).isEqualTo(PackageVersionStatus.PUBLISHED);
        assertThat(resp.asset().name()).isEqualTo("asset.txt");
        assertThat(resp.asset().size()).isEqualTo(content.length);
        assertThat(resp.asset().hashes().get(HashAlgorithm.SHA_256)).isEqualTo(sha256);
    }

    // No SDK-level bad-assetSHA256 test: the Java SDK overwrites that header during SigV4 signing; see CodeArtifactServiceTest instead.

    @Test
    @Order(72)
    @DisplayName("DescribePackageVersion / GetPackageVersionAsset - round-trip exact bytes")
    void describeAndDownloadPackageVersionAsset() {
        DescribePackageVersionResponse described = codeArtifact.describePackageVersion(r -> r
                .domain(DOMAIN)
                .repository(REPO)
                .format(PackageFormat.GENERIC)
                .namespace("compat-ns")
                .packageValue("compat-pkg")
                .packageVersion("1.0.0"));
        assertThat(described.packageVersion().status()).isEqualTo(PackageVersionStatus.PUBLISHED);
        assertThat(described.packageVersion().origin().originType()).isEqualTo(PackageVersionOriginType.INTERNAL);

        ResponseBytes<GetPackageVersionAssetResponse> downloaded = codeArtifact.getPackageVersionAssetAsBytes(r -> r
                .domain(DOMAIN)
                .repository(REPO)
                .format(PackageFormat.GENERIC)
                .namespace("compat-ns")
                .packageValue("compat-pkg")
                .packageVersion("1.0.0")
                .asset("asset.txt"));
        assertThat(downloaded.response().assetName()).isEqualTo("asset.txt");
        assertThat(downloaded.asByteArray()).isEqualTo("hello from the compat suite".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Order(73)
    @DisplayName("PublishPackageVersion - a second publish to a Published version conflicts")
    void republishingAPublishedVersionConflicts() {
        byte[] content = "won't land".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> codeArtifact.publishPackageVersion(r -> r
                        .domain(DOMAIN)
                        .repository(REPO)
                        .format(PackageFormat.GENERIC)
                        .namespace("compat-ns")
                        .packageValue("compat-pkg")
                        .packageVersion("1.0.0")
                        .assetName("another.txt")
                        .assetSHA256(sha256Hex(content)),
                RequestBody.fromBytes(content)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @Order(90)
    @DisplayName("DeleteDomain - fails while repositories still exist")
    void deleteDomainWithRepositoriesFails() {
        assertThatThrownBy(() -> codeArtifact.deleteDomain(r -> r.domain(DOMAIN)))
                .isInstanceOf(ConflictException.class);
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
