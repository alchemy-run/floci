package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.services.acm.AcmJsonHandler;
import io.github.hectorvent.floci.services.acmpca.AcmPcaJsonHandler;
import io.github.hectorvent.floci.services.athena.AthenaJsonHandler;
import io.github.hectorvent.floci.services.codebuild.CodeBuildJsonHandler;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployJsonHandler;
import io.github.hectorvent.floci.services.codepipeline.CodePipelineJsonHandler;
import io.github.hectorvent.floci.services.ecr.EcrJsonHandler;
import io.github.hectorvent.floci.services.ecrpublic.EcrPublicJsonHandler;
import io.github.hectorvent.floci.services.transfer.TransferHandler;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.firehose.FirehoseJsonHandler;
import io.github.hectorvent.floci.services.fms.FmsJsonHandler;
import io.github.hectorvent.floci.services.licensemanager.LicenseManagerJsonHandler;
import io.github.hectorvent.floci.services.frauddetector.FraudDetectorJsonHandler;
import io.github.hectorvent.floci.services.glue.GlueJsonHandler;
import io.github.hectorvent.floci.services.lightsail.LightsailJsonHandler;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingJsonHandler;
import io.github.hectorvent.floci.services.bcmdataexports.BcmDataExportsJsonHandler;
import io.github.hectorvent.floci.services.budgets.BudgetsJsonHandler;
import io.github.hectorvent.floci.services.ce.CostExplorerJsonHandler;
import io.github.hectorvent.floci.services.cloudhsmv2.CloudHsmV2JsonHandler;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailJsonHandler;
import io.github.hectorvent.floci.services.applicationautoscaling.ApplicationAutoScalingJsonHandler;
import io.github.hectorvent.floci.services.cloudcontrol.CloudControlJsonHandler;
import io.github.hectorvent.floci.services.configservice.ConfigServiceJsonHandler;
import io.github.hectorvent.floci.services.cur.CurJsonHandler;
import io.github.hectorvent.floci.services.pricing.PricingJsonHandler;
import io.github.hectorvent.floci.services.comprehend.ComprehendJsonHandler;
import io.github.hectorvent.floci.services.comprehendmedical.ComprehendMedicalJsonHandler;
import io.github.hectorvent.floci.services.textract.TextractJsonHandler;
import io.github.hectorvent.floci.services.rekognition.RekognitionJsonHandler;
import io.github.hectorvent.floci.services.bedrockdataautomation.BedrockDataAutomationRuntimeJsonHandler;
import io.github.hectorvent.floci.services.transcribe.TranscribeJsonHandler;
import io.github.hectorvent.floci.services.translate.TranslateJsonHandler;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2JsonHandler;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsHandler;
import io.github.hectorvent.floci.services.cognito.CognitoIdentityJsonHandler;
import io.github.hectorvent.floci.services.cognito.CognitoJsonHandler;
import io.github.hectorvent.floci.services.cloudmap.CloudMapHandler;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler;
import io.github.hectorvent.floci.services.emr.EmrHandler;
import io.github.hectorvent.floci.services.dax.DaxJsonHandler;
import io.github.hectorvent.floci.services.memorydb.MemoryDbHandler;
import io.github.hectorvent.floci.services.wafv2.WafV2Handler;
import io.github.hectorvent.floci.services.kendra.KendraJsonHandler;
import io.github.hectorvent.floci.services.kinesis.KinesisJsonHandler;
import io.github.hectorvent.floci.services.kinesisanalytics.KinesisAnalyticsV2JsonHandler;
import io.github.hectorvent.floci.services.kms.KmsJsonHandler;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerJsonHandler;
import io.github.hectorvent.floci.services.ssm.Ec2MessagesJsonHandler;
import io.github.hectorvent.floci.services.ssm.SsmJsonHandler;
import io.github.hectorvent.floci.services.ssmcontacts.SsmContactsJsonHandler;
import io.github.hectorvent.floci.services.dms.DmsJsonHandler;
import io.github.hectorvent.floci.services.datasync.DataSyncJsonHandler;
import io.github.hectorvent.floci.services.fsx.FsxJsonHandler;
import io.github.hectorvent.floci.services.directoryservice.DirectoryServiceJsonHandler;
import io.github.hectorvent.floci.services.forecast.ForecastJsonHandler;
import io.github.hectorvent.floci.services.personalize.PersonalizeJsonHandler;
import io.github.hectorvent.floci.services.sagemaker.SageMakerJsonHandler;
import io.github.hectorvent.floci.services.globalaccelerator.GlobalAcceleratorJsonHandler;
import io.github.hectorvent.floci.services.organizations.OrganizationsJsonHandler;
import io.github.hectorvent.floci.services.identitystore.IdentityStoreJsonHandler;
import io.github.hectorvent.floci.services.ssoadmin.SsoAdminJsonHandler;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataJsonHandler;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessJsonHandler;
import io.github.hectorvent.floci.services.route53domains.Route53DomainsJsonHandler;
import io.github.hectorvent.floci.services.route53resolver.Route53ResolverJsonHandler;
import io.github.hectorvent.floci.services.servicecatalog.ServiceCatalogJsonHandler;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Generic dispatcher for all AWS services that use the application/x-amz-json-1.1 protocol.
 * Routes requests to the appropriate service handler based on the X-Amz-Target header prefix.
 * <p>
 * Currently supported services:
 * - SSM (AmazonSSM.*)
 * - EventBridge (AmazonEventBridge.*)
 * - CloudWatch Logs (Logs_20140328.*)
 */
