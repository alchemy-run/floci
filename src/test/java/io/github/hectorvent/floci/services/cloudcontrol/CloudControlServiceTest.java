package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationResourceProvisioner;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class CloudControlServiceTest {

    @Test
    void accountScopesCreateStatusLookupAndDelete() throws Exception {
        CloudFormationResourceProvisioner provisioner = mock(CloudFormationResourceProvisioner.class);
        StackResource resource = new StackResource();
        resource.setPhysicalId("vpc-account-a");
        resource.setStatus("CREATE_COMPLETE");
        resource.setAttributes(Map.of("VpcId", "vpc-account-a"));
        Ec2Service ec2 = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-account-a");
        when(ec2.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of(vpc));
        org.mockito.Mockito.doAnswer(invocation -> {
            when(ec2.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of());
            return null;
        }).when(provisioner).deleteStandalone("AWS::EC2::VPC", "vpc-account-a", "us-east-1",
                "111111111111", Map.of("VpcId", "vpc-account-a"));
        when(provisioner.provisionStandalone(org.mockito.ArgumentMatchers.eq("AWS::EC2::VPC"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("us-east-1"),
                org.mockito.ArgumentMatchers.eq("111111111111"))).thenReturn(resource);
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), ec2, mock(IamService.class), provisioner,
                new ObjectMapper());

        CloudControlService.ProgressEvent pending = service.createResource(
                "us-east-1", "111111111111", "AWS::EC2::VPC", "{\"CidrBlock\":\"10.0.0.0/16\"}");
        CloudControlService.ProgressEvent completed = pending;
        for (int i = 0; i < 20 && !"SUCCESS".equals(completed.operationStatus()); i++) {
            Thread.sleep(10);
            completed = service.requestStatus("111111111111", pending.requestToken());
        }
        assertEquals("SUCCESS", completed.operationStatus());
        assertEquals(1, service.listRequests("us-east-1", "111111111111", List.of("CREATE"), List.of("SUCCESS")).size());
        assertTrue(service.listRequests("us-west-2", "111111111111", List.of(), List.of()).isEmpty());
        assertTrue(service.listRequests("us-east-1", "222222222222", List.of(), List.of()).isEmpty());
        assertThrows(AwsException.class, () -> service.requestStatus("us-west-2", "111111111111", pending.requestToken()));
        assertEquals("ConcurrentModificationException", assertThrows(AwsException.class,
                () -> service.cancelRequest("us-east-1", "111111111111", pending.requestToken())).getErrorCode());
        assertEquals("RequestTokenNotFoundException", assertThrows(AwsException.class,
                () -> service.cancelRequest("us-east-1", "111111111111", "missing")).getErrorCode());
        assertEquals("vpc-account-a", service.getResource("us-east-1", "111111111111",
                "AWS::EC2::VPC", "vpc-account-a").identifier());
        assertThrows(AwsException.class, () -> service.requestStatus("222222222222", pending.requestToken()));
        when(ec2.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of());
        assertThrows(AwsException.class, () -> service.getResource("us-east-1", "222222222222",
                "AWS::EC2::VPC", "vpc-account-a"));

        CloudControlService.ProgressEvent deniedDelete = service.deleteResource(
                "us-east-1", "222222222222", "AWS::EC2::VPC", "vpc-account-a");
        assertEquals("FAILED", deniedDelete.operationStatus());
        verify(provisioner, never()).deleteStandalone(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());

        when(ec2.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of(vpc));
        CloudControlService.ProgressEvent deleted = service.deleteResource(
                "us-east-1", "111111111111", "AWS::EC2::VPC", "vpc-account-a");
        assertEquals("SUCCESS", deleted.operationStatus());
        verify(provisioner).deleteStandalone("AWS::EC2::VPC", "vpc-account-a", "us-east-1",
                "111111111111", Map.of("VpcId", "vpc-account-a"));
        service.shutdown();
    }

    @Test
    void restoresAccountOwnersAndRequestStateFromMetadataStores() throws Exception {
        CloudFormationResourceProvisioner provisioner = mock(CloudFormationResourceProvisioner.class);
        StackResource resource = new StackResource();
        resource.setPhysicalId("igw-persisted");
        resource.setStatus("CREATE_COMPLETE");
        Ec2Service ec2 = mock(Ec2Service.class);
        var gateway = new io.github.hectorvent.floci.services.ec2.model.InternetGateway();
        gateway.setInternetGatewayId("igw-persisted");
        when(ec2.describeInternetGateways("us-east-1", List.of(), Map.of())).thenReturn(List.of(gateway));
        when(provisioner.provisionStandalone(org.mockito.ArgumentMatchers.eq("AWS::EC2::InternetGateway"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("us-east-1"),
                org.mockito.ArgumentMatchers.eq("111111111111"))).thenReturn(resource);
        AccountAwareStorageBackend<CloudControlService.PersistedRequest> requests =
                AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<CloudControlService.PersistedCreatedResource> created =
                AccountAwareStorageBackend.inMemory("000000000000");
        CloudControlService first = new CloudControlService(
                mock(S3Service.class), ec2, mock(IamService.class), provisioner,
                new ObjectMapper(), requests, created);
        CloudControlService.ProgressEvent pending = first.createResource("us-east-1", "111111111111",
                "AWS::EC2::InternetGateway", "{}");
        CloudControlService.ProgressEvent completed = first.requestStatus("111111111111", pending.requestToken());
        for (int i = 0; i < 20 && !"SUCCESS".equals(completed.operationStatus()); i++) {
            Thread.sleep(10);
            completed = first.requestStatus("111111111111", pending.requestToken());
        }
        CloudControlService restarted = new CloudControlService(
                mock(S3Service.class), ec2, mock(IamService.class), provisioner,
                new ObjectMapper(), requests, created);

        assertEquals("SUCCESS", restarted.requestStatus("111111111111", pending.requestToken()).operationStatus());
        assertEquals("igw-persisted", restarted.getResource("us-east-1", "111111111111",
                "AWS::EC2::InternetGateway", "igw-persisted").identifier());
        assertThrows(AwsException.class, () -> restarted.requestStatus("222222222222", pending.requestToken()));
        when(ec2.describeInternetGateways("us-east-1", List.of(), Map.of())).thenReturn(List.of());
        assertThrows(AwsException.class, () -> restarted.getResource("us-east-1", "222222222222",
                "AWS::EC2::InternetGateway", "igw-persisted"));
        assertThrows(AwsException.class, () -> restarted.getResource("us-east-1", "111111111111",
                "AWS::EC2::InternetGateway", "igw-persisted"));
        first.shutdown();
        restarted.shutdown();
    }

    @Test
    void restartDoesNotInventSuccessOrReplayAnInterruptedCreate() {
        var requestStore = AccountAwareStorageBackend.<CloudControlService.PersistedRequest>inMemory("000000000000");
        var createdStore = AccountAwareStorageBackend.<CloudControlService.PersistedCreatedResource>inMemory("000000000000");
        var event = new CloudControlService.ProgressEvent("AWS::EC2::VPC", null, "interrupted",
                "CREATE", "IN_PROGRESS", null, null, "000000000000");
        requestStore.putForAccount("000000000000", "interrupted",
                new CloudControlService.PersistedRequest(event, "us-east-1", "{}", 1));
        var provisioner = mock(CloudFormationResourceProvisioner.class);
        var service = new CloudControlService(mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                provisioner, new ObjectMapper(), requestStore, createdStore);
        try {
            assertEquals("FAILED", service.requestStatus("interrupted").operationStatus());
            org.mockito.Mockito.verifyNoInteractions(provisioner);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void emitsOnlyAwsShapedTagsForMalformedPersistedData() throws Exception {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-test");
        vpc.setTags(List.of(
                new Tag(null, "ignored-null"),
                new Tag("", "ignored-empty"),
                new Tag("  ", "ignored-blank"),
                new Tag("Name", null)));
        when(ec2Service.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of(vpc));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), ec2Service, mock(IamService.class),
                mock(CloudFormationResourceProvisioner.class), mapper);

        String properties = service.listResources("us-east-1", "AWS::EC2::VPC").getFirst().properties();
        JsonNode tags = mapper.readTree(properties).path("Tags");

        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertTrue(tags.get(0).path("Key").isTextual());
        assertEquals("Name", tags.get(0).path("Key").asText());
        assertTrue(tags.get(0).path("Value").isTextual());
        assertEquals("", tags.get(0).path("Value").asText());
        assertFalse(properties.contains("ignored-null"));
        assertFalse(properties.contains("ignored-empty"));
        assertFalse(properties.contains("ignored-blank"));
    }

    @Test
    void listResourcesRejectsARealButUnbackedTypeInsteadOfReturningEmpty() {
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                mock(CloudFormationResourceProvisioner.class), new ObjectMapper());

        AwsException e = assertThrows(AwsException.class,
                () -> service.listResources("us-east-1", "AWS::SQS::Queue"));

        assertEquals("UnsupportedActionException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void listResourcesRejectsATypeThatDoesNotExistInAwsAtAll() {
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                mock(CloudFormationResourceProvisioner.class), new ObjectMapper());

        AwsException e = assertThrows(AwsException.class,
                () -> service.listResources("us-east-1", "AWS::NoSuch::Type"));

        assertEquals("UnsupportedActionException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void listResourcesStillReturnsASupportedType() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.listBuckets()).thenReturn(List.of());
        CloudControlService service = new CloudControlService(
                s3Service, mock(Ec2Service.class), mock(IamService.class),
                mock(CloudFormationResourceProvisioner.class), new ObjectMapper());

        assertTrue(service.listResources("us-east-1", "AWS::S3::Bucket").isEmpty());
    }
}
