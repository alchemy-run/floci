package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.MicrovmImage;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService.MicrovmImageVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Lambda::MicrovmImage} in isolation. Name is a createOnlyProperty in the registry
 * schema and the physical id, so an unnamed image must keep its generated name across updates and
 * the update must go through UpdateMicrovmImage, the schema's update handler, rather than mint a
 * fresh version through create.
 */
@Timeout(5)
class LambdaMicrovmsCfnProvisionerTest {

    private static final String BASE_IMAGE = "arn:aws:lambda:us-east-1::base-image:nodejs";
    private static final String BUILD_ROLE = "arn:aws:iam::000000000000:role/build";
    private static final String CODE_URI = "s3://bucket/code.zip";

    private final LambdaMicrovmsService service = mock(LambdaMicrovmsService.class);
    private final LambdaMicrovmsCfnProvisioner provisioner = new LambdaMicrovmsCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("MyImage");
        r.setResourceType("AWS::Lambda::MicrovmImage");
        return r;
    }

    private ObjectNode props(String name) {
        ObjectNode props = mapper.createObjectNode()
                .put("BaseImageArn", BASE_IMAGE)
                .put("BuildRoleArn", BUILD_ROLE)
                .put("Description", "an image");
        if (name != null) {
            props.put("Name", name);
        }
        props.set("CodeArtifact", mapper.createObjectNode().put("Uri", CODE_URI));
        return props;
    }

    private static MicrovmImage image(String name, String version) {
        MicrovmImage image = new MicrovmImage();
        image.name = name;
        image.imageArn = "arn:aws:lambda:us-east-1:000000000000:microvm-image:" + name;
        image.state = "CREATED";
        image.latestActiveImageVersion = version;
        if (version != null) {
            MicrovmImageVersion active = new MicrovmImageVersion();
            active.imageVersion = version;
            active.state = "SUCCESSFUL";
            active.status = "ACTIVE";
            image.versions.add(active);
        }
        return image;
    }

    private static MicrovmImage pendingImage(String name, String activeVersion, String newVersion) {
        MicrovmImage image = image(name, activeVersion);
        image.state = activeVersion == null ? "CREATING" : "UPDATING";
        MicrovmImageVersion pending = new MicrovmImageVersion();
        pending.imageVersion = newVersion;
        pending.state = "PENDING";
        pending.status = "INACTIVE";
        image.versions.add(pending);
        return image;
    }

    @Test
    void createSetsThePhysicalIdAndTheGetAttAttributes() {
        when(service.createImage(eq("us-east-1"), eq("000000000000"), eq("img"), eq(BASE_IMAGE),
                eq(BUILD_ROLE), eq(CODE_URI), eq("an image"))).thenReturn(image("img", "1.0"));
        StackResource r = resource();

        provisioner.provision(r, props("img"), ctx(null));

        assertEquals("img", r.getPhysicalId());
        assertEquals("arn:aws:lambda:us-east-1:000000000000:microvm-image:img", r.getAttributes().get("ImageArn"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:microvm-image:img", r.getAttributes().get("Arn"));
        assertEquals("img", r.getAttributes().get("Name"));
        assertEquals("1.0", r.getAttributes().get("LatestActiveImageVersion"));
    }

    @Test
    void createWaitsForPendingBuildToBecomeActiveBeforePublishingAttributes() {
        MicrovmImage pending = pendingImage("img", null, "1.0");
        MicrovmImage building = pendingImage("img", null, "1.0");
        building.versions.getLast().state = "IN_PROGRESS";
        when(service.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(pending);
        StackResource r = resource();
        when(service.getImage("us-east-1", "img")).thenAnswer(inv -> {
            assertFalse(r.getAttributes().containsKey("LatestActiveImageVersion"));
            return building;
        }).thenReturn(image("img", "1.0"));

        provisioner.provision(r, props("img"), ctx(null));

        verify(service, times(2)).getImage("us-east-1", "img");
        assertEquals("img", r.getPhysicalId());
        assertEquals("1.0", r.getAttributes().get("LatestActiveImageVersion"));
    }

    @Test
    void updateWaitsForTheNewVersionDespiteAnOlderActiveVersion() {
        MicrovmImage pending = pendingImage("img", "1.0", "2.0");
        MicrovmImage updated = image("img", "2.0");
        updated.state = "UPDATED";
        when(service.updateImage(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(pending);
        when(service.getImage("us-east-1", "img")).thenReturn(pending, updated);
        StackResource r = resource();
        r.getAttributes().put("LatestActiveImageVersion", "1.0");

        provisioner.provision(r, props("img"), ctx("img"));

        verify(service, times(2)).getImage("us-east-1", "img");
        verify(service, never()).createImage(anyString(), anyString(), anyString(), any(), any(), any(), any());
        assertEquals("2.0", r.getAttributes().get("LatestActiveImageVersion"));
    }

    @Test
    void createBuildFailureFailsProvisioningWithTheDockerReason() {
        MicrovmImage pending = pendingImage("img", null, "1.0");
        MicrovmImage failed = pendingImage("img", null, "1.0");
        failed.state = "CREATE_FAILED";
        failed.versions.getLast().state = "FAILED";
        failed.versions.getLast().stateReason = "Code artifact has no Dockerfile at its root";
        when(service.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(pending);
        when(service.getImage("us-east-1", "img")).thenReturn(failed);
        StackResource r = resource();

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("img"), ctx(null)));

        assertEquals("ValidationError", error.getErrorCode());
        assertTrue(error.getMessage().contains("img/1.0"), error.getMessage());
        assertTrue(error.getMessage().contains("Code artifact has no Dockerfile"), error.getMessage());
        assertEquals("img", r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey("LatestActiveImageVersion"));
    }

    @Test
    void updateBuildFailureDoesNotReportSuccessFromThePreviousActiveVersion() {
        MicrovmImage pending = pendingImage("img", "1.0", "2.0");
        MicrovmImage failed = pendingImage("img", "1.0", "2.0");
        failed.state = "UPDATE_FAILED";
        failed.versions.getLast().state = "FAILED";
        failed.versions.getLast().stateReason = "Docker build failed";
        when(service.updateImage(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(pending);
        when(service.getImage("us-east-1", "img")).thenReturn(failed);
        StackResource r = resource();
        r.getAttributes().put("LatestActiveImageVersion", "1.0");

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("img"), ctx("img")));

        assertEquals("ValidationError", error.getErrorCode());
        assertTrue(error.getMessage().contains("img/2.0"), error.getMessage());
        assertTrue(error.getMessage().contains("Docker build failed"), error.getMessage());
        assertEquals("1.0", r.getAttributes().get("LatestActiveImageVersion"));
    }

    @Test
    void interruptedBuildWaitPreservesInterruptAndDoesNotPublishAnActiveVersion() {
        when(service.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(pendingImage("img", null, "1.0"));
        StackResource r = resource();
        try {
            Thread.currentThread().interrupt();
            AwsException error = assertThrows(AwsException.class,
                    () -> provisioner.provision(r, props("img"), ctx(null)));

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("ValidationError", error.getErrorCode());
            assertTrue(error.getMessage().contains("Interrupted"), error.getMessage());
            assertFalse(r.getAttributes().containsKey("LatestActiveImageVersion"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anUnnamedImageKeepsItsNameAndIsUpdatedRatherThanRecreated() {
        when(service.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> image(inv.getArgument(2), "1.0"));
        when(service.updateImage(anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> image(inv.getArgument(1), "2.0"));
        StackResource created = resource();
        provisioner.provision(created, props(null), ctx(null));
        String generatedName = created.getPhysicalId();
        assertTrue(generatedName.startsWith("my-stack-MyImage-"), generatedName);

        StackResource updated = resource();
        provisioner.provision(updated, props(null), ctx(generatedName));

        assertEquals(generatedName, updated.getPhysicalId());
        assertEquals("2.0", updated.getAttributes().get("LatestActiveImageVersion"));
        verify(service, times(1)).createImage(anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(service).updateImage("us-east-1", generatedName, BASE_IMAGE, BUILD_ROLE, CODE_URI, "an image");
    }

    @Test
    void aRenamedImageIsAReplacingUpdateAndStillCreates() {
        when(service.createImage(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> image(inv.getArgument(2), "1.0"));
        StackResource r = resource();

        provisioner.provision(r, props("renamed"), ctx("old-name"));

        assertEquals("renamed", r.getPhysicalId());
        verify(service).createImage(eq("us-east-1"), eq("000000000000"), eq("renamed"), any(), any(), any(), any());
        verify(service, never()).updateImage(anyString(), anyString(), any(), any(), any(), any());
    }
}
