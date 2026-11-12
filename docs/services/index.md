# Services Overview

Floci emulates the AWS services listed below on a single port (`4566`). All services use the real AWS wire protocol, your existing AWS CLI commands and SDK clients work without modification.

This page indexes supported services and their operation references. Some services expose separate control-plane and data-plane rows below. Other docs (and the README) should link here rather than duplicating the table.

## Service Matrix

For services extended by this fork, follow the service-page link for the combined action list and limitations rather than relying on a pre-merge count. Remaining numeric counts are upstream inventory snapshots. For dispatch-table services (Query and JSON 1.1) each count reflects one case per AWS action in the handler. For REST-based services (S3, Lambda, API Gateway v1) the count reflects distinct AWS SDK operations, collapsing routes where one JAX-RS handler fans out via query-string or header markers (e.g. `PUT /{bucket}/{key}` → `PutObject`, `PutObjectTagging`, `PutObjectAcl`, etc.).

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [SSM](ssm.md) | `POST /` + `X-Amz-Target: AmazonSSM.*` / `AmazonSSMMessageDeliveryService.*` | JSON 1.1 | 22 |
| [SQS](sqs.md) | `POST /` with `Action=` param | Query / JSON | 23 |
| [SNS](sns.md) | `POST /` with `Action=` param | Query / JSON | 39 |
| [S3](s3.md) | `/{bucket}/{key}` | REST XML | 58 |
| [S3 Control](s3control.md) | `/v20180820/*` + `x-amz-account-id` | REST XML | 97 |
| [S3 Vectors](s3vectors.md) | `POST /{OperationName}` | REST JSON | 12 |
| [S3 Tables](s3tables.md) | `/buckets`, `/namespaces/*`, `/tables/*` | REST JSON | 25 |
| [DynamoDB](dynamodb.md) | `POST /` + `X-Amz-Target: DynamoDB_20120810.*` | JSON 1.1 | 28 |
| [DynamoDB Streams](dynamodb.md#streams) | `POST /` + `X-Amz-Target: DynamoDBStreams_20120810.*` | JSON 1.1 | 4 |
| [Lambda](lambda.md) | `/2015-03-31/functions/...` + `/2025-12-01/durable-executions/...` | REST JSON | 55 |
| [Lambda MicroVMs](lambda-microvms.md) | `/2025-09-09/...` + `/2026-04-04/...` | REST JSON | 22 |
| [Lambda Web Functions](lambda-web-functions.md) | `/2025-03-07/web-functions/...` | REST JSON | 20 |
| [API Gateway v1](api-gateway.md) | `/restapis/...` | REST JSON | 80 |
| [API Gateway v2](api-gateway.md#v2) | `/v2/apis/...` | REST JSON | 53 + data-plane |
| [IAM](iam.md) | `POST /` with `Action=` param | Query | 180 |
| [STS](sts.md) | `POST /` with `Action=` param | Query | 11 |
| [AWS Sign-In](iam.md#aws-sign-in-login-credentials) | `/v1/authorize`, `/v1/token` | REST JSON | 2 |
| [Organizations](organizations.md) | `POST /` + `X-Amz-Target: AWSOrganizationsV20161128.*` | JSON 1.1 | 57 |
| [Cognito](cognito.md) | `POST /` + `X-Amz-Target: AWSCognitoIdentityProviderService.*` | JSON 1.1 | 94 |
| [Cognito Identity](cognitoidentity.md) | `POST /` + `X-Amz-Target: AWSCognitoIdentityService.*` | JSON 1.1 | 18 |
| [Global Accelerator](globalaccelerator.md) | `POST /` + `X-Amz-Target: GlobalAccelerator_V20180706.*` | JSON 1.1 | 22 |
| [KMS](kms.md) | `POST /` + `X-Amz-Target: TrentService.*` | JSON 1.1 | 45 |
| [CloudHSM v2](cloudhsmv2.md) | `POST /` + `X-Amz-Target: BaldrApiService.*` | JSON 1.1 | 18 |
| [Kinesis](kinesis.md) | `POST /` + `X-Amz-Target: Kinesis_20131202.*` | JSON 1.1 | 38 |
| [Managed Service for Apache Flink](kinesisanalytics.md) | `POST /` + `X-Amz-Target: KinesisAnalytics_20180523.*` | JSON 1.1 | 23 |
| [Payment Cryptography](payment-cryptography.md) | `POST /` + `X-Amz-Target: PaymentCryptographyControlPlane.*` | JSON 1.0 | 12 |
| [Payment Cryptography data plane](payment-cryptography-data.md) | `POST /keys/{KeyIdentifier}/encrypt` and the rest | REST JSON | 5 |
| [Secrets Manager](secrets-manager.md) | `POST /` + `X-Amz-Target: secretsmanager.*` | JSON 1.1 | 23 |
| [Step Functions](step-functions.md) | `POST /` + `X-Amz-Target: AmazonStatesService.*` | JSON 1.1 | 36 |
| [SWF](swf.md) | `POST /` + `X-Amz-Target: SimpleWorkflowService.*` | JSON 1.0 | 39 |
| [CloudFormation](cloudformation.md) | `POST /` with `Action=` param | Query | 40 |
| [Cloud Control API](cloudcontrol.md) | `POST /` + `X-Amz-Target: CloudApiService.*` | JSON 1.1 | 5 |
| [EventBridge](eventbridge.md) | `POST /` + `X-Amz-Target: AmazonEventBridge.*` | JSON 1.1 | 46 |
| [EventBridge V2](eventbridgev2.md) | `POST /service/AWSEventsV2/operation/*` | Smithy RPC v2 CBOR | 10 |
| [EventBridge Scheduler](scheduler.md) | `/schedules/*`, `/schedule-groups/*`, `/tags/*` | REST JSON | [See actions](scheduler.md) |
| [EventBridge Pipes](pipes.md) | `/v1/pipes/*`, `/tags/*` | REST JSON | [See actions](pipes.md) |
| [CloudWatch OAM](oam.md) | REST paths such as `POST /CreateSink` and `POST /CreateLink` | REST JSON | 15 |
| [CloudWatch Logs](cloudwatch.md) | `POST /` + `X-Amz-Target: Logs.*` | JSON 1.1 | [See actions](cloudwatch.md) |
| [CloudWatch Metrics](cloudwatch.md#metrics) | `POST /` with `Action=` or JSON 1.1 | Query / JSON | [See actions](cloudwatch.md#metrics) |
| [CloudWatch RUM](rum.md) | `/appmonitor`, `/appmonitor/{name}`, `/appmonitors` | REST JSON | 5 |
| [GuardDuty](guardduty.md) | `/detector`, `/detector/{detectorId}`, `/detector/{detectorId}/admin`, `/admin/*`, `/tags/*` | REST JSON | 28 |
| [AWS Account Management](account.md) | `/putAlternateContact`, `/getAlternateContact`, `/deleteAlternateContact` | REST JSON | 11 |
| [IAM Access Analyzer](access-analyzer.md) | `/analyzer`, `/analyzer/{name}`, `/policy/validation` | REST JSON | 34 |
| [IAM Identity Center (SSO Admin)](ssoadmin.md) | `POST /` + `X-Amz-Target: SWBExternalService.*` | JSON 1.1 | 79 |
| [IAM Identity Center OIDC](ssooidc.md) | `/client/register`, `/device_authorization`, `/token`, `/token?aws_iam=t`, `/authorize`, `/device` | REST JSON | 4 |
| [IAM Identity Center Access Portal](ssoportal.md) | `/assignment/accounts`, `/assignment/roles`, `/federation/credentials`, `/logout` | REST JSON | 4 |
| [Identity Store](identitystore.md) | `POST /` + `X-Amz-Target: AWSIdentityStore.*` | JSON 1.1 | 19 |
| [Amazon Macie](macie2.md) | `/admin`, `/macie`, `/admin/configuration` | REST JSON | 49 |
| [Amazon Inspector](inspector2.md) | `/delegatedadminaccounts/*`, `/status/batch/get`, `/enable`, `/disable`, `/organizationconfiguration/*` | REST JSON | 29 |
| [Amazon Verified Permissions](verifiedpermissions.md) | `POST /` + `X-Amz-Target: VerifiedPermissions.*` | JSON 1.0 | 34 |
| [Security Hub](securityhub.md) | `/organization/*`, `/accounts`, `/findingAggregator/*`, `/configurationPolicy*`, `/standards*`, `/tags/*` | REST JSON | 58 |
| [Amazon Detective](detective.md) | `/orgs/*`, `/graphs/list`, `/graph/*` | REST JSON | 21 |
| [Amazon Connect](connect.md) | `/instance`, `/instance/{instanceId}/*`, `/tags/*` | REST JSON | 15 |
| [Amazon AppIntegrations](appintegrations.md) | `/eventIntegrations/*`, `/dataIntegrations/*`, `/tags/*` | REST JSON | 23 |
| [Amazon Data Lifecycle Manager](dlm.md) | `/policies`, `/policies/{policyId}`, `/tags/{resourceArn}` | REST JSON | 8 |
| [ElastiCache](elasticache.md) | `POST /` with `Action=` param + TCP proxy | Query + RESP | 42 |
| [MemoryDB](memorydb.md) | `POST /` + `X-Amz-Target: AmazonMemoryDB.*` + TCP proxy | JSON 1.1 + RESP | 33 |
| [Aurora DSQL](dsql.md) | `/cluster`, `/cluster/{identifier}`, `/cluster/{identifier}/policy`, `/stream/{clusterIdentifier}` + TLS endpoint | REST JSON + wire | 13 |
| [RDS](rds.md) | `POST /` with `Action=` param + TCP proxy | Query + wire | 119 |
| [RDS Data API](rds-data.md) | `/Execute`, `/BeginTransaction`, `/CommitTransaction`, `/RollbackTransaction` | REST JSON | 4 |
| [Timestream for InfluxDB](timestream-influxdb.md) | `POST /` + `X-Amz-Target: AmazonTimestreamInfluxDB.*` + InfluxDB container | JSON 1.0 + InfluxDB HTTP | 24 |
| [Timestream for LiveAnalytics](timestream.md) | `POST /` + `X-Amz-Target: Timestream_20181101.*` (not-onboarded account) | JSON 1.0 | 30 |
| [MSK](msk.md) | `/v1/clusters/...`, `/api/v2/clusters/...` + Redpanda broker | REST JSON + Kafka | 8 |
| [Amazon MQ](amazonmq.md) | `/v1/brokers/...` + RabbitMQ broker | REST JSON + AMQP | 5 |
| [Athena](athena.md) | `POST /` + `X-Amz-Target: AmazonAthena.*` | JSON 1.1 | [See actions](athena.md) |
| [Glue](glue.md) | `POST /` + `X-Amz-Target: AWSGlue.*` | JSON 1.1 | [See actions](glue.md) |
| [Neptune](neptune.md) | `POST /` with `Action=` param + Gremlin TCP proxy | Query + WebSocket | [See actions](neptune.md) |
| [MWAA](mwaa.md) | `/` REST paths for environments + CLI/web proxy | REST JSON | 10 |
| [Athena](athena.md) | `POST /` + `X-Amz-Target: AmazonAthena.*` | JSON 1.1 | 36 |
| [Glue](glue.md) | `POST /` + `X-Amz-Target: AWSGlue.*` | JSON 1.1 | 137 |
| [Lake Formation](lakeformation.md) | `POST /<Action>` | REST JSON | 16 |
| [Neptune](neptune.md) | `POST /` with `Action=` param + Gremlin TCP proxy | Query + WebSocket | 14 |
| [DocumentDB](docdb.md) | `POST /` with `Action=` param + MongoDB container | Query + MongoDB wire | 13 |
| [DMS](dms.md) | `POST /` + `X-Amz-Target: AmazonDMSv20160101.*` | JSON 1.1 | 33 |
| [QuickSight](quicksight.md) | `/accounts/{AwsAccountId}/...` | REST JSON, metadata only | 16 |
| [Redshift](redshift.md) | `POST /` with `Action=` param + PostgreSQL container | Query + PostgreSQL wire (+ CFN) | 56 |
| [Redshift Data API](redshift-data.md) | `POST /` + `X-Amz-Target: RedshiftData.*` | JSON 1.1 | 12 |
| [Redshift Serverless](redshift-serverless.md) | `POST /` + `X-Amz-Target: RedshiftServerless.*` | JSON 1.1 | 63 |
| [EMR](emr.md) | `POST /` + `X-Amz-Target: ElasticMapReduce.*` | JSON 1.1 | 41 |
| [EMR Serverless](emr-serverless.md) | `/applications/*`, `/tags/*` | REST JSON | 15 |
| [Data Firehose](firehose.md) | `POST /` + `X-Amz-Target: Firehose_20150804.*` | JSON 1.1 | 12 |
| [ECS](ecs.md) | `POST /` + `X-Amz-Target: AmazonEC2ContainerServiceV20141113.*` | JSON 1.1 | 59 |
| [EFS](efs.md) | `/2015-02-01/...` | REST JSON | 27 |
| [EC2](ec2.md) | `POST /` with `Action=` param | EC2 Query | 237 |
| [Lightsail](lightsail.md) | `POST /` + `X-Amz-Target: Lightsail_20161128.*` | JSON 1.1 | 79 local responses; 161 recognized actions |
| [ACM](acm.md) | `POST /` + `X-Amz-Target: CertificateManager.*` | JSON 1.1 | 17 |
| [App Runner](apprunner.md) | `POST /` + `X-Amz-Target: AppRunner.*`; services by `Host` | JSON 1.0 | 37 |
| [DataBrew](databrew.md) | `/datasets`, `/recipes`, `/projects`, `/jobs`, `/rulesets`, `/schedules` (signed `databrew`) | REST JSON | 44 |
| [Data Exchange](dataexchange.md) | `/v1/data-sets`, `/v1/jobs`, `/v1/data-grants`, `/v1/event-actions` (signed `dataexchange`) | REST JSON | 37 |
| [DataZone](datazone.md) | `/v2/domains`, `/tags/{resourceArn}` (signed `datazone`) | REST JSON | 195 |
| [Deadline Cloud](deadline.md) | `/2023-10-12/farms`, `/2023-10-12/monitors`, `/2023-10-12/license-endpoints` (signed `deadline`) | REST JSON | 127 |
| [DevOps Guru](devopsguru.md) | `/insights`, `/channels`, `/resource-collections`, `/accounts/health` (signed `devops-guru`) | REST JSON | 31 |
| [DocumentDB Elastic Clusters](docdbelastic.md) | `/cluster`, `/cluster-snapshot`, `/pending-action`, `/tags` (signed `docdb-elastic`) + MongoDB container | REST JSON + MongoDB wire | 19 |
| [Directory Service](ds.md) | `POST /` (`X-Amz-Target: DirectoryService_20150416.*`) | JSON 1.1 | 80 |
| [DAX](dax.md) | `POST /` + `X-Amz-Target: AmazonDAXV3.*` | JSON 1.1 | 21 |
| [CodeConnections](codeconnections.md) | `POST /` + `X-Amz-Target: CodeConnections_20231201.*`, or RPC v2 CBOR | JSON 1.0 + CBOR | 27 |
| [Chatbot](chatbot.md) | `POST /<operation-path>` (signed `chatbot`) | REST JSON | 34 |
| [Bedrock Data Automation](bedrock-data-automation.md) | `/blueprints`, `/data-automation-projects`, `/data-automation-libraries`; runtime `X-Amz-Target: AmazonBedrockKeystoneRuntimeService.*` (signed `bedrock`) | REST JSON + JSON 1.1 | 30 |
| [Backup Search](backupsearch.md) | `/search-jobs`, `/export-search-jobs` (signed `backup-search`) | REST JSON | 12 |
| [B2B Data Interchange](b2bi.md) | `POST /` + `X-Amz-Target: B2BI.*` | JSON 1.0 | 30 |
| [Audit Manager](auditmanager.md) | `/assessments`, `/controls`, `/insights`, … (signed `auditmanager`) | REST JSON | 62 |
| [AppRegistry](appregistry.md) | `/applications`, `/attribute-groups` (signed `servicecatalog`) | REST JSON | 24 |
| [AppFlow](appflow.md) | `POST /<operation-name>` | REST JSON | 25 |
| [Amplify Hosting](amplify.md) | `/apps/*`, `/webhooks/*`, `/artifacts/*`; sites by `Host` | REST JSON | 37 |
| [CloudWatch Investigations](aiops.md) | `/investigationGroups` | REST JSON | 11 |
| [Private CA](acmpca.md) | `POST /` + `X-Amz-Target: ACMPrivateCA.*` | JSON 1.1 | 23 |
| [ECR](ecr.md) | `POST /` + `X-Amz-Target: AmazonEC2ContainerRegistry_V20150921.*` (control plane) and `/v2/...` (data plane proxied to `registry:2`) | JSON 1.1 + OCI Distribution | 34 |
| [ECR Public](ecrpublic.md) | `POST /` + `X-Amz-Target: SpencerFrontendService.*` (signed `ecr-public`); images stored in the ECR registry | JSON 1.1 | 23 |
| [EMR on EKS](emrcontainers.md) | `/virtualclusters`, `/jobtemplates`, `/securityconfigurations`, `/tags` (signed `emr-containers`); Spark drivers in Docker in the `docker` runner mode | REST JSON | 25 |
| [Entity Resolution](entityresolution.md) | `/schemas`, `/matchingworkflows`, `/idnamespaces`, `/idmappingworkflows`, `/policies`, `/tags` (signed `entityresolution`); jobs read Glue tables in S3 | REST JSON | 38 |
| [Firewall Manager](fms.md) | `POST /` + `X-Amz-Target: AWSFMS_20180101.*` | JSON 1.1 | 42 |
| [Forecast](forecast.md) | `POST /` + `X-Amz-Target: AmazonForecast.*`, `AmazonForecastRuntime.*` | JSON 1.1 | 65 |
| [Glacier](glacier.md) | `/<account>/vaults/*`, `/<account>/policies/*`, `/<account>/provisioned-capacity` | REST JSON | 33 |
| [Managed Grafana](grafana.md) | `/workspaces/*`, `/tags/*`, `/versions` | REST JSON | 25 |
| [IoT Greengrass V2](greengrassv2.md) | `/greengrass/v2/*`, `/greengrass/things/*`, `/greengrass/servicerole`, `/tags/*` | REST JSON | 29 |
| [Managed integrations](iotmanagedintegrations.md) | `/managed-things`, `/cloud-connectors`, `/schema-versions` and the rest, signed for `iotmanagedintegrations`; connectors are Lambda functions | REST JSON | 83 |
| [IoT Wireless](iotwireless.md) | `/wireless-devices`, `/wireless-gateways`, `/destinations` and the rest, signed for `iotwireless`; a LoRaWAN network server for Basics Station gateways | REST JSON | 112 |
| [IoT SiteWise](iotsitewise.md) | `/asset-models`, `/assets`, `/properties/*`, `/queries/execution`, `/portals` and the rest, signed for `iotsitewise`; computes transforms and metrics, publishes to IoT MQTT | REST JSON | 149 |
| [IoT FleetWise](iotfleetwise.md) | `POST /` + `X-Amz-Target: IoTAutobahnControlPlane.*`; talks to vehicles over IoT MQTT | JSON 1.0 | 57 |
| [HealthLake](healthlake.md) | `POST /` + `X-Amz-Target: HealthLake.*` | JSON 1.0 | 26 |
| [EC2 Image Builder](imagebuilder.md) | `PUT`/`GET`/`POST`/`DELETE /<Operation>`, `/tags/*` signed for `imagebuilder`; AMIs built on Docker-backed instances, containers pushed to ECR | REST JSON | 77 |
| [Internet Monitor](internetmonitor.md) | `/v20210603/Monitors/*`, `/v20210603/InternetEvents/*`, `/tags/*` signed for `internetmonitor`; measures the traffic CloudFront and load balancers serve | REST JSON | 16 |
| [Kendra](kendra.md) | `POST /` + `X-Amz-Target: AWSKendraFrontendService.*`; a search engine over documents, S3, websites and databases | JSON 1.1 | 66 |
| [Keyspaces](keyspaces.md) | `POST /` + `X-Amz-Target: KeyspacesService.*`, `KeyspacesStreams.*` (signed `cassandra`); CQL on port 9142 | JSON 1.0 / CBOR | 23 |
| [Kinesis Video Streams](kinesisvideo.md) | `/createStream`, `/putMedia`, `/getMedia`, `/getClip` and the rest, signed for `kinesisvideo`; HLS/DASH playback and WebRTC signaling | REST JSON | 44 |
| [Lex V2](lexv2.md) | `/bots/*`, `/exports/*`, `/imports/*`, `/testsets/*`, `/tags/*`, `/policy/*` and the session paths, signed for `lex`; conversations with Lambda code hooks, StartConversation over HTTP/2 | REST JSON | 113 |
| [License Manager](licensemanager.md) | `POST /` + `X-Amz-Target: AWSLicenseManager.*`; license configurations with usage and hard limits, seller licenses, grants, tokens and checkouts | JSON 1.1 | 62 |
| [MWAA Serverless](airflowserverless.md) | `POST /` + `X-Amz-Target: AmazonMWAAServerless.*` (signed `airflow-serverless`); workflow runs execute in Apache Airflow | JSON 1.0 | 15 |
| [SES Mail Manager](mailmanager.md) | `POST /` + `X-Amz-Target: MailManagerSvc.*` (signed `ses`); ingress points receive mail over SMTP | JSON 1.0 / CBOR | 60 |
| [MediaConnect](mediaconnect.md) | `/v1/flows`, `/v1/bridges`, `/v1/gateways`, `/v1/routerInput`, `/v1/offerings` (signed `mediaconnect`); active flows relay UDP | REST JSON | 82 |
| [MediaConvert](mediaconvert.md) | `/2017-08-29/jobs`, `/2017-08-29/queues`, `/2017-08-29/presets`, `/2017-08-29/jobTemplates` (signed `mediaconvert`); jobs transcode with ffmpeg | REST JSON | 34 |
| [MediaLive](medialive.md) | `/prod/channels`, `/prod/inputs`, `/prod/multiplexes`, `/prod/signal-maps` (signed `medialive`); running channels encode with ffmpeg | REST JSON | 123 |
| [MediaPackage v2](mediapackagev2.md) | `/channelGroup` (signed `mediapackagev2`); ingest and egress on per-group hosts; CMAF remuxed with ffmpeg | REST JSON | 30 |
| [MediaTailor](mediatailor.md) | `/playbackConfiguration`, `/channel`, `/sourceLocation` (signed `mediatailor`); ads stitched into HLS, running channels | REST JSON | 48 |
| [HealthImaging](medicalimaging.md) | `/datastore`, `/startDICOMImportJob` (signed `medical-imaging`); DICOM imports to HTJ2K image sets | REST JSON | 18 |
| [User Notifications](notifications.md) | `/notification-configurations`, `/event-rules`, `/notification-hubs`, `/notification-events`, `/channels` (signed `notifications`); events from EventBridge emailed to contacts | REST JSON | 40 |
| [User Notifications Contacts](notificationscontacts.md) | `/emailcontacts` (signed `notifications-contacts`); activation codes emailed through SES | REST JSON | 9 |
| [OpenSearch Serverless](opensearchserverless.md) | `POST /` + `X-Amz-Target: OpenSearchServerless.*` (signed `aoss`); collections run on OpenSearch | JSON 1.0 | 54 |
| [Personalize](personalize.md) | `POST /` + `X-Amz-Target: AmazonPersonalize.*`, plus the runtime and events REST APIs (signed `personalize`); trains real models | JSON 1.1 + REST JSON | 79 |
| [SMS and Voice v2](pinpointsmsvoicev2.md) | `POST /` + `X-Amz-Target: PinpointSMSVoiceV2.*` (signed `sms-voice`); events to SNS, Logs and Firehose, inbound keywords | JSON 1.0 | 110 |
| [Q Business](qbusiness.md) | `/applications/...` (signed `qbusiness`); indexes documents, answers chat with citations | REST JSON | 83 |
| [Q Apps](qapps.md) | `/apps.*`, `/runtime.*`, `/catalog.*` (signed `qapps`); apps of cards computed on a Q Business instance | REST JSON | 35 |
| [Recycle Bin](rbin.md) | `/rules`, `/list-rules`, `/tags/...` (signed `rbin`); keeps deleted EC2 snapshots, AMIs and volumes | REST JSON | 10 |
| [re:Post Private](repostspace.md) | `/spaces/...` (signed `repostspace`); spaces on IAM Identity Center | REST JSON | 19 |
| [IAM Roles Anywhere](rolesanywhere.md) | `/trustanchors`, `/profiles`, `/crls`, `POST /sessions` (signed `rolesanywhere`); certificates exchanged for role credentials | REST JSON | 30 |
| [Route 53 Profiles](route53profiles.md) | `/profile`, `/profileassociation`, `/profileresourceassociation` (signed `route53profiles`); profiles apply Resolver rules to their VPCs' DNS | REST JSON | 16 |
| [S3 Files](s3files.md) | `/file-systems`, `/access-points`, `/mount-targets` (signed `s3files`); file systems over versioned buckets, mount targets hold EC2 network interfaces | REST JSON | 21 |
| [Incident Manager](ssm-incidents.md) | `/createReplicationSet`, `/startIncident` and the rest (signed `ssm-incidents`); incidents notify SNS topics and engage contacts | REST JSON | 31 |
| [EventBridge Schemas](schemas.md) | `/v1/registries`, `/v1/discoverers`, `/v1/discover` (signed `schemas`); discoverers infer schemas from bus events, code bindings | REST JSON | 31 |
| [SSM Contacts](ssm-contacts.md) | `POST /` + `X-Amz-Target: SSMContacts.*`; channels activated by code, pages delivered through SES and SNS, rotation shifts | JSON 1.1 | 39 |
| [HealthOmics](omics.md) | `/referencestore`, `/sequencestore`, `/workflow`, `/run`, ... (signed `omics`); runs on Cromwell, Nextflow and cwltool | REST JSON | 107 |
| [CloudWatch Observability Admin](observabilityadmin.md) | `POST /<Operation>` (signed `observabilityadmin`); telemetry rules configure resources, pipelines run processors | REST JSON | 45 |
| [OpenSearch Ingestion](osis.md) | `/2022-01-01/osis/*` (signed `osis`); pipelines run on Data Prepper, ingest on per-pipeline hosts | REST JSON | 22 |
| [Neptune Analytics](neptunegraph.md) | `/graphs`, `/queries`, `/snapshots`, `/importtasks` (signed `neptune-graph`); openCypher on Neo4j | REST JSON | 34 |
| [IVS](ivs.md) | `POST /<Operation>`, `/tags/*` signed for `ivs` (with Real-Time) | REST JSON | 78 |
| [IVS Chat](ivschat.md) | `POST /<Operation>`, `/tags/*` signed for `ivschat` | REST JSON | 17 |
| [FSx](fsx.md) | `POST /` + `X-Amz-Target: AWSSimbaAPIService_v20180301.*` | JSON 1.1 | 48 |
| [FinSpace](finspace.md) (end of support) | requests signed `finspace-api` | REST JSON | 0 |
| [AWS Resource Groups](resource-groups.md) | `/groups`, `/get-group`, `/groups-list`, `/list-group-resources`, `/resources/*` and the other AWS routes | REST JSON | 23 |
| [Resource Groups Tagging API](resource-groups-tagging.md) | `POST /` + `X-Amz-Target: ResourceGroupsTaggingAPI_20170126.*` | JSON 1.1 | 5 |
| [Resource Explorer](resource-explorer.md) | `POST /{OperationName}`, rewritten to `/_re2/*` for the four paths S3 Vectors also claims | REST JSON | 32 |
| [SES](ses.md) | `POST /` with `Action=` param | Query | 71 |
| [SES v2](ses.md#v2) | `/v2/email/*` | REST JSON | 102 |
| [OpenSearch](opensearch.md) | `/2021-01-01/opensearch/...` | REST JSON | 24 |
| [AppConfig](appconfig.md) | `/applications/...`, `/deploymentstrategies/...`, `/extensions/...` | REST JSON | [See actions](appconfig.md) |
| [AppConfigData](appconfig.md#data-plane) | `/configurationsessions`, `/configuration` | REST JSON | [See actions](appconfig.md#data-plane) |
| [AppSync](appsync.md) | `/v1/apis/...` | REST JSON | [See actions](appsync.md) |
| [Amazon Bedrock](bedrock.md) | `/guardrails`, `/guardrails/{guardrailIdentifier}`, `/resource-policy`, `/resource-policy/{resourceArn}`, `/tagResource`, `/untagResource`, `/listTagsForResource` | REST JSON | 12 |
| [Bedrock Runtime](bedrock-runtime.md) | `/model/{modelId}/converse`, `/model/{modelId}/converse-stream`, `/model/{modelId}/invoke`, `/model/{modelId}/invoke-with-response-stream`, `/model/{modelId}/count-tokens` | REST JSON | 5 (stub or proxy) |
| [Agents for Amazon Bedrock](bedrock-agent.md) | `/agents/*`, `/knowledgebases/*`, `/rerank`, `/retrieveAndGenerate*`, `/tags/{resourceArn}` | REST JSON | 49 (+ 3 tagging via shared `/tags/{arn}` route) |
| [Bedrock AgentCore Control](bedrock-agentcore.md) | `/runtimes/*`, `/gateways/*`, `/memories/*`, `/identities/*`, `/browsers*`, `/browser-profiles*`, `/code-interpreters*`, `/harnesses*`, `/resourcepolicy/*`, `/tags/{resourceArn}` | REST JSON | 76 (+ 3 tagging via shared `/tags/{arn}` route) |
| [Bedrock AgentCore](bedrock-agentcore.md#data-plane-invokeagentruntime) | `/runtimes/{agentRuntimeArn}/invocations` | REST JSON (binary payload) | 1 (canned-response stub) |
| [EKS](eks.md) | `/clusters`, `/clusters/{name}`, `/tags/{resourceArn}` | REST JSON | 7 |
| [ELB v2](elb.md) | `POST /` with `Action=` param | Query | 44 |
| [ELB Classic (v1)](elb-classic.md) | `POST /` with `Action=` and `Version=2012-06-01` | Query | 20 |
| [WAF v2](wafv2.md) | `POST /` + `X-Amz-Target: AWSWAF_20190729.*` | JSON 1.1 | 47 |
| [Auto Scaling](autoscaling.md) | `POST /` with `Action=` param | Query | 58 |
| [Application Auto Scaling](applicationautoscaling.md) | `POST /` + `X-Amz-Target: AnyScaleFrontendService.*` | JSON 1.1 | 14 |
| [Elastic Beanstalk](elastic-beanstalk.md) | `POST /` with `Action=` or `Operation=` param | Query | 14 |
| [CodeBuild](codebuild.md) | `POST /` + `X-Amz-Target: CodeBuild_20161006.*` | JSON 1.1 | 44 |
| [AWS Batch](batch.md) | `/v1/...` | REST JSON | [See actions](batch.md) |
| [CodeDeploy](codedeploy.md) | `POST /` + `X-Amz-Target: CodeDeploy_20141006.*` | JSON 1.1 | 39 |
| [CodePipeline](codepipeline.md) | `POST /` + `X-Amz-Target: CodePipeline_20150709.*` | JSON 1.1 | 44 |
| [AWS Network Firewall](network-firewall.md) | `POST /` + `X-Amz-Target: NetworkFirewall_20201112.*` | JSON 1.0 | 40 |
| [AWS Service Catalog](service-catalog.md) | `POST /` + `X-Amz-Target: AWS242ServiceCatalogService.*` | JSON 1.1 | 89 |
| [Service Quotas](servicequotas.md) | `POST /` + `X-Amz-Target: ServiceQuotasV20190624.*` | JSON 1.1 | 9 |
| [AWS Budgets](budgets.md) | `POST /` + `X-Amz-Target: AWSBudgetServiceGateway.*` | JSON 1.1 | 26 |
| [AWS RAM](ram.md) | `POST /{operationname}` (lowercase), `DELETE /deleteresourceshare` | REST JSON | 25 |
| [Control Catalog](controlcatalog.md) | `/get-control`, `/list-controls` | REST JSON | 2 |
| [AWS Marketplace](marketplace.md) | Marketplace Catalog API | REST JSON | 56 |
| [Control Tower](controltower.md) | `/list-landingzones`, `/get-landingzone`, `/create-landingzone`, `/*-baseline*` | REST JSON | 15 |
| [Managed Prometheus (AMP)](managed-prometheus.md) | `/workspaces/*`, `/workspaces/*/rulegroupsnamespaces/*`, `/tags/*` | REST JSON | 13 |
| [AWS Backup](backup.md) | `/backup-vaults/*`, `/logically-air-gapped-backup-vaults/*`, `/backup/plans/*`, `/backup-jobs/*`, `/supported-resource-types` | REST JSON | 35 |
| [AWS FIS](fis.md) | `/experimentTemplates/*`, `/experiments/*`, `/actions/*`, `/targetResourceTypes/*`, `/safetyLevers/*`, `/tags/*` | REST JSON | 26 |
| [CodeGuru Reviewer](codegurureviewer.md) | `/associations`, `/associations/{associationArn}`, `/tags/*` | REST JSON | 7 |
| [CodeArtifact](codeartifact.md) | `/v1/domain*`, `/v1/repository*`, `/v1/package`, `/v1/package/version*`, `/v1/tag*`, `/v1/authorization-token`, `/codeartifact/maven/*`, `/codeartifact/npm/*`, `/codeartifact/pypi/*` | REST JSON | 38 |
| [CloudFront](cloudfront.md) | `/2020-05-31/distribution/*`, `/2020-05-31/cache-policy/*`, `/2020-05-31/function/*` | REST XML | 50 |
| [Route53](route53.md) | `/2013-04-01/hostedzone/*`, `/2013-04-01/healthcheck/*`, `/2013-04-01/change/*`, `/2013-04-01/delegationset/*` | REST XML | 33 |
| [Route 53 Resolver](route53resolver.md) | `POST /` + `X-Amz-Target: Route53Resolver.*` | JSON 1.1 | 30 |
| [Amazon SageMaker](sagemaker.md) | `POST /` + `X-Amz-Target: SageMaker.*` | JSON 1.1 | 43 |
| [SageMaker Runtime](sagemaker.md#endpoint-hosting) | `/endpoints/{EndpointName}/invocations` | REST (binary payload) | 1 |
| [Cloud Map](cloudmap.md) | `POST /` + `X-Amz-Target: Route53AutoNaming_v20170314.*` | JSON 1.1 | 30 |
| [AWS Config](config.md) | `POST /` + `X-Amz-Target: StarlingDoveService.*` | JSON 1.1 | 46 |
| [CloudTrail](cloudtrail.md) | `POST /` + `X-Amz-Target: com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.*` | JSON 1.1 | 32 |
| [Textract](textract.md) | `POST /` + `X-Amz-Target: Textract.*` | JSON 1.1 | 25 |
| [Comprehend](comprehend.md) | `POST /` + `X-Amz-Target: Comprehend_20171127.*` | JSON 1.1 | 53 |
| [Comprehend Medical](comprehendmedical.md) | `POST /` + `X-Amz-Target: ComprehendMedical_20181030.*` | JSON 1.1 | 26 |
| [Location](location.md) | `/maps/v0`, `/places/v0`, `/routes/v0`, `/geofencing/v0`, `/tracking/v0`, `/metadata/v0`, `/v2/*` | REST JSON | 76 |
| [Route 53 Domains](route53domains.md) | `POST /` + `X-Amz-Target: Route53Domains_v20140515.*` | JSON 1.1 | 35 |
| [Polly](polly.md) | `/v1/lexicons`, `/v1/voices`, `/v1/speech`, `/v1/synthesisTasks`, `/v1/synthesisStream` | REST JSON | 10 |
| [Rekognition](rekognition.md) | `POST /` + `X-Amz-Target: RekognitionService.*` | JSON 1.1 | 61 |
| [Translate](translate.md) | `POST /` + `X-Amz-Target: AWSShineFrontendService_20170701.*` | JSON 1.1 | 19 |
| [Transcribe](transcribe.md) | `POST /` + `X-Amz-Target: Transcribe.*` | JSON 1.1 | 43 |
| [Pricing](pricing.md) | `POST /` + `X-Amz-Target: AWSPriceListService.*` | JSON 1.1 | 5 |
| [Cost Explorer](ce.md) | `POST /` + `X-Amz-Target: AWSInsightsIndexService.*` | JSON 1.1 | 47 |
| [Cost and Usage Reports](cur.md) | `POST /` + `X-Amz-Target: AWSOrigamiServiceGatewayService.*` | JSON 1.1 | 6 |
| [BCM Pricing Calculator](bcm-pricing-calculator.md) | `POST /` + `X-Amz-Target: AWSBCMPricingCalculator.*` | JSON 1.0 | 4 |
| [BCM Data Exports](bcm-data-exports.md) | `POST /` + `X-Amz-Target: AWSBillingAndCostManagementDataExports.*` | JSON 1.1 | 7 |
| [Transfer Family](transfer.md) | `POST /` + `X-Amz-Target: TransferService.*` | JSON 1.1 | 17 |
| [DataSync](datasync.md) | `POST /` + `X-Amz-Target: FmrsService.*` | JSON 1.1 | 53 |
| [IoT Core](iot.md) | `/things/...`, `/endpoint`, rules/policies REST paths | REST JSON | 78 |
| [IoT Data](iot.md) | `/things/{thingName}/shadow`, MQTT topics | REST JSON | 11 |

**Lambda, ElastiCache, RDS, MSK, MWAA, ECS, EKS, and OpenSearch** spin up real Docker containers and support IAM authentication and SigV4 request signing, the same auth flow as production AWS. **RDS Data API** executes SQL against the local RDS containers through AWS-compatible REST JSON routes.

**ECR** proxies Docker Distribution traffic to a shared `registry:2` container so the stock `docker` client can push and pull image bytes against repositories returned by the AWS-shaped control plane. **EKS** (real mode) starts a k3s container per cluster and exposes the Kubernetes API server on a host port. **OpenSearch** (real mode) starts an `opensearchproject/opensearch` container per domain and exposes the data-plane REST API on a host port. **DocumentDB** starts a real `mongo` container per cluster and returns its host and port as the cluster endpoint, so any MongoDB driver can connect against the MongoDB-compatible wire protocol.

## Common Setup

Before calling any service, configure your AWS client to point to Floci:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
```

`AWS_ENDPOINT_URL` is the standard env var recognised by the AWS CLI v2 and AWS SDKs v2+, so no `--endpoint-url` flag is needed on each command.

- [SageMaker](sagemaker.md) - model, endpoint, runtime, and training emulation with Docker execution.