@Path("/")
public class AwsJson11Controller {

    public static final String CONTENT_TYPE_AWS_JSON_1_1 = "application/x-amz-json-1.1";
    private static final Logger LOG = Logger.getLogger(AwsJson11Controller.class);

    private final ObjectMapper objectMapper;
    private final ObjectReader strictBodyReader;
    private final ResolvedServiceCatalog catalog;
    private final RegionResolver regionResolver;
    private final SsmJsonHandler ssmJsonHandler;
    private final SsmContactsJsonHandler ssmContactsJsonHandler;
    private final EventBridgeHandler eventBridgeHandler;
    private final CloudMapHandler cloudMapHandler;
    private final EmrHandler emrHandler;
    private final MemoryDbHandler memoryDbHandler;
    private final WafV2Handler wafV2Handler;
    private final CloudWatchLogsHandler cloudWatchLogsHandler;
    private final SecretsManagerJsonHandler secretsManagerJsonHandler;
    private final KendraJsonHandler kendraJsonHandler;
    private final KinesisJsonHandler kinesisJsonHandler;
    private final KinesisAnalyticsV2JsonHandler kinesisAnalyticsV2JsonHandler;
    private final ApiGatewayV2JsonHandler apigwV2JsonHandler;
    private final KmsJsonHandler kmsJsonHandler;
    private final CognitoJsonHandler cognitoJsonHandler;
    private final CognitoIdentityJsonHandler cognitoIdentityJsonHandler;
    private final AcmJsonHandler acmJsonHandler;
    private final AcmPcaJsonHandler acmPcaJsonHandler;
    private final EcsJsonHandler ecsJsonHandler;
    private final EcrJsonHandler ecrJsonHandler;
    private final EcrPublicJsonHandler ecrPublicJsonHandler;
    private final GlueJsonHandler glueJsonHandler;
    private final AthenaJsonHandler athenaJsonHandler;
    private final FirehoseJsonHandler firehoseJsonHandler;
    private final FmsJsonHandler fmsJsonHandler;
    private final LicenseManagerJsonHandler licenseManagerJsonHandler;
    private final FraudDetectorJsonHandler fraudDetectorJsonHandler;
    private final ResourceGroupsTaggingJsonHandler resourceGroupsTaggingJsonHandler;
    private final CodeBuildJsonHandler codeBuildJsonHandler;
    private final CodeDeployJsonHandler codeDeployJsonHandler;
    private final CodePipelineJsonHandler codePipelineJsonHandler;
    private final Ec2MessagesJsonHandler ec2MessagesJsonHandler;
    private final TransferHandler transferHandler;
    private final TextractJsonHandler textractJsonHandler;
    private final RekognitionJsonHandler rekognitionJsonHandler;
    private final BedrockDataAutomationRuntimeJsonHandler bedrockDataAutomationRuntimeJsonHandler;
    private final ComprehendJsonHandler comprehendJsonHandler;
    private final ComprehendMedicalJsonHandler comprehendMedicalJsonHandler;
    private final PricingJsonHandler pricingJsonHandler;
    private final TranscribeJsonHandler transcribeJsonHandler;
    private final TranslateJsonHandler translateJsonHandler;
    private final CostExplorerJsonHandler costExplorerJsonHandler;
    private final CurJsonHandler curJsonHandler;
    private final BcmDataExportsJsonHandler bcmDataExportsJsonHandler;
    private final BudgetsJsonHandler budgetsJsonHandler;
    private final ConfigServiceJsonHandler configServiceJsonHandler;
    private final CloudTrailJsonHandler cloudTrailJsonHandler;
    private final CloudHsmV2JsonHandler cloudHsmV2JsonHandler;
    private final LightsailJsonHandler lightsailJsonHandler;
    private final CloudControlJsonHandler cloudControlJsonHandler;
    private final ApplicationAutoScalingJsonHandler applicationAutoScalingJsonHandler;
    private final DmsJsonHandler dmsJsonHandler;
    private final DataSyncJsonHandler dataSyncJsonHandler;
    private final FsxJsonHandler fsxJsonHandler;
    private final DirectoryServiceJsonHandler directoryServiceJsonHandler;
    private final DaxJsonHandler daxJsonHandler;
    private final ForecastJsonHandler forecastJsonHandler;
    private final PersonalizeJsonHandler personalizeJsonHandler;
    private final SageMakerJsonHandler sageMakerJsonHandler;
    private final GlobalAcceleratorJsonHandler globalAcceleratorJsonHandler;
    private final OrganizationsJsonHandler organizationsJsonHandler;
    private final IdentityStoreJsonHandler identityStoreJsonHandler;
    private final SsoAdminJsonHandler ssoAdminJsonHandler;
    private final RedshiftServerlessJsonHandler redshiftServerlessJsonHandler;
    private final RedshiftDataJsonHandler redshiftDataJsonHandler;
    private final Route53DomainsJsonHandler route53DomainsJsonHandler;
    private final Route53ResolverJsonHandler route53ResolverJsonHandler;
    private final ServiceCatalogJsonHandler serviceCatalogJsonHandler;

