package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.CreatePodIdentityAssociationRequest;
import software.amazon.awssdk.services.eks.model.DescribeClusterResponse;
import software.amazon.awssdk.services.eks.model.InvalidParameterException;
import software.amazon.awssdk.services.eks.model.ListAccessPoliciesResponse;
import software.amazon.awssdk.services.eks.model.PodIdentityAssociation;
import software.amazon.awssdk.services.eks.model.ResourceInUseException;
import software.amazon.awssdk.services.eks.model.ResourceNotFoundException;
import software.amazon.awssdk.services.eks.model.VpcConfigRequest;
import software.amazon.awssdk.services.eks.waiters.EksWaiter;
import software.amazon.awssdk.services.iam.IamClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EKS pod identity management and honest catalog boundaries")
class EksPodIdentityTest {
    private static final String ACCOUNT = "135791357913";

    @Test
    @DisplayName("Signed SDK requests persist association changes and reject foreign account and region access")
    void signedAssociationLifecycleAndIsolation() {
        String cluster = TestFixtures.uniqueName("sdk-pod-identity");
        String role = cluster + "-role";
        StaticCredentialsProvider credentials = credentials(ACCOUNT);
        try (EksClient eks = client(ACCOUNT, Region.US_EAST_1);
             EksClient foreignAccount = client("246802468024", Region.US_EAST_1);
             EksClient foreignRegion = client(ACCOUNT, Region.EU_WEST_1);
             IamClient iam = IamClient.builder().endpointOverride(TestFixtures.endpoint())
                     .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            String roleArn = iam.createRole(request -> request.roleName(role).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Principal":{"Service":"pods.eks.amazonaws.com"},
                    "Action":["sts:AssumeRole","sts:TagSession"]}]}
                    """)).role().arn();
            try {
                eks.createCluster(request -> request.name(cluster).roleArn(roleArn)
                        .resourcesVpcConfig(VpcConfigRequest.builder().build()));
                try {
                    try (EksWaiter waiter = eks.waiter()) {
                        WaiterResponse<DescribeClusterResponse> ready = waiter.waitUntilClusterActive(
                                request -> request.name(cluster),
                                options -> options.maxAttempts(12).waitTimeout(Duration.ofSeconds(60)));
                        assertThat(ready.matched().response()).isPresent();
                    }
                    CreatePodIdentityAssociationRequest create = CreatePodIdentityAssociationRequest.builder()
                            .clusterName(cluster).namespace("default").serviceAccount("api").roleArn(roleArn)
                            .clientRequestToken("create").tags(Map.of("environment", "test")).build();
                    PodIdentityAssociation association = eks.createPodIdentityAssociation(create).association();
                    String id = association.associationId();
                    String arn = association.associationArn();
                    assertThat(id).startsWith("a-");
                    assertThat(association.createdAt()).isNotNull();
                    assertThat(association.disableSessionTags()).isFalse();
                    assertThat(eks.createPodIdentityAssociation(create).association().associationId()).isEqualTo(id);
                    assertThatThrownBy(() -> eks.createPodIdentityAssociation(create.toBuilder()
                            .clientRequestToken("duplicate").build())).isInstanceOf(ResourceInUseException.class);
                    assertThatThrownBy(() -> eks.createPodIdentityAssociation(create.toBuilder()
                            .serviceAccount("changed").build())).isInstanceOf(InvalidParameterException.class);
                    assertThat(eks.updatePodIdentityAssociation(request -> request.clusterName(cluster)
                            .associationId(id).disableSessionTags(true).clientRequestToken("update"))
                            .association().disableSessionTags()).isTrue();
                    eks.tagResource(request -> request.resourceArn(arn).tags(Map.of("owner", "platform")));
                    eks.untagResource(request -> request.resourceArn(arn).tagKeys("environment"));
                    assertThat(eks.listTagsForResource(request -> request.resourceArn(arn)).tags())
                            .containsExactlyEntriesOf(Map.of("owner", "platform"));
                    assertThat(eks.listPodIdentityAssociations(request -> request.clusterName(cluster)
                            .namespace("default").serviceAccount("api")).associations())
                            .singleElement().satisfies(summary -> assertThat(summary.associationId()).isEqualTo(id));
                    for (EksClient foreign : new EksClient[] { foreignAccount, foreignRegion }) {
                        assertThat(foreign.listClusters().clusters()).doesNotContain(cluster);
                        assertThatThrownBy(() -> foreign.describePodIdentityAssociation(request -> request
                                .clusterName(cluster).associationId(id))).isInstanceOf(ResourceNotFoundException.class);
                        assertThatThrownBy(() -> foreign.listPodIdentityAssociations(request -> request
                                .clusterName(cluster))).isInstanceOf(ResourceNotFoundException.class);
                        assertThatThrownBy(() -> foreign.updatePodIdentityAssociation(request -> request
                                .clusterName(cluster).associationId(id).disableSessionTags(false)))
                                .isInstanceOf(ResourceNotFoundException.class);
                        assertThatThrownBy(() -> foreign.deletePodIdentityAssociation(request -> request
                                .clusterName(cluster).associationId(id))).isInstanceOf(ResourceNotFoundException.class);
                        assertThatThrownBy(() -> foreign.tagResource(request -> request
                                .resourceArn(arn).tags(Map.of("owner", "foreign"))))
                                .isInstanceOf(ResourceNotFoundException.class);
                        assertThatThrownBy(() -> foreign.deleteCluster(request -> request.name(cluster)))
                                .isInstanceOf(ResourceNotFoundException.class);
                    }
                    PodIdentityAssociation observed = eks.describePodIdentityAssociation(request -> request
                            .clusterName(cluster).associationId(id)).association();
                    assertThat(observed.disableSessionTags()).isTrue();
                    assertThat(observed.tags()).containsExactlyEntriesOf(Map.of("owner", "platform"));
                    assertThat(eks.deletePodIdentityAssociation(request -> request.clusterName(cluster)
                            .associationId(id)).association().associationArn()).isEqualTo(arn);
                    assertThatThrownBy(() -> eks.describePodIdentityAssociation(request -> request
                            .clusterName(cluster).associationId(id))).isInstanceOf(ResourceNotFoundException.class);
                    assertThat(eks.listPodIdentityAssociations(request -> request.clusterName(cluster)).associations()).isEmpty();
                } finally {
                    eks.deleteCluster(request -> request.name(cluster));
                }
            } finally {
                iam.deleteRole(request -> request.roleName(role));
            }
        }
    }

    @Test
    @DisplayName("Signed catalog reads expose metadata without claiming that add-ons are installed")
    void signedCatalogBoundariesAreTyped() {
        try (EksClient eks = client(ACCOUNT, Region.US_EAST_1)) {
            assertThat(eks.listAccessPolicies(request -> {}).accessPolicies())
                    .anySatisfy(policy -> {
                        assertThat(policy.name()).isEqualTo("AmazonEKSViewPolicy");
                        assertThat(policy.arn()).isEqualTo("arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy");
                    });
            ListAccessPoliciesResponse first = eks.listAccessPolicies(request -> request.maxResults(2));
            assertThat(first.accessPolicies()).hasSize(2);
            assertThat(first.nextToken()).isNotBlank();
            ListAccessPoliciesResponse last = eks.listAccessPolicies(request -> request.maxResults(2)
                    .nextToken(first.nextToken()));
            assertThat(last.accessPolicies()).hasSize(2).doesNotContainAnyElementsOf(first.accessPolicies());
            assertThat(last.nextToken()).isNull();
            assertThat(eks.describeClusterVersions(request -> request.defaultOnly(true)).clusterVersions())
                    .singleElement().satisfies(version -> {
                        assertThat(version.clusterVersion()).matches("\\d+\\.\\d+");
                        assertThat(version.defaultVersion()).isTrue();
                        assertThat(version.releaseDate()).isNotNull();
                        assertThat(version.endOfStandardSupportDate()).isAfter(version.releaseDate());
                        assertThat(version.endOfExtendedSupportDate()).isAfter(version.endOfStandardSupportDate());
                    });
            assertThat(eks.describeAddonVersions(request -> request.addonName("vpc-cni").maxResults(1)).addons())
                    .singleElement().satisfies(addon -> {
                        assertThat(addon.addonName()).isEqualTo("vpc-cni");
                        assertThat(addon.addonVersions()).isNotEmpty();
                        String version = addon.addonVersions().get(0).addonVersion();
                        assertThat(eks.describeAddonConfiguration(request -> request.addonName("vpc-cni")
                                .addonVersion(version)).configurationSchema())
                                .contains("#/definitions/VpcCni", "ENABLE_PREFIX_DELEGATION", "WARM_ENI_TARGET");
                    });
            assertThatThrownBy(() -> eks.listAccessPolicies(request -> request.maxResults(0)))
                    .isInstanceOf(InvalidParameterException.class);
            assertThatThrownBy(() -> eks.describeClusterVersions(request -> request.maxResults(101)))
                    .isInstanceOf(InvalidParameterException.class);
            assertThatThrownBy(() -> eks.describeAddonVersions(request -> request.nextToken("!")))
                    .isInstanceOf(InvalidParameterException.class);
            assertThatThrownBy(() -> eks.describeAddonConfiguration(request -> request.addonName("vpc-cni")
                    .addonVersion("unavailable"))).isInstanceOf(ResourceNotFoundException.class);
            String cluster = TestFixtures.uniqueName("sdk-no-addons");
            eks.createCluster(request -> request.name(cluster).roleArn("arn:aws:iam::" + ACCOUNT + ":role/cluster")
                    .resourcesVpcConfig(VpcConfigRequest.builder().build()));
            try {
                // Add-on APIs require an ACTIVE cluster, and CreateCluster returns while it is CREATING.
                try (EksWaiter waiter = eks.waiter()) {
                    assertThat(waiter.waitUntilClusterActive(request -> request.name(cluster),
                            options -> options.maxAttempts(12).waitTimeout(Duration.ofSeconds(60)))
                            .matched().response()).isPresent();
                }
                assertThat(eks.listAddons(request -> request.clusterName(cluster)).addons()).isEmpty();
                assertThatThrownBy(() -> eks.createAddon(request -> request.clusterName(cluster).addonName("floci-unknown-addon")))
                        .isInstanceOf(InvalidParameterException.class);
                assertThatThrownBy(() -> eks.describeAddon(request -> request.clusterName(cluster).addonName("vpc-cni")))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> eks.updateAddon(request -> request.clusterName(cluster).addonName("vpc-cni")))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> eks.deleteAddon(request -> request.clusterName(cluster).addonName("vpc-cni")))
                        .isInstanceOf(ResourceNotFoundException.class);
            } finally {
                eks.deleteCluster(request -> request.name(cluster));
            }
        }
    }

    private static EksClient client(String account, Region region) {
        return EksClient.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(credentials(account))
                .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(75))).build();
    }

    private static StaticCredentialsProvider credentials(String account) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
    }
}
