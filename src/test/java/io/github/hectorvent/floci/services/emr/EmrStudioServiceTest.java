package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmrStudioServiceTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/studio";
    private static final String TRUST = """
            {"Statement":[{"Effect":"Allow","Principal":{"Service":"elasticmapreduce.amazonaws.com"},
              "Action":"sts:AssumeRole"}]}
            """;
    private static final String S3_ACCESS = """
            {"Statement":[{"Effect":"Allow","Action":"s3:*",
              "Resource":["arn:aws:s3:::studio-backup","arn:aws:s3:::studio-backup/*"]}]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestContext context = new RequestContext();
    private final RegionResolver resolver = mock(RegionResolver.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final IamService iam = mock(IamService.class);
    private final S3Service s3 = mock(S3Service.class);
    private final StorageFactory storageFactory = mock(StorageFactory.class);
    private EmrStudioService service;
    private ObjectNode request;
    private IamRole role;
    private SecurityGroup engine;
    private Subnet subnet;

    @BeforeEach
    void setUp() throws Exception {
        context.setAccountId(ACCOUNT);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        AccountAwareStorageBackend<ObjectNode> store = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), contexts, ACCOUNT);
        when(storageFactory.<ObjectNode>create(eq("emr"), eq("emr-studios.json"), any())).thenReturn(store);
        when(resolver.getAccountId()).thenAnswer(ignored -> context.getAccountId());
        when(resolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                AwsArnUtils.Arn.of(invocation.getArgument(0), invocation.getArgument(1),
                        context.getAccountId(), invocation.getArgument(2)).toString());
        service = new EmrStudioService(storageFactory, resolver, mapper, ec2, iam, new IamPolicyEvaluator(mapper), s3);
        role = new IamRole("role-id", "studio", "/", ROLE_ARN, TRUST);
        role.setInlinePolicies(Map.of("storage", S3_ACCESS));
        when(iam.findRole(ACCOUNT, "studio")).thenReturn(Optional.of(role));
        when(s3.findBucketPolicyInfo("studio-backup"))
                .thenReturn(Optional.of(new S3Service.BucketPolicyInfo(null, ACCOUNT)));
        subnet = new Subnet();
        subnet.setSubnetId("subnet-studio");
        subnet.setVpcId("vpc-studio");
        when(ec2.describeSubnets(eq(REGION), anyList(), anyMap())).thenReturn(List.of(subnet));
        SecurityGroup workspace = new SecurityGroup();
        workspace.setGroupId("sg-workspace");
        workspace.setVpcId("vpc-studio");
        engine = new SecurityGroup();
        engine.setGroupId("sg-engine");
        engine.setVpcId("vpc-studio");
        IpPermission ingress = new IpPermission();
        ingress.setIpProtocol("tcp");
        ingress.setFromPort(18888);
        ingress.setToPort(18888);
        UserIdGroupPair source = new UserIdGroupPair();
        source.setGroupId("sg-workspace");
        ingress.setUserIdGroupPairs(List.of(source));
        engine.setIpPermissions(List.of(ingress));
        when(ec2.describeSecurityGroups(eq(REGION), anyList(), anyList(), anyMap()))
                .thenReturn(List.of(workspace, engine));
        request = (ObjectNode) mapper.readTree("""
                {"Name":"studio","Description":"initial","AuthMode":"IAM","VpcId":"vpc-studio",
                 "SubnetIds":["subnet-studio"],"ServiceRole":"%s",
                 "WorkspaceSecurityGroupId":"sg-workspace","EngineSecurityGroupId":"sg-engine",
                 "DefaultS3Location":"s3://studio-backup/notebooks/","Tags":[{"Key":"env","Value":"dev"}]}
                """.formatted(ROLE_ARN));
    }

    @Test
    void studioLifecyclePersistsUpdatesAndTags() throws Exception {
        ObjectNode created = service.create(request, REGION);
        String id = created.path("StudioId").asText();
        assertTrue(id.startsWith("es-"));
        assertEquals("arn:aws:elasticmapreduce:" + REGION + ":" + ACCOUNT + ":studio/" + id,
                created.path("StudioArn").asText());
        assertEquals(1, service.list(mapper.createObjectNode(), REGION).path("Studios").size());
        assertEquals("IAM", service.describe(id, REGION).path("AuthMode").asText());
        service.update(mapper.createObjectNode().put("StudioId", id).put("Description", "updated")
                .put("DefaultS3Location", "s3://studio-backup/v2/"), REGION);
        service.addTags(id, mapper.readTree("[{\"Key\":\"env\",\"Value\":\"prod\"},{\"Key\":\"team\",\"Value\":\"data\"}]"), REGION);
        service.removeTags(id, List.of("env"), REGION);
        ObjectNode updated = service.describe(id, REGION);
        assertEquals("updated", updated.path("Description").asText());
        assertEquals("s3://studio-backup/v2/", updated.path("DefaultS3Location").asText());
        assertEquals(1, updated.path("Tags").size());
        assertEquals("team", updated.path("Tags").get(0).path("Key").asText());
        updated.put("Description", "must-not-mutate-storage");
        assertEquals("updated", service.describe(id, REGION).path("Description").asText());
        EmrStudioService reopened = new EmrStudioService(storageFactory, resolver, mapper,
                ec2, iam, new IamPolicyEvaluator(mapper), s3);
        assertEquals("updated", reopened.describe(id, REGION).path("Description").asText());
        service.delete(id, REGION);
        assertMissing(() -> service.describe(id, REGION));
        assertMissing(() -> service.delete(id, REGION));
        assertEquals(0, service.list(mapper.createObjectNode(), REGION).path("Studios").size());
    }

    @Test
    void handlerDispatchesStudioLifecycleAndTags() throws Exception {
        EmrHandler handler = new EmrHandler(mock(EmrService.class), mapper,
                new EmrReleaseCatalog(mapper, resolver), service);
        Response created = handler.handle("CreateStudio", request, REGION);
        assertEquals(200, created.getStatus());
        String id = ((JsonNode) created.getEntity()).path("StudioId").asText();
        ObjectNode identity = mapper.createObjectNode().put("StudioId", id);
        ObjectNode tagged = mapper.createObjectNode().put("ResourceId", id);
        tagged.set("Tags", mapper.readTree("[{\"Key\":\"owner\",\"Value\":\"data\"}]"));
        assertEquals(200, handler.handle("AddTags", tagged, REGION).getStatus());
        assertEquals(200, handler.handle("UpdateStudio", identity.deepCopy().put("Description", "wire"), REGION).getStatus());
        Response described = handler.handle("DescribeStudio", identity, REGION);
        assertEquals(200, described.getStatus());
        JsonNode studio = ((JsonNode) described.getEntity()).path("Studio");
        assertEquals(id, studio.path("StudioId").asText());
        assertEquals("wire", studio.path("Description").asText());
        assertEquals(2, studio.path("Tags").size());
        tagged.putArray("TagKeys").add("owner");
        assertEquals(200, handler.handle("RemoveTags", tagged, REGION).getStatus());
        assertEquals(1, service.describe(id, REGION).path("Tags").size());
        assertEquals(200, handler.handle("ListStudios", mapper.createObjectNode(), REGION).getStatus());
        assertEquals(200, handler.handle("DeleteStudio", identity, REGION).getStatus());
        assertEquals(400, handler.handle("DescribeStudio", identity, REGION).getStatus());
    }

    @Test
    void studioInventoryPagesWithoutRepeatingItems() {
        for (int index = 0; index < 51; index++) {
            service.create(request.deepCopy().put("Name", "studio-" + index), REGION);
        }
        ObjectNode first = service.list(mapper.createObjectNode(), REGION);
        assertEquals(50, first.path("Studios").size());
        assertTrue(first.has("Marker"));
        ObjectNode second = service.list(mapper.createObjectNode().put("Marker", first.path("Marker").asText()), REGION);
        assertEquals(1, second.path("Studios").size());
        assertFalse(second.has("Marker"));
        String lastId = second.path("Studios").get(0).path("StudioId").asText();
        first.path("Studios").forEach(studio -> assertNotEquals(lastId, studio.path("StudioId").asText()));
    }

    @Test
    void studiosAreIsolatedByAccountAndRegion() {
        String id = service.create(request, REGION).path("StudioId").asText();
        assertMissing(() -> service.describe(id, "us-west-2"));
        assertMissing(() -> service.delete(id, "us-west-2"));
        assertEquals(0, service.list(mapper.createObjectNode(), "us-west-2").path("Studios").size());
        context.setAccountId("222222222222");
        assertMissing(() -> service.describe(id, REGION));
        assertMissing(() -> service.update(mapper.createObjectNode().put("StudioId", id).put("Name", "foreign"), REGION));
        assertMissing(() -> service.addTags(id, mapper.createArrayNode(), REGION));
        assertMissing(() -> service.delete(id, REGION));
        assertEquals(0, service.list(mapper.createObjectNode(), REGION).path("Studios").size());
        context.setAccountId(ACCOUNT);
        assertEquals("studio", service.describe(id, REGION).path("Name").asText());
    }

    @Test
    void missingNetworkResourcesAndCrossVpcSubnetsAreRejected() {
        when(ec2.describeVpcs(eq(REGION), anyList(), anyMap()))
                .thenThrow(new AwsException("InvalidVpcID.NotFound", "VPC does not exist", 400));
        assertInvalid(() -> service.create(request, REGION));
        reset(ec2);
        when(ec2.describeSubnets(eq(REGION), anyList(), anyMap())).thenReturn(List.of(subnet));
        subnet.setVpcId("vpc-other");
        assertInvalid(() -> service.create(request, REGION));
        assertEquals(0, service.list(mapper.createObjectNode(), REGION).path("Studios").size());
    }

    @Test
    void engineMustAllowTrafficFromWorkspace() {
        engine.setIpPermissions(List.of());
        assertInvalid(() -> service.create(request, REGION));
    }

    @Test
    void serviceRoleMustExistAndTrustEmr() {
        when(iam.findRole(ACCOUNT, "studio")).thenReturn(Optional.empty());
        assertInvalid(() -> service.create(request, REGION));
        when(iam.findRole(ACCOUNT, "studio")).thenReturn(Optional.of(role));
        role.setAssumeRolePolicyDocument(TRUST.replace("elasticmapreduce.amazonaws.com", "lambda.amazonaws.com"));
        assertInvalid(() -> service.create(request, REGION));
        role.setAssumeRolePolicyDocument(TRUST.replace("Allow", "Deny"));
        assertInvalid(() -> service.create(request, REGION));
    }

    @Test
    void missingBucketsAndMissingS3GrantsAreRejected() {
        when(s3.findBucketPolicyInfo("studio-backup")).thenReturn(Optional.empty());
        assertInvalid(() -> service.create(request, REGION));
        when(s3.findBucketPolicyInfo("studio-backup"))
                .thenReturn(Optional.of(new S3Service.BucketPolicyInfo(null, ACCOUNT)));
        role.setInlinePolicies(Map.of());
        assertInvalid(() -> service.create(request, REGION));
        role.setInlinePolicies(Map.of("storage", S3_ACCESS.replace("Allow", "Deny")));
        assertInvalid(() -> service.create(request, REGION));
    }

    @Test
    void crossAccountBucketRequiresItsOwnersGrant() {
        when(s3.findBucketPolicyInfo("studio-backup"))
                .thenReturn(Optional.of(new S3Service.BucketPolicyInfo(null, "222222222222")));
        assertInvalid(() -> service.create(request, REGION));
        String bucketPolicy = """
                {"Statement":[{"Effect":"Allow","Principal":{"AWS":"%s"},
                  "Action":"s3:*","Resource":["arn:aws:s3:::studio-backup","arn:aws:s3:::studio-backup/*"]}]}
                """.formatted(ROLE_ARN);
        when(s3.findBucketPolicyInfo("studio-backup"))
                .thenReturn(Optional.of(new S3Service.BucketPolicyInfo(bucketPolicy, "222222222222")));
        assertNotNull(service.create(request, REGION).get("StudioId"));
    }

    @Test
    void permissionsBoundaryAndBucketDenyOverrideRoleGrants() {
        String boundaryArn = "arn:aws:iam::" + ACCOUNT + ":policy/boundary";
        role.setPermissionsBoundaryArn(boundaryArn);
        when(iam.getPolicy(boundaryArn)).thenReturn(new IamPolicy("id", "boundary", "/", boundaryArn,
                null, S3_ACCESS.replace("s3:*", "s3:GetObject")));
        assertInvalid(() -> service.create(request, REGION));
        role.setPermissionsBoundaryArn(null);
        String deny = """
                {"Statement":[{"Effect":"Deny","Principal":"*","Action":"s3:*","Resource":"*"}]}
                """;
        when(s3.findBucketPolicyInfo("studio-backup"))
                .thenReturn(Optional.of(new S3Service.BucketPolicyInfo(deny, ACCOUNT)));
        assertInvalid(() -> service.create(request, REGION));
    }

    @Test
    void unsupportedStudioFeaturesDoNotReportSuccessOrPersistState() {
        AwsException sso = assertThrows(AwsException.class,
                () -> service.create(request.deepCopy().put("AuthMode", "SSO"), REGION));
        assertEquals("NotImplementedException", sso.getErrorCode());
        assertEquals(501, sso.getHttpStatus());
        for (String field : List.of("IdcInstanceArn", "EncryptionKeyArn", "IdpAuthUrl")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.create(request.deepCopy().put(field, "unsupported"), REGION));
            assertEquals("NotImplementedException", error.getErrorCode());
            assertEquals(501, error.getHttpStatus());
        }
        assertEquals(0, service.list(mapper.createObjectNode(), REGION).path("Studios").size());
        String id = service.create(request, REGION).path("StudioId").asText();
        AwsException error = assertThrows(AwsException.class, () -> service.update(mapper.createObjectNode()
                .put("StudioId", id).put("Description", "rejected").put("EncryptionKeyArn", "unsupported"), REGION));
        assertEquals("NotImplementedException", error.getErrorCode());
        assertEquals("initial", service.describe(id, REGION).path("Description").asText());
    }

    @Test
    void failedUpdateDoesNotMutateStoredStudio() {
        String id = service.create(request, REGION).path("StudioId").asText();
        assertInvalid(() -> service.update(mapper.createObjectNode().put("StudioId", id)
                .put("Description", "rejected").put("DefaultS3Location", "https://example.com"), REGION));
        assertEquals("initial", service.describe(id, REGION).path("Description").asText());
        assertEquals("s3://studio-backup/notebooks/", service.describe(id, REGION).path("DefaultS3Location").asText());
    }

    private static void assertMissing(Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals("InvalidRequestException", error.getErrorCode());
        assertEquals("Studio does not exist.", error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    private static void assertInvalid(Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals("InvalidRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}
