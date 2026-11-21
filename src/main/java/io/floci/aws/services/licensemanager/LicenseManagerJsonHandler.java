package io.floci.aws.services.licensemanager;

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

/** JSON 1.1 dispatch for AWS License Manager ({@code X-Amz-Target: AWSLicenseManager.<Action>}). */
@ApplicationScoped
public class LicenseManagerJsonHandler {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/license-manager-request-shapes.json", "License Manager request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long", "float", "double")
                            .contains(type) ? "value" : "size";
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

    private final LicenseConfigurations configurations;
    private final LicenseIssuance issuance;
    private final LicenseAssets assets;

    @Inject
    public LicenseManagerJsonHandler(LicenseConfigurations configurations, LicenseIssuance issuance,
                                     LicenseAssets assets) {
        this.configurations = configurations;
        this.issuance = issuance;
        this.assets = assets;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return LicenseManagerStore.validation("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "AcceptGrant" -> issuance.acceptGrant(request, region);
            case "CheckInLicense" -> issuance.checkInLicense(request, region);
            case "CheckoutBorrowLicense" -> issuance.checkoutBorrowLicense(request, region);
            case "CheckoutLicense" -> issuance.checkoutLicense(request, region);
            case "CreateGrant" -> issuance.createGrant(request, region);
            case "CreateGrantVersion" -> issuance.createGrantVersion(request, region);
            case "CreateLicense" -> issuance.createLicense(request, region);
            case "CreateLicenseAssetGroup" -> assets.createLicenseAssetGroup(request, region);
            case "CreateLicenseAssetRuleset" -> assets.createLicenseAssetRuleset(request, region);
            case "CreateLicenseConfiguration" -> configurations.createLicenseConfiguration(request, region);
            case "CreateLicenseConversionTaskForResource" -> configurations.createLicenseConversionTaskForResource(request, region);
            case "CreateLicenseManagerReportGenerator" -> configurations.createLicenseManagerReportGenerator(request, region);
            case "CreateLicenseVersion" -> issuance.createLicenseVersion(request, region);
            case "CreateToken" -> issuance.createToken(request, region);
            case "DeleteGrant" -> issuance.deleteGrant(request, region);
            case "DeleteLicense" -> issuance.deleteLicense(request, region);
            case "DeleteLicenseAssetGroup" -> assets.deleteLicenseAssetGroup(request, region);
            case "DeleteLicenseAssetRuleset" -> assets.deleteLicenseAssetRuleset(request, region);
            case "DeleteLicenseConfiguration" -> configurations.deleteLicenseConfiguration(request, region);
            case "DeleteLicenseManagerReportGenerator" -> configurations.deleteLicenseManagerReportGenerator(request, region);
            case "DeleteToken" -> issuance.deleteToken(request, region);
            case "ExtendLicenseConsumption" -> issuance.extendLicenseConsumption(request, region);
            case "GetAccessToken" -> issuance.getAccessToken(request, region);
            case "GetGrant" -> issuance.getGrant(request, region);
            case "GetLicense" -> issuance.getLicense(request, region);
            case "GetLicenseAssetGroup" -> assets.getLicenseAssetGroup(request, region);
            case "GetLicenseAssetRuleset" -> assets.getLicenseAssetRuleset(request, region);
            case "GetLicenseConfiguration" -> configurations.getLicenseConfiguration(request, region);
            case "GetLicenseConversionTask" -> configurations.getLicenseConversionTask(request, region);
            case "GetLicenseManagerReportGenerator" -> configurations.getLicenseManagerReportGenerator(request, region);
            case "GetLicenseUsage" -> issuance.getLicenseUsage(request, region);
            case "GetServiceSettings" -> configurations.getServiceSettings(request, region);
            case "ListAssetsForLicenseAssetGroup" -> assets.listAssetsForLicenseAssetGroup(request, region);
            case "ListAssociationsForLicenseConfiguration" -> configurations.listAssociationsForLicenseConfiguration(request, region);
            case "ListDistributedGrants" -> issuance.listDistributedGrants(request, region);
            case "ListFailuresForLicenseConfigurationOperations" -> configurations.listFailuresForLicenseConfigurationOperations(request, region);
            case "ListLicenseAssetGroups" -> assets.listLicenseAssetGroups(request, region);
            case "ListLicenseAssetRulesets" -> assets.listLicenseAssetRulesets(request, region);
            case "ListLicenseConfigurations" -> configurations.listLicenseConfigurations(request, region);
            case "ListLicenseConfigurationsForOrganization" -> configurations.listLicenseConfigurationsForOrganization(request, region);
            case "ListLicenseConversionTasks" -> configurations.listLicenseConversionTasks(request, region);
            case "ListLicenseManagerReportGenerators" -> configurations.listLicenseManagerReportGenerators(request, region);
            case "ListLicenseSpecificationsForResource" -> configurations.listLicenseSpecificationsForResource(request, region);
            case "ListLicenseVersions" -> issuance.listLicenseVersions(request, region);
            case "ListLicenses" -> issuance.listLicenses(request, region);
            case "ListReceivedGrants" -> issuance.listReceivedGrants(request, region);
            case "ListReceivedGrantsForOrganization" -> issuance.listReceivedGrantsForOrganization(request, region);
            case "ListReceivedLicenses" -> issuance.listReceivedLicenses(request, region);
            case "ListReceivedLicensesForOrganization" -> issuance.listReceivedLicensesForOrganization(request, region);
            case "ListResourceInventory" -> configurations.listResourceInventory(request, region);
            case "ListTagsForResource" -> configurations.listTagsForResource(request, region);
            case "ListTokens" -> issuance.listTokens(request, region);
            case "ListUsageForLicenseConfiguration" -> configurations.listUsageForLicenseConfiguration(request, region);
            case "RejectGrant" -> issuance.rejectGrant(request, region);
            case "TagResource" -> configurations.tagResource(request, region);
            case "UntagResource" -> configurations.untagResource(request, region);
            case "UpdateLicenseAssetGroup" -> assets.updateLicenseAssetGroup(request, region);
            case "UpdateLicenseAssetRuleset" -> assets.updateLicenseAssetRuleset(request, region);
            case "UpdateLicenseConfiguration" -> configurations.updateLicenseConfiguration(request, region);
            case "UpdateLicenseManagerReportGenerator" -> configurations.updateLicenseManagerReportGenerator(request, region);
            case "UpdateLicenseSpecificationsForResource" -> configurations.updateLicenseSpecificationsForResource(request, region);
            case "UpdateServiceSettings" -> configurations.updateServiceSettings(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: AWSLicenseManager." + action)).build();
        }
        return Response.ok(result).build();
    }
}
