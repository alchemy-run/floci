package io.floci.aws.services.rds.proxy;

/** The published identity used when validating a MySQL RDS IAM token. */
public record RdsMysqlBinding(String advertisedHost, int publishedPort, String region) {
}
