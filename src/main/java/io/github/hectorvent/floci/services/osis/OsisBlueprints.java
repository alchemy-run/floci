package io.github.hectorvent.floci.services.osis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS-provided OpenSearch Ingestion Data Prepper blueprints. Names match
 * {@code aws osis list-pipeline-blueprints} / {@code get-pipeline-blueprint}.
 */
final class OsisBlueprints {

    record Blueprint(
            String blueprintName,
            String displayName,
            String displayDescription,
            String service,
            String useCase,
            String pipelineConfigurationBody) {
    }

    private static final Map<String, Blueprint> BY_NAME = new LinkedHashMap<>();

    static {
        register(apacheLogs());
        register(cloudTrail());
        register(s3Logs());
        register(vpcFlowLogs());
        register(wafLogs());
    }

    private OsisBlueprints() {
    }

    static List<Blueprint> all() {
        return List.copyOf(BY_NAME.values());
    }

    static Blueprint get(String name) {
        return BY_NAME.get(name);
    }

    private static void register(Blueprint blueprint) {
        BY_NAME.put(blueprint.blueprintName(), blueprint);
    }

    private static Blueprint apacheLogs() {
        return new Blueprint(
                "AWS-ApacheLogPipeline",
                "Apache logs",
                "Receive Apache logs over HTTP and write them to OpenSearch.",
                "http",
                "logs",
                """
                        version: "2"
                        apache-log-pipeline:
                          source:
                            http:
                              path: "/logs/ingest"
                          processor:
                            - grok:
                                match:
                                  log: [ "%{COMMONAPACHELOG}" ]
                          sink:
                            - opensearch:
                                hosts: [ "https://search-example.us-east-1.es.amazonaws.com" ]
                                index: "logs"
                                aws:
                                  sts_role_arn: "<sts-role-arn>"
                                  region: "us-east-1"
                        """);
    }

    private static Blueprint cloudTrail() {
        return new Blueprint(
                "AWS-CloudTrailLogsToOpenSearch",
                "CloudTrail logs",
                "Ingest CloudTrail logs from S3 into OpenSearch.",
                "s3",
                "logs",
                """
                        version: "2"
                        cloudtrail-pipeline:
                          source:
                            s3:
                              notification_type: "sqs"
                              compression: "gzip"
                              codec:
                                newline:
                              aws:
                                region: "us-east-1"
                                sts_role_arn: "<sts-role-arn>"
                          sink:
                            - opensearch:
                                hosts: [ "https://search-example.us-east-1.es.amazonaws.com" ]
                                index: "cloudtrail"
                                aws:
                                  sts_role_arn: "<sts-role-arn>"
                                  region: "us-east-1"
                        """);
    }

    private static Blueprint s3Logs() {
        return new Blueprint(
                "AWS-S3LogPipeline",
                "S3 logs",
                "Ingest log files from S3 and write them to OpenSearch.",
                "s3",
                "logs",
                """
                        version: "2"
                        s3-log-pipeline:
                          source:
                            s3:
                              notification_type: "sqs"
                              compression: "none"
                              codec:
                                newline:
                              aws:
                                region: "us-east-1"
                                sts_role_arn: "<sts-role-arn>"
                          sink:
                            - opensearch:
                                hosts: [ "https://search-example.us-east-1.es.amazonaws.com" ]
                                index: "s3-logs"
                                aws:
                                  sts_role_arn: "<sts-role-arn>"
                                  region: "us-east-1"
                        """);
    }

    private static Blueprint vpcFlowLogs() {
        return new Blueprint(
                "AWS-VPCFlowLogsToOpenSearch",
                "VPC flow logs",
                "Ingest VPC flow logs from S3 into OpenSearch.",
                "s3",
                "logs",
                """
                        version: "2"
                        vpc-flow-log-pipeline:
                          source:
                            s3:
                              notification_type: "sqs"
                              compression: "gzip"
                              codec:
                                newline:
                              aws:
                                region: "us-east-1"
                                sts_role_arn: "<sts-role-arn>"
                          sink:
                            - opensearch:
                                hosts: [ "https://search-example.us-east-1.es.amazonaws.com" ]
                                index: "vpc-flow-logs"
                                aws:
                                  sts_role_arn: "<sts-role-arn>"
                                  region: "us-east-1"
                        """);
    }

    private static Blueprint wafLogs() {
        return new Blueprint(
                "AWS-WAFLogsToOpenSearch",
                "WAF logs",
                "Ingest AWS WAF logs from S3 into OpenSearch.",
                "s3",
                "logs",
                """
                        version: "2"
                        waf-log-pipeline:
                          source:
                            s3:
                              notification_type: "sqs"
                              compression: "gzip"
                              codec:
                                json:
                              aws:
                                region: "us-east-1"
                                sts_role_arn: "<sts-role-arn>"
                          sink:
                            - opensearch:
                                hosts: [ "https://search-example.us-east-1.es.amazonaws.com" ]
                                index: "waf-logs"
                                aws:
                                  sts_role_arn: "<sts-role-arn>"
                                  region: "us-east-1"
                        """);
    }
}
