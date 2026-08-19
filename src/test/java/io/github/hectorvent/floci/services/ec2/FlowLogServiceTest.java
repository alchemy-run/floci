package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowLogServiceTest {

    private FlowLogService flowLogService;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.describeInstances(any(), any(), any())).thenReturn(List.of());
        when(ec2Service.endpointNetworkInterfaces(any())).thenReturn(List.of());
        flowLogService = new FlowLogService(config, ec2Service, mock(S3Service.class),
                new InMemoryStorage<>());
    }

    @Test
    void deleteFlowLogsIsScopedToTheRequestRegion() {
        FlowLog fl = flowLogService.createFlowLog("us-east-1", "vpc-123", "VPC", "ALL",
                "s3", "arn:aws:s3:::flow-bucket", null, 600);

        List<String> otherRegion = flowLogService.deleteFlowLogs("eu-west-1", List.of(fl.getFlowLogId()));

        assertTrue(otherRegion.isEmpty(), "delete must not cross regions");
        assertEquals(1, flowLogService.describeFlowLogs("us-east-1", List.of()).size());

        List<String> sameRegion = flowLogService.deleteFlowLogs("us-east-1", List.of(fl.getFlowLogId()));

        assertEquals(List.of(fl.getFlowLogId()), sameRegion);
        assertTrue(flowLogService.describeFlowLogs("us-east-1", List.of()).isEmpty());
    }

    @Test
    void deleteFlowLogsIgnoresUnknownIds() {
        assertTrue(flowLogService.deleteFlowLogs("us-east-1", List.of("fl-doesnotexist")).isEmpty());
    }

    @Test
    void describeFlowLogsHonorsFiltersAndPagination() {
        FlowLog a = flowLogService.createFlowLog("us-east-1", "vpc-aaa", "VPC", "ALL",
                "cloud-watch-logs", null, null, 600, "/aws/vpc/a",
                "arn:aws:iam::000000000000:role/flow", List.of());
        FlowLog b = flowLogService.createFlowLog("us-east-1", "vpc-bbb", "VPC", "ACCEPT",
                "s3", "arn:aws:s3:::flow-b", null, 600);

        assertEquals(1, flowLogService.describeFlowLogs("us-east-1", List.of(),
                Map.of("resource-id", List.of("vpc-aaa")), 0, null).logs().size());
        assertEquals(a.getFlowLogId(), flowLogService.describeFlowLogs("us-east-1", List.of(),
                Map.of("log-destination-type", List.of("cloud-watch-logs")), 0, null).logs().getFirst().getFlowLogId());
        assertEquals(b.getFlowLogId(), flowLogService.describeFlowLogs("us-east-1", List.of(),
                Map.of("traffic-type", List.of("ACCEPT")), 0, null).logs().getFirst().getFlowLogId());

        FlowLogService.FlowLogListResult page = flowLogService.describeFlowLogs(
                "us-east-1", List.of(), Map.of(), 1, null);
        assertEquals(1, page.logs().size());
        assertEquals("1", page.nextToken());
        FlowLogService.FlowLogListResult rest = flowLogService.describeFlowLogs(
                "us-east-1", List.of(), Map.of(), 1, page.nextToken());
        assertEquals(1, rest.logs().size());
        assertTrue(rest.nextToken() == null);
        assertTrue(!page.logs().getFirst().getFlowLogId().equals(rest.logs().getFirst().getFlowLogId()));
    }

    @Test
    void createFlowLogDefaultsToCloudWatchWhenLogGroupNameIsSet() {
        FlowLog fl = flowLogService.createFlowLog("us-east-1", "vpc-123", "VPC", "ALL",
                null, null, null, 600, "/aws/vpc/flow",
                "arn:aws:iam::000000000000:role/flow", List.of(new Tag("env", "prod")));

        assertEquals("cloud-watch-logs", fl.getLogDestinationType());
        assertEquals("/aws/vpc/flow", fl.getLogGroupName());
        assertEquals("arn:aws:iam::000000000000:role/flow", fl.getDeliverLogsPermissionArn());
        assertEquals(1, fl.getTags().size());
        assertEquals("env", fl.getTags().getFirst().getKey());
    }
}
