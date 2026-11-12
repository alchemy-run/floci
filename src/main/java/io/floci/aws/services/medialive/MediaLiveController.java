package io.floci.aws.services.medialive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The AWS Elemental MediaLive REST API, routed here by {@link MediaLiveRouteFilter} and matched to operations by
 * its HTTP bindings ({@link RestJsonDispatcher}), which also rename its lowerCamel wire members to the model's. A
 * response keeps only its operation's modelled members.
 */
@Path(MediaLiveRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MediaLiveController {

    /** MediaLive's request constraints, worded as a BadRequestException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/medialive-request-shapes.json", "MediaLive request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return MediaLiveStore.badRequest(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return MediaLiveStore.badRequest(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return MediaLiveStore.badRequest(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return MediaLiveStore.badRequest(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return MediaLiveStore.badRequest(path + " is required.");
                }
            });

    private final MediaLiveInputs inputs;
    private final MediaLiveChannels channels;
    private final MediaLiveResources resources;
    private final Map<String, Set<String>> outputMembers = new HashMap<>();
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public MediaLiveController(MediaLiveInputs inputs, MediaLiveChannels channels, MediaLiveResources resources,
                               RegionResolver regions, ObjectMapper mapper) {
        this.inputs = inputs;
        this.channels = channels;
        this.resources = resources;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(
                "aws/rest-json/medialive.json")) {
            JsonNode model = mapper.readTree(in);
            model.path("operations").properties().forEach(operation -> {
                String shape = operation.getValue().path("output").path("shape").asText(null);
                Set<String> members = new HashSet<>();
                model.path("shapes").path(shape == null ? "" : shape).path("members").fieldNames()
                        .forEachRemaining(members::add);
                outputMembers.put(operation.getKey(), members);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the MediaLive bindings", e);
        }
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "medialive");
    }

    @GET
    @Path("{path: .*}")
    public Response get(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("GET", headers, uri, body);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("POST", headers, uri, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PUT", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(MediaLiveRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        ObjectNode output = run(operation, input, region);
        Set<String> members = outputMembers.getOrDefault(operation, Set.of());
        List<String> extra = new ArrayList<>();
        output.fieldNames().forEachRemaining(name -> {
            if (!members.contains(name)) {
                extra.add(name);
            }
        });
        output.remove(extra);
        return output;
    }

    private ObjectNode run(String operation, ObjectNode input, String region) {
        return switch (operation) {
            case "AcceptInputDeviceTransfer" -> inputs.acceptInputDeviceTransfer(input, region);
            case "BatchDelete" -> channels.batchDelete(input, region);
            case "BatchStart" -> channels.batchStart(input, region);
            case "BatchStop" -> channels.batchStop(input, region);
            case "BatchUpdateSchedule" -> channels.batchUpdateSchedule(input, region);
            case "CancelInputDeviceTransfer" -> inputs.cancelInputDeviceTransfer(input, region);
            case "ClaimDevice" -> inputs.claimDevice(input, region);
            case "CreateChannel" -> channels.createChannel(input, region);
            case "CreateChannelPlacementGroup" -> resources.createChannelPlacementGroup(input, region);
            case "CreateCloudWatchAlarmTemplate" -> resources.createCloudWatchAlarmTemplate(input, region);
            case "CreateCloudWatchAlarmTemplateGroup" -> resources.createCloudWatchAlarmTemplateGroup(input, region);
            case "CreateCluster" -> resources.createCluster(input, region);
            case "CreateEventBridgeRuleTemplate" -> resources.createEventBridgeRuleTemplate(input, region);
            case "CreateEventBridgeRuleTemplateGroup" -> resources.createEventBridgeRuleTemplateGroup(input, region);
            case "CreateInput" -> inputs.createInput(input, region);
            case "CreateInputSecurityGroup" -> inputs.createInputSecurityGroup(input, region);
            case "CreateMultiplex" -> channels.createMultiplex(input, region);
            case "CreateMultiplexProgram" -> channels.createMultiplexProgram(input, region);
            case "CreateNetwork" -> resources.createNetwork(input, region);
            case "CreateNode" -> resources.createNode(input, region);
            case "CreateNodeRegistrationScript" -> resources.createNodeRegistrationScript(input, region);
            case "CreatePartnerInput" -> inputs.createPartnerInput(input, region);
            case "CreateSdiSource" -> resources.createSdiSource(input, region);
            case "CreateSignalMap" -> resources.createSignalMap(input, region);
            case "CreateTags" -> resources.createTags(input, region);
            case "DeleteChannel" -> channels.deleteChannel(input, region);
            case "DeleteChannelPlacementGroup" -> resources.deleteChannelPlacementGroup(input, region);
            case "DeleteCloudWatchAlarmTemplate" -> resources.deleteCloudWatchAlarmTemplate(input, region);
            case "DeleteCloudWatchAlarmTemplateGroup" -> resources.deleteCloudWatchAlarmTemplateGroup(input, region);
            case "DeleteCluster" -> resources.deleteCluster(input, region);
            case "DeleteEventBridgeRuleTemplate" -> resources.deleteEventBridgeRuleTemplate(input, region);
            case "DeleteEventBridgeRuleTemplateGroup" -> resources.deleteEventBridgeRuleTemplateGroup(input, region);
            case "DeleteInput" -> inputs.deleteInput(input, region);
            case "DeleteInputSecurityGroup" -> inputs.deleteInputSecurityGroup(input, region);
            case "DeleteMultiplex" -> channels.deleteMultiplex(input, region);
            case "DeleteMultiplexProgram" -> channels.deleteMultiplexProgram(input, region);
            case "DeleteNetwork" -> resources.deleteNetwork(input, region);
            case "DeleteNode" -> resources.deleteNode(input, region);
            case "DeleteReservation" -> resources.deleteReservation(input, region);
            case "DeleteSchedule" -> channels.deleteSchedule(input, region);
            case "DeleteSdiSource" -> resources.deleteSdiSource(input, region);
            case "DeleteSignalMap" -> resources.deleteSignalMap(input, region);
            case "DeleteTags" -> resources.deleteTags(input, region);
            case "DescribeAccountConfiguration" -> resources.describeAccountConfiguration(input, region);
            case "DescribeChannel" -> channels.describeChannel(input, region);
            case "DescribeChannelPlacementGroup" -> resources.describeChannelPlacementGroup(input, region);
            case "DescribeCluster" -> resources.describeCluster(input, region);
            case "DescribeInput" -> inputs.describeInput(input, region);
            case "DescribeInputDevice" -> inputs.describeInputDevice(input, region);
            case "DescribeInputDeviceThumbnail" -> inputs.describeInputDeviceThumbnail(input, region);
            case "DescribeInputSecurityGroup" -> inputs.describeInputSecurityGroup(input, region);
            case "DescribeMultiplex" -> channels.describeMultiplex(input, region);
            case "DescribeMultiplexProgram" -> channels.describeMultiplexProgram(input, region);
            case "DescribeNetwork" -> resources.describeNetwork(input, region);
            case "DescribeNode" -> resources.describeNode(input, region);
            case "DescribeOffering" -> resources.describeOffering(input, region);
            case "DescribeReservation" -> resources.describeReservation(input, region);
            case "DescribeSchedule" -> channels.describeSchedule(input, region);
            case "DescribeSdiSource" -> resources.describeSdiSource(input, region);
            case "DescribeThumbnails" -> channels.describeThumbnails(input, region);
            case "GetCloudWatchAlarmTemplate" -> resources.getCloudWatchAlarmTemplate(input, region);
            case "GetCloudWatchAlarmTemplateGroup" -> resources.getCloudWatchAlarmTemplateGroup(input, region);
            case "GetEventBridgeRuleTemplate" -> resources.getEventBridgeRuleTemplate(input, region);
            case "GetEventBridgeRuleTemplateGroup" -> resources.getEventBridgeRuleTemplateGroup(input, region);
            case "GetSignalMap" -> resources.getSignalMap(input, region);
            case "ListAlerts" -> channels.listAlerts(input, region);
            case "ListChannelPlacementGroups" -> resources.listChannelPlacementGroups(input, region);
            case "ListChannels" -> channels.listChannels(input, region);
            case "ListCloudWatchAlarmTemplateGroups" -> resources.listCloudWatchAlarmTemplateGroups(input, region);
            case "ListCloudWatchAlarmTemplates" -> resources.listCloudWatchAlarmTemplates(input, region);
            case "ListClusterAlerts" -> resources.listClusterAlerts(input, region);
            case "ListClusters" -> resources.listClusters(input, region);
            case "ListEventBridgeRuleTemplateGroups" -> resources.listEventBridgeRuleTemplateGroups(input, region);
            case "ListEventBridgeRuleTemplates" -> resources.listEventBridgeRuleTemplates(input, region);
            case "ListInputDeviceTransfers" -> inputs.listInputDeviceTransfers(input, region);
            case "ListInputDevices" -> inputs.listInputDevices(input, region);
            case "ListInputSecurityGroups" -> inputs.listInputSecurityGroups(input, region);
            case "ListInputs" -> inputs.listInputs(input, region);
            case "ListMultiplexAlerts" -> channels.listMultiplexAlerts(input, region);
            case "ListMultiplexPrograms" -> channels.listMultiplexPrograms(input, region);
            case "ListMultiplexes" -> channels.listMultiplexes(input, region);
            case "ListNetworks" -> resources.listNetworks(input, region);
            case "ListNodes" -> resources.listNodes(input, region);
            case "ListOfferings" -> resources.listOfferings(input, region);
            case "ListReservations" -> resources.listReservations(input, region);
            case "ListSdiSources" -> resources.listSdiSources(input, region);
            case "ListSignalMaps" -> resources.listSignalMaps(input, region);
            case "ListTagsForResource" -> resources.listTagsForResource(input, region);
            case "ListVersions" -> resources.listVersions(input, region);
            case "PurchaseOffering" -> resources.purchaseOffering(input, region);
            case "RebootInputDevice" -> inputs.rebootInputDevice(input, region);
            case "RejectInputDeviceTransfer" -> inputs.rejectInputDeviceTransfer(input, region);
            case "RestartChannelPipelines" -> channels.restartChannelPipelines(input, region);
            case "StartChannel" -> channels.startChannel(input, region);
            case "StartDeleteMonitorDeployment" -> resources.startDeleteMonitorDeployment(input, region);
            case "StartInputDevice" -> inputs.startInputDevice(input, region);
            case "StartInputDeviceMaintenanceWindow" -> inputs.startInputDeviceMaintenanceWindow(input, region);
            case "StartMonitorDeployment" -> resources.startMonitorDeployment(input, region);
            case "StartMultiplex" -> channels.startMultiplex(input, region);
            case "StartUpdateSignalMap" -> resources.startUpdateSignalMap(input, region);
            case "StopChannel" -> channels.stopChannel(input, region);
            case "StopInputDevice" -> inputs.stopInputDevice(input, region);
            case "StopMultiplex" -> channels.stopMultiplex(input, region);
            case "TransferInputDevice" -> inputs.transferInputDevice(input, region);
            case "UpdateAccountConfiguration" -> resources.updateAccountConfiguration(input, region);
            case "UpdateChannel" -> channels.updateChannel(input, region);
            case "UpdateChannelClass" -> channels.updateChannelClass(input, region);
            case "UpdateChannelPlacementGroup" -> resources.updateChannelPlacementGroup(input, region);
            case "UpdateCloudWatchAlarmTemplate" -> resources.updateCloudWatchAlarmTemplate(input, region);
            case "UpdateCloudWatchAlarmTemplateGroup" -> resources.updateCloudWatchAlarmTemplateGroup(input, region);
            case "UpdateCluster" -> resources.updateCluster(input, region);
            case "UpdateEventBridgeRuleTemplate" -> resources.updateEventBridgeRuleTemplate(input, region);
            case "UpdateEventBridgeRuleTemplateGroup" -> resources.updateEventBridgeRuleTemplateGroup(input, region);
            case "UpdateInput" -> inputs.updateInput(input, region);
            case "UpdateInputDevice" -> inputs.updateInputDevice(input, region);
            case "UpdateInputSecurityGroup" -> inputs.updateInputSecurityGroup(input, region);
            case "UpdateMultiplex" -> channels.updateMultiplex(input, region);
            case "UpdateMultiplexProgram" -> channels.updateMultiplexProgram(input, region);
            case "UpdateNetwork" -> resources.updateNetwork(input, region);
            case "UpdateNode" -> resources.updateNode(input, region);
            case "UpdateNodeState" -> resources.updateNodeState(input, region);
            case "UpdateReservation" -> resources.updateReservation(input, region);
            case "UpdateSdiSource" -> resources.updateSdiSource(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
