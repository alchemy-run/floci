package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaImageDigestResolverTest {

    private static final String REPO_URI = "000000000000.dkr.ecr.us-east-1.localhost:4566/lambda-image";
    private static final String DIGEST_ONE = "sha256:" + "a".repeat(64);
    private static final String DIGEST_TWO = "sha256:" + "b".repeat(64);

    @Test
    void parsesHostnameStyleTagReference() {
        LambdaImageDigestResolver.EcrImageReference ref = LambdaImageDigestResolver.parse(REPO_URI + ":v1");

        assertEquals("000000000000", ref.registryId());
        assertEquals("us-east-1", ref.region());
        assertEquals("lambda-image", ref.repositoryName());
        assertEquals(REPO_URI, ref.repositoryUri());
        assertEquals("v1", ref.tag());
        assertNull(ref.digest());
    }

    @Test
    void parsesAwsShapedNestedRepositoryWithDigest() {
        LambdaImageDigestResolver.EcrImageReference ref = LambdaImageDigestResolver.parse(
                "123456789012.dkr.ecr.eu-west-1.amazonaws.com/team/app@" + DIGEST_ONE);

        assertEquals("123456789012", ref.registryId());
        assertEquals("eu-west-1", ref.region());
        assertEquals("team/app", ref.repositoryName());
        assertEquals(DIGEST_ONE, ref.digest());
    }

    @Test
    void parsesPathStyleRepository() {
        LambdaImageDigestResolver.EcrImageReference ref = LambdaImageDigestResolver.parse(
                "localhost:4566/000000000000/us-east-1/lambda-image:v2");

        assertEquals("000000000000", ref.registryId());
        assertEquals("us-east-1", ref.region());
        assertEquals("lambda-image", ref.repositoryName());
        assertEquals("localhost:4566/000000000000/us-east-1/lambda-image", ref.repositoryUri());
        assertEquals("v2", ref.tag());
    }

    @Test
    void nonEcrImagesAreNotParsed() {
        assertNull(LambdaImageDigestResolver.parse("public.ecr.aws/lambda/nodejs:22"));
        assertNull(LambdaImageDigestResolver.parse("nginx"));
    }

    @Test
    void resolvesTagToRepositoryDigestUri() {
        EcrService ecr = mock(EcrService.class);
        when(ecr.describeImages(eq("lambda-image"), anyList(), eq("000000000000"), eq("us-east-1")))
                .thenReturn(result(DIGEST_ONE));

        String resolved = new LambdaImageDigestResolver(ecr).resolve(REPO_URI + ":v1");

        assertEquals(REPO_URI + "@" + DIGEST_ONE, resolved);
    }

    @Test
    void digestReferenceResolvesWithoutRegistryLookup() {
        EcrService ecr = mock(EcrService.class);

        String resolved = new LambdaImageDigestResolver(ecr).resolve(REPO_URI + "@" + DIGEST_TWO);

        assertEquals(REPO_URI + "@" + DIGEST_TWO, resolved);
        verify(ecr, never()).describeImages(any(), any(), any(), any());
    }

    @Test
    void missingImageLeavesReferenceUnresolved() {
        EcrService ecr = mock(EcrService.class);
        when(ecr.describeImages(any(), anyList(), any(), any()))
                .thenThrow(new AwsException("ImageNotFoundException", "not found", 400));

        assertNull(new LambdaImageDigestResolver(ecr).resolve(REPO_URI + ":missing"));
    }

    @Test
    void createAndUpdateCodePinTheDigestCurrentlyBehindTheTag() {
        EcrService ecr = mock(EcrService.class);
        when(ecr.describeImages(eq("lambda-image"), anyList(), eq("000000000000"), eq("us-east-1")))
                .thenReturn(result(DIGEST_ONE), result(DIGEST_TWO));
        LambdaService service = new LambdaService(
                new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>()), new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")), new ZipExtractor(),
                new RegionResolver("us-east-1", "000000000000"));
        service.setImageDigestResolver(new LambdaImageDigestResolver(ecr));

        Map<String, Object> create = new HashMap<>();
        create.put("FunctionName", "digest-pinned");
        create.put("Role", "arn:aws:iam::000000000000:role/lambda-role");
        create.put("PackageType", "Image");
        create.put("Architectures", List.of("arm64"));
        create.put("Code", Map.of("ImageUri", REPO_URI + ":lambda"));
        LambdaFunction created = service.createFunction("us-east-1", create);

        assertEquals(REPO_URI + ":lambda", created.getImageUri());
        assertEquals(REPO_URI + "@" + DIGEST_ONE, created.getResolvedImageUri());
        assertEquals("a".repeat(64), created.getCodeSha256());
        assertEquals(List.of("arm64"), created.getArchitectures());

        LambdaFunction updated = service.updateFunctionCode("us-east-1", "digest-pinned",
                Map.of("ImageUri", REPO_URI + ":lambda"));

        assertEquals(REPO_URI + "@" + DIGEST_TWO, updated.getResolvedImageUri());
        assertEquals("b".repeat(64), updated.getCodeSha256());
    }

    @Test
    void emptyImageConfigOnUpdateClearsEveryOverride() {
        LambdaService service = new LambdaService(
                new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>()), new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")), new ZipExtractor(),
                new RegionResolver("us-east-1", "000000000000"));
        Map<String, Object> create = new HashMap<>();
        create.put("FunctionName", "image-overrides");
        create.put("Role", "arn:aws:iam::000000000000:role/lambda-role");
        create.put("PackageType", "Image");
        create.put("Code", Map.of("ImageUri", REPO_URI + ":v1"));
        create.put("ImageConfig", Map.of(
                "Command", List.of("index.alternate"),
                "EntryPoint", List.of("/lambda-entrypoint.sh"),
                "WorkingDirectory", "/var/task"));
        service.createFunction("us-east-1", create);

        Map<String, Object> update = new HashMap<>();
        update.put("ImageConfig", Map.of());
        LambdaFunction fn = service.updateFunctionConfiguration("us-east-1", "image-overrides", update);

        assertNull(fn.getImageConfigCommand());
        assertNull(fn.getImageConfigEntryPoint());
        assertNull(fn.getImageConfigWorkingDirectory());
    }

    private static EcrService.DescribeImagesResult result(String digest) {
        ImageDetail detail = new ImageDetail();
        detail.setImageDigest(digest);
        return new EcrService.DescribeImagesResult(List.of(detail), List.of());
    }
}