    @Inject
    public AwsJson11Controller(ObjectMapper objectMapper, ResolvedServiceCatalog catalog,
                               RegionResolver regionResolver,
                               SsmJsonHandler ssmJsonHandler,
                               SsmContactsJsonHandler ssmContactsJsonHandler, EventBridgeHandler eventBridgeHandler,
                               CloudMapHandler cloudMapHandler,
                               EmrHandler emrHandler,
                               MemoryDbHandler memoryDbHandler,
                               WafV2Handler wafV2Handler,
                               CloudWatchLogsHandler cloudWatchLogsHandler,
                               SecretsManagerJsonHandler secretsManagerJsonHandler,
                               KendraJsonHandler kendraJsonHandler,
                               KinesisJsonHandler kinesisJsonHandler,
                               KinesisAnalyticsV2JsonHandler kinesisAnalyticsV2JsonHandler,
                               ApiGatewayV2JsonHandler apigwV2JsonHandler,
                               KmsJsonHandler kmsJsonHandler, CognitoJsonHandler cognitoJsonHandler,
                               CognitoIdentityJsonHandler cognitoIdentityJsonHandler,
                               AcmJsonHandler acmJsonHandler, AcmPcaJsonHandler acmPcaJsonHandler,
                               EcsJsonHandler ecsJsonHandler,
                               EcrJsonHandler ecrJsonHandler, EcrPublicJsonHandler ecrPublicJsonHandler,
                               GlueJsonHandler glueJsonHandler,
                               AthenaJsonHandler athenaJsonHandler,
                               FirehoseJsonHandler firehoseJsonHandler,
                               FmsJsonHandler fmsJsonHandler,
                               LicenseManagerJsonHandler licenseManagerJsonHandler,
                               FraudDetectorJsonHandler fraudDetectorJsonHandler,
                               ResourceGroupsTaggingJsonHandler resourceGroupsTaggingJsonHandler,
                               CodeBuildJsonHandler codeBuildJsonHandler,
                               CodeDeployJsonHandler codeDeployJsonHandler,
                               CodePipelineJsonHandler codePipelineJsonHandler,
                               Ec2MessagesJsonHandler ec2MessagesJsonHandler,
                               TransferHandler transferHandler,
                               TextractJsonHandler textractJsonHandler,
                               RekognitionJsonHandler rekognitionJsonHandler,
                               BedrockDataAutomationRuntimeJsonHandler bedrockDataAutomationRuntimeJsonHandler,
                               ComprehendJsonHandler comprehendJsonHandler,
                               ComprehendMedicalJsonHandler comprehendMedicalJsonHandler,
                               PricingJsonHandler pricingJsonHandler,
                               TranscribeJsonHandler transcribeJsonHandler,
                               TranslateJsonHandler translateJsonHandler,
                               CostExplorerJsonHandler costExplorerJsonHandler,
                               CurJsonHandler curJsonHandler,
                               BcmDataExportsJsonHandler bcmDataExportsJsonHandler,
                               BudgetsJsonHandler budgetsJsonHandler,
                               ConfigServiceJsonHandler configServiceJsonHandler,
                               CloudTrailJsonHandler cloudTrailJsonHandler,
                               CloudHsmV2JsonHandler cloudHsmV2JsonHandler,
                               LightsailJsonHandler lightsailJsonHandler,
                               CloudControlJsonHandler cloudControlJsonHandler,
                               ApplicationAutoScalingJsonHandler applicationAutoScalingJsonHandler,
                               DmsJsonHandler dmsJsonHandler,
                               DataSyncJsonHandler dataSyncJsonHandler,
                               FsxJsonHandler fsxJsonHandler,
                               DirectoryServiceJsonHandler directoryServiceJsonHandler,
                               DaxJsonHandler daxJsonHandler,
                               ForecastJsonHandler forecastJsonHandler,
                               PersonalizeJsonHandler personalizeJsonHandler,
                               SageMakerJsonHandler sageMakerJsonHandler,
                               GlobalAcceleratorJsonHandler globalAcceleratorJsonHandler,
                               OrganizationsJsonHandler organizationsJsonHandler,
                               IdentityStoreJsonHandler identityStoreJsonHandler,
                               SsoAdminJsonHandler ssoAdminJsonHandler,
                               RedshiftServerlessJsonHandler redshiftServerlessJsonHandler,
                               RedshiftDataJsonHandler redshiftDataJsonHandler,
                               Route53DomainsJsonHandler route53DomainsJsonHandler,
                               Route53ResolverJsonHandler route53ResolverJsonHandler,
                               ServiceCatalogJsonHandler serviceCatalogJsonHandler) {
        this.objectMapper = objectMapper;
        this.strictBodyReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.catalog = catalog;
        this.regionResolver = regionResolver;
        this.ssmJsonHandler = ssmJsonHandler;
        this.ssmContactsJsonHandler = ssmContactsJsonHandler;
        this.eventBridgeHandler = eventBridgeHandler;
        this.cloudMapHandler = cloudMapHandler;
        this.emrHandler = emrHandler;
        this.memoryDbHandler = memoryDbHandler;
        this.wafV2Handler = wafV2Handler;
        this.cloudWatchLogsHandler = cloudWatchLogsHandler;
        this.secretsManagerJsonHandler = secretsManagerJsonHandler;
        this.kendraJsonHandler = kendraJsonHandler;
        this.kinesisJsonHandler = kinesisJsonHandler;
        this.kinesisAnalyticsV2JsonHandler = kinesisAnalyticsV2JsonHandler;
        this.apigwV2JsonHandler = apigwV2JsonHandler;
        this.kmsJsonHandler = kmsJsonHandler;
        this.cognitoJsonHandler = cognitoJsonHandler;
        this.cognitoIdentityJsonHandler = cognitoIdentityJsonHandler;
        this.acmJsonHandler = acmJsonHandler;
        this.acmPcaJsonHandler = acmPcaJsonHandler;
        this.ecsJsonHandler = ecsJsonHandler;
        this.ecrJsonHandler = ecrJsonHandler;
        this.ecrPublicJsonHandler = ecrPublicJsonHandler;
        this.glueJsonHandler = glueJsonHandler;
        this.athenaJsonHandler = athenaJsonHandler;
        this.firehoseJsonHandler = firehoseJsonHandler;
        this.fmsJsonHandler = fmsJsonHandler;
        this.licenseManagerJsonHandler = licenseManagerJsonHandler;
        this.fraudDetectorJsonHandler = fraudDetectorJsonHandler;
        this.resourceGroupsTaggingJsonHandler = resourceGroupsTaggingJsonHandler;
        this.codeBuildJsonHandler = codeBuildJsonHandler;
        this.codeDeployJsonHandler = codeDeployJsonHandler;
        this.codePipelineJsonHandler = codePipelineJsonHandler;
        this.ec2MessagesJsonHandler = ec2MessagesJsonHandler;
        this.transferHandler = transferHandler;
        this.textractJsonHandler = textractJsonHandler;
        this.rekognitionJsonHandler = rekognitionJsonHandler;
        this.bedrockDataAutomationRuntimeJsonHandler = bedrockDataAutomationRuntimeJsonHandler;
        this.comprehendJsonHandler = comprehendJsonHandler;
        this.comprehendMedicalJsonHandler = comprehendMedicalJsonHandler;
        this.pricingJsonHandler = pricingJsonHandler;
        this.transcribeJsonHandler = transcribeJsonHandler;
        this.translateJsonHandler = translateJsonHandler;
        this.costExplorerJsonHandler = costExplorerJsonHandler;
        this.curJsonHandler = curJsonHandler;
        this.bcmDataExportsJsonHandler = bcmDataExportsJsonHandler;
        this.budgetsJsonHandler = budgetsJsonHandler;
        this.configServiceJsonHandler = configServiceJsonHandler;
        this.cloudTrailJsonHandler = cloudTrailJsonHandler;
        this.cloudHsmV2JsonHandler = cloudHsmV2JsonHandler;
        this.lightsailJsonHandler = lightsailJsonHandler;
        this.cloudControlJsonHandler = cloudControlJsonHandler;
        this.applicationAutoScalingJsonHandler = applicationAutoScalingJsonHandler;
        this.dmsJsonHandler = dmsJsonHandler;
        this.dataSyncJsonHandler = dataSyncJsonHandler;
        this.fsxJsonHandler = fsxJsonHandler;
        this.directoryServiceJsonHandler = directoryServiceJsonHandler;
        this.daxJsonHandler = daxJsonHandler;
        this.forecastJsonHandler = forecastJsonHandler;
        this.personalizeJsonHandler = personalizeJsonHandler;
        this.sageMakerJsonHandler = sageMakerJsonHandler;
        this.globalAcceleratorJsonHandler = globalAcceleratorJsonHandler;
        this.organizationsJsonHandler = organizationsJsonHandler;
        this.identityStoreJsonHandler = identityStoreJsonHandler;
        this.ssoAdminJsonHandler = ssoAdminJsonHandler;
        this.redshiftServerlessJsonHandler = redshiftServerlessJsonHandler;
        this.redshiftDataJsonHandler = redshiftDataJsonHandler;
        this.route53DomainsJsonHandler = route53DomainsJsonHandler;
        this.route53ResolverJsonHandler = route53ResolverJsonHandler;
        this.serviceCatalogJsonHandler = serviceCatalogJsonHandler;
    }

