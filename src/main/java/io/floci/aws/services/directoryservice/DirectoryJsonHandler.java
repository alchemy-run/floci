package io.floci.aws.services.directoryservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/** JSON 1.1 dispatch for AWS Directory Service ({@code X-Amz-Target: DirectoryService_20150416.<Action>}). */
@ApplicationScoped
public class DirectoryJsonHandler {

    /** Directory Service's request constraints; it reports a violation as InvalidParameterException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load("aws/ds-request-shapes.json",
            "Directory Service request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long").contains(type)
                            ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });

    private final DirectoryService directories;
    private final DirectoryFeatures features;

    @Inject
    public DirectoryJsonHandler(DirectoryService directories, DirectoryFeatures features) {
        this.directories = directories;
        this.features = features;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return DirectoryStore.invalid("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "CreateDirectory" -> directories.createDirectory(request, region);
            case "CreateMicrosoftAD" -> directories.createMicrosoftAD(request, region);
            case "ConnectDirectory" -> directories.connectDirectory(request, region);
            case "DescribeDirectories" -> directories.describeDirectories(request, region);
            case "DescribeDomainControllers" -> directories.describeDomainControllers(request, region);
            case "GetDirectoryLimits" -> directories.getDirectoryLimits(request, region);
            case "DeleteDirectory" -> directories.deleteDirectory(request, region);
            case "ResetUserPassword" -> directories.resetUserPassword(request, region);
            case "CreateComputer" -> directories.createComputer(request, region);
            case "CreateSnapshot" -> directories.createSnapshot(request, region);
            case "DescribeSnapshots" -> directories.describeSnapshots(request, region);
            case "DeleteSnapshot" -> directories.deleteSnapshot(request, region);
            case "RestoreFromSnapshot" -> directories.restoreFromSnapshot(request, region);
            case "GetSnapshotLimits" -> directories.getSnapshotLimits(request, region);
            case "UpdateNumberOfDomainControllers" -> directories.updateNumberOfDomainControllers(request, region);
            case "UpdateDirectorySetup" -> directories.updateDirectorySetup(request, region);
            case "DescribeUpdateDirectory" -> directories.describeUpdateDirectory(request, region);
            case "AddRegion" -> directories.addRegion(request, region);
            case "RemoveRegion" -> directories.removeRegion(request, region);
            case "DescribeRegions" -> directories.describeRegions(request, region);
            case "ShareDirectory" -> directories.shareDirectory(request, region);
            case "AcceptSharedDirectory" -> directories.acceptSharedDirectory(request, region);
            case "RejectSharedDirectory" -> directories.rejectSharedDirectory(request, region);
            case "UnshareDirectory" -> directories.unshareDirectory(request, region);
            case "DescribeSharedDirectories" -> directories.describeSharedDirectories(request, region);
            case "AddTagsToResource" -> directories.addTagsToResource(request, region);
            case "RemoveTagsFromResource" -> directories.removeTagsFromResource(request, region);
            case "ListTagsForResource" -> directories.listTagsForResource(request, region);
            case "CreateConditionalForwarder" -> features.createConditionalForwarder(request, region);
            case "UpdateConditionalForwarder" -> features.updateConditionalForwarder(request, region);
            case "DeleteConditionalForwarder" -> features.deleteConditionalForwarder(request, region);
            case "DescribeConditionalForwarders" -> features.describeConditionalForwarders(request, region);
            case "CreateTrust" -> features.createTrust(request, region);
            case "DescribeTrusts" -> features.describeTrusts(request, region);
            case "VerifyTrust" -> features.verifyTrust(request, region);
            case "UpdateTrust" -> features.updateTrust(request, region);
            case "DeleteTrust" -> features.deleteTrust(request, region);
            case "AddIpRoutes" -> features.addIpRoutes(request, region);
            case "RemoveIpRoutes" -> features.removeIpRoutes(request, region);
            case "ListIpRoutes" -> features.listIpRoutes(request, region);
            case "EnableRadius" -> features.enableRadius(request, region);
            case "UpdateRadius" -> features.updateRadius(request, region);
            case "DisableRadius" -> features.disableRadius(request, region);
            case "CreateAlias" -> features.createAlias(request, region);
            case "EnableSso" -> features.enableSso(request, region);
            case "DisableSso" -> features.disableSso(request, region);
            case "RegisterCertificate" -> features.registerCertificate(request, region);
            case "DescribeCertificate" -> features.describeCertificate(request, region);
            case "ListCertificates" -> features.listCertificates(request, region);
            case "DeregisterCertificate" -> features.deregisterCertificate(request, region);
            case "EnableLDAPS" -> features.enableLDAPS(request, region);
            case "DisableLDAPS" -> features.disableLDAPS(request, region);
            case "DescribeLDAPSSettings" -> features.describeLDAPSSettings(request, region);
            case "EnableClientAuthentication" -> features.enableClientAuthentication(request, region);
            case "DisableClientAuthentication" -> features.disableClientAuthentication(request, region);
            case "DescribeClientAuthenticationSettings" ->
                    features.describeClientAuthenticationSettings(request, region);
            case "DescribeSettings" -> features.describeSettings(request, region);
            case "UpdateSettings" -> features.updateSettings(request, region);
            case "StartSchemaExtension" -> features.startSchemaExtension(request, region);
            case "CancelSchemaExtension" -> features.cancelSchemaExtension(request, region);
            case "ListSchemaExtensions" -> features.listSchemaExtensions(request, region);
            case "CreateLogSubscription" -> features.createLogSubscription(request, region);
            case "DeleteLogSubscription" -> features.deleteLogSubscription(request, region);
            case "ListLogSubscriptions" -> features.listLogSubscriptions(request, region);
            case "RegisterEventTopic" -> features.registerEventTopic(request, region);
            case "DeregisterEventTopic" -> features.deregisterEventTopic(request, region);
            case "DescribeEventTopics" -> features.describeEventTopics(request, region);
            case "StartADAssessment" -> features.startADAssessment(request, region);
            case "DescribeADAssessment" -> features.describeADAssessment(request, region);
            case "ListADAssessments" -> features.listADAssessments(request, region);
            case "DeleteADAssessment" -> features.deleteADAssessment(request, region);
            case "CreateHybridAD" -> features.createHybridAD(request, region);
            case "UpdateHybridAD" -> features.updateHybridAD(request, region);
            case "DescribeHybridADUpdate" -> features.describeHybridADUpdate(request, region);
            case "EnableCAEnrollmentPolicy" -> features.enableCAEnrollmentPolicy(request, region);
            case "DisableCAEnrollmentPolicy" -> features.disableCAEnrollmentPolicy(request, region);
            case "DescribeCAEnrollmentPolicy" -> features.describeCAEnrollmentPolicy(request, region);
            case "EnableDirectoryDataAccess" -> features.enableDirectoryDataAccess(request, region);
            case "DisableDirectoryDataAccess" -> features.disableDirectoryDataAccess(request, region);
            case "DescribeDirectoryDataAccess" -> features.describeDirectoryDataAccess(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: DirectoryService_20150416." + action)).build();
        }
        return Response.ok(result).build();
    }
}