    @POST
    @Consumes(CONTENT_TYPE_AWS_JSON_1_1)
    @Produces(CONTENT_TYPE_AWS_JSON_1_1)
    public Response handle(
            @HeaderParam("X-Amz-Target") String target,
            @Context HttpHeaders httpHeaders,
            String body) {

        if (target == null) {
            return null;
        }

        ServiceCatalog.TargetMatch targetMatch = catalog.matchTarget(target).orElse(null);
        if (targetMatch == null) {
            return JsonErrorResponseUtils.createUnknownOperationErrorResponse(target);
        }

        String serviceKey = targetMatch.descriptor().externalKey();
        String action = targetMatch.action();
        LOG.infov("AwsJson11Controller {0} action: {1}", serviceKey, action);

        JsonNode request;
        try {
            request = strictBodyReader.readTree(body);
        } catch (JsonProcessingException e) {
            return JsonErrorResponseUtils.createSerializationErrorResponse();
        }

        try {
            String region = regionResolver.resolveRegion(httpHeaders);

            Response delegated = switch (serviceKey) {
                case "ssm" -> ssmJsonHandler.handle(action, request, region);
                case "ssm-contacts" -> ssmContactsJsonHandler.handle(action, request, region);
                case "events" -> eventBridgeHandler.handle(action, request, region);
                case "servicediscovery" -> cloudMapHandler.handle(action, request, region);
                case "elasticmapreduce" -> emrHandler.handle(action, request, region);
                case "wafv2" -> wafV2Handler.handle(action, request, region);
                case "memorydb" -> memoryDbHandler.handle(action, request, region);
                case "dax" -> daxJsonHandler.handle(action, request, region);
                case "logs" -> cloudWatchLogsHandler.handle(action, request, region);
                case "secretsmanager" -> secretsManagerJsonHandler.handle(action, request, region);
                case "kendra" -> kendraJsonHandler.handle(action, request, region);
                case "kinesis" -> kinesisJsonHandler.handle(action, request, region);
                case "kinesisanalytics" -> kinesisAnalyticsV2JsonHandler.handle(action, request, region);
                case "apigatewayv2" -> apigwV2JsonHandler.handle(action, request, region);
                case "kms" -> kmsJsonHandler.handle(action, request, region);
                case "cognito-idp" -> cognitoJsonHandler.handle(action, request, region);
                case "cognito-identity" -> cognitoIdentityJsonHandler.handle(action, request, region);
                case "acm" -> acmJsonHandler.handle(action, request, region);
                case "acm-pca" -> acmPcaJsonHandler.handle(action, request, region);
                case "ecs" -> ecsJsonHandler.handle(action, request, region);
                case "ecr" -> ecrJsonHandler.handle(action, request, region);
                case "ecr-public" -> ecrPublicJsonHandler.handle(action, request, region);
                case "glue" -> glueJsonHandler.handle(action, request, region);
                case "athena" -> athenaJsonHandler.handle(action, request, region);
                case "firehose" -> firehoseJsonHandler.handle(action, request, region);
                case "fms" -> fmsJsonHandler.handle(action, request, region);
                case "license-manager" -> licenseManagerJsonHandler.handle(action, request, region);
                case "frauddetector" -> fraudDetectorJsonHandler.handle(action, request, region);
                case "tagging" -> resourceGroupsTaggingJsonHandler.handle(action, request, region);
                case "codebuild" -> codeBuildJsonHandler.handle(action, request, region, regionResolver.getAccountId());
                case "codedeploy" -> codeDeployJsonHandler.handle(action, request, region);
                case "codepipeline" -> codePipelineJsonHandler.handle(
                        action, request, region, regionResolver.getAccountId());
                case "ec2messages" -> ec2MessagesJsonHandler.handle(action, request, region);
                case "transfer" -> transferHandler.handle(action, request, region);
                case "textract" -> textractJsonHandler.handle(action, request, region);
                case "rekognition" -> rekognitionJsonHandler.handle(action, request, region);
                case "bedrock-data-automation-runtime" -> bedrockDataAutomationRuntimeJsonHandler.handle(
                        action, request, region);
                case "comprehend" -> comprehendJsonHandler.handle(action, request, region);
                case "comprehendmedical" -> comprehendMedicalJsonHandler.handle(action, request, region);
                case "pricing" -> pricingJsonHandler.handle(action, request, region);
                case "transcribe" -> transcribeJsonHandler.handle(action, request, region);
                case "translate" -> translateJsonHandler.handle(action, request, region);
                case "ce" -> costExplorerJsonHandler.handle(action, request, region);
                case "cur" -> curJsonHandler.handle(action, request, region);
                case "bcm-data-exports" -> bcmDataExportsJsonHandler.handle(action, request, region);
                case "budgets" -> budgetsJsonHandler.handle(action, request, region);
                case "config" -> configServiceJsonHandler.handle(action, request, region);
                case "cloudtrail" -> cloudTrailJsonHandler.handle(action, request, region);
                case "cloudhsmv2" -> cloudHsmV2JsonHandler.handle(action, request, region);
                case "application-autoscaling" -> applicationAutoScalingJsonHandler.handle(action, request, region);
                case "lightsail" -> lightsailJsonHandler.handle(action, request, region);
                case "cloudcontrol" -> cloudControlJsonHandler.handle(action, request, region);
                case "dms" -> dmsJsonHandler.handle(action, request, region);
                case "datasync" -> dataSyncJsonHandler.handle(action, request, region);
                case "fsx" -> fsxJsonHandler.handle(action, request, region);
                case "ds" -> directoryServiceJsonHandler.handle(action, request, region);
                case "forecast" -> forecastJsonHandler.handle(action, request, region);
                case "personalize" -> personalizeJsonHandler.handle(action, request, region);
                case "sagemaker" -> sageMakerJsonHandler.handle(action, request, region);
                case "globalaccelerator" -> globalAcceleratorJsonHandler.handle(action, request, region);
                case "organizations" -> organizationsJsonHandler.handle(action, request, region);
                case "identitystore" -> identityStoreJsonHandler.handle(action, request, region);
                case "sso-admin" -> ssoAdminJsonHandler.handle(action, request, region);
                case "redshift-serverless" -> redshiftServerlessJsonHandler.handle(action, request, region);
                case "redshift-data" -> redshiftDataJsonHandler.handle(action, request, region);
                case "route53domains" -> route53DomainsJsonHandler.handle(action, request, region);
                case "route53resolver" -> route53ResolverJsonHandler.handle(action, request, region);
                case "servicecatalog" -> serviceCatalogJsonHandler.handle(action, request, region);
                default -> null;
            };
            // catalog.matchTarget is protocol-agnostic: a JSON 1.0 target
            // (e.g. DynamoDB_20120810.*) can match here under @Consumes json-1.1.
            // Return the AWS-style unknown-operation error rather than null.
            if (delegated == null) {
                return JsonErrorResponseUtils.createUnknownOperationErrorResponse(target);
            }
            return delegated;
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.errorf(e, "Error processing %s request", serviceKey);
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

}
