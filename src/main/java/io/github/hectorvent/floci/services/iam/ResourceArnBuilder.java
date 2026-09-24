package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Constructs the target resource ARN for a request so the policy evaluator
 * can match it against Resource patterns in policy documents.
 *
 * Returns {@code *} when the resource cannot be determined, which matches
 * permissive wildcard policies.
 */
@ApplicationScoped
public class ResourceArnBuilder {

    private static final Logger LOG = Logger.getLogger(ResourceArnBuilder.class);

    /** SES v2 / v1 From address in a JSON body. */
    private static final Pattern FROM_EMAIL_JSON =
            Pattern.compile("\"FromEmailAddress\"\\s*:\\s*\"([^\"]+)\"");
    private final ObjectMapper objectMapper;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResourceArnBuilder() {
        this(new ObjectMapper());
    }

    public String build(String credentialScope, ContainerRequestContext ctx,
                        String region, String accountId) {
        List<String> list = buildResources(credentialScope, ctx, region, accountId);
        return list.isEmpty() ? "*" : list.getFirst();
    }

    public List<String> buildResources(String credentialScope, ContainerRequestContext ctx,
                                       String region, String accountId) {
        if (credentialScope == null) {
            return List.of("*");
        }
        String path = ctx.getUriInfo().getPath();
        return switch (credentialScope) {
            case "s3"             -> List.of(buildS3Arn(path));
            case "lambda"         -> List.of(buildLambdaArn(path, region, accountId));
            case "sqs"            -> List.of(buildSqsArn(ctx, region, accountId));
            case "sns"            -> List.of(buildSnsArn(ctx, region, accountId));
            case "dynamodb"       -> buildDynamoDbArns(ctx, region, accountId);
            case "kinesis"        -> List.of(buildKinesisArn(ctx, region, accountId));
            case "secretsmanager" -> List.of(buildSecretsManagerArn(ctx, region, accountId));
            case "ssm"            -> List.of(buildSsmArn(ctx, region, accountId));
            case "kms"            -> List.of(buildKmsArn(path, region, accountId));
            case "ses"            -> List.of(buildSesIdentityArn(ctx, region, accountId));
            case "emr-serverless" -> List.of(buildEmrServerlessArn(ctx, path, region, accountId));
            case "guardduty"      -> List.of(buildGuardDutyArn(ctx, path, region, accountId));
            case "inspector2"     -> List.of(buildInspector2Arn(ctx, path, region, accountId));
            default               -> List.of("*");
        };
    }

    public String buildExecutionRoleArn(ContainerRequestContext ctx) {
        JsonNode body = readJsonBody(ctx);
        JsonNode role = body == null ? null : body.get("executionRoleArn");
        return role != null && role.isTextual() && !role.textValue().isBlank()
                ? role.textValue() : null;
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────
    private String buildS3Arn(String path) {
        // path: /bucket or /bucket/key
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return AwsArnUtils.Arn.of("s3", "", "", "*").toString();
        }
        // S3VirtualHostFilter rewrites a bucket-level virtual-hosted request (GET /) to
        // /bucket/, and the empty key after that separator is not part of the resource.
        // A policy names the bucket as arn:aws:s3:::bucket, so the trailing slash has to
        // go or a bucket-level ARN never matches. A key that itself ends in a slash
        // (a folder marker such as folder/) keeps it, because there the slash is key data.
        int firstSlash = stripped.indexOf('/');
        if (firstSlash == stripped.length() - 1) {
            stripped = stripped.substring(0, firstSlash);
        }
        return AwsArnUtils.Arn.of("s3", "", "", stripped).toString();
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────
    private String buildLambdaArn(String path, String region, String accountId) {
        // path: /2015-03-31/functions/name or similar
        String name = extractSegmentAfter(path, "functions");
        if (name == null) return "*";
        // strip qualifier if present
        int colon = name.indexOf(':');
        if (colon > 0) name = name.substring(0, colon);
        return AwsArnUtils.Arn.of("lambda", region, accountId, "function:" + name).toString();
    }

    private String buildGuardDutyArn(ContainerRequestContext ctx, String path, String region, String accountId) {
        path = path.startsWith("/") ? path : "/" + path;
        if (path.startsWith("/tags/")) {
            return path.substring("/tags/".length());
        }
        String detector = extractSegmentAfter(path, "detector");
        if (detector == null) {
            return "*";
        }
        String resource = "detector/" + detector;
        for (String kind : List.of("filter", "ipset", "threatintelset")) {
            String id = extractSegmentAfter(path, kind);
            if (id != null) {
                return AwsArnUtils.Arn.of("guardduty", region, accountId, resource + "/" + kind + "/" + id).toString();
            }
            if (path.endsWith("/" + kind)) {
                if ("filter".equals(kind) && "POST".equals(ctx.getMethod())) {
                    JsonNode body = readJsonBody(ctx);
                    JsonNode name = body == null ? null : body.get("name");
                    if (name != null && name.isTextual() && !name.asText().isBlank()) {
                        return AwsArnUtils.Arn.of("guardduty", region, accountId,
                                resource + "/filter/" + name.asText()).toString();
                    }
                }
                // Set creation and collection listing do not support resource-level permissions.
                return "*";
            }
        }
        return AwsArnUtils.Arn.of("guardduty", region, accountId, resource).toString();
    }

    private String buildInspector2Arn(ContainerRequestContext ctx, String path, String region, String accountId) {
        path = path.startsWith("/") ? path : "/" + path;
        if (path.startsWith("/tags/")) {
            return path.substring("/tags/".length());
        }
        if ("/filters/create".equals(path)) {
            return AwsArnUtils.Arn.of("inspector2", region, accountId,
                    "owner/" + accountId + "/filter/*").toString();
        }
        if ("/filters/update".equals(path) || "/filters/delete".equals(path)) {
            JsonNode body = readJsonBody(ctx);
            JsonNode arn = body == null ? null : body.get("filterArn");
            if (arn != null && arn.isTextual() && !arn.asText().isBlank()) {
                return arn.asText();
            }
        }
        return "*";
    }

    private String buildEmrServerlessArn(ContainerRequestContext ctx, String path, String region, String accountId) {
        path = path.startsWith("/") ? path : "/" + path;
        int tags = path.indexOf("/tags/");
        if (tags >= 0) {
            return path.substring(tags + "/tags/".length());
        }
        String applicationId = extractSegmentAfter(path, "applications");
        if (applicationId == null) {
            return "*";
        }
        String resource = "/applications/" + applicationId;
        String jobRunId = extractSegmentAfter(path, "jobruns");
        String sessionId = extractSegmentAfter(path, "sessions");
        if (jobRunId == null && sessionId == null && path.endsWith("/dashboard")) {
            sessionId = ctx.getUriInfo().getQueryParameters().getFirst("resourceId");
        }
        if (jobRunId != null) {
            resource += "/jobruns/" + jobRunId;
        } else if (sessionId != null) {
            resource += "/sessions/" + sessionId;
        }
        return AwsArnUtils.Arn.of("emr-serverless", region, accountId, resource).toString();
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────
    private String buildSqsArn(ContainerRequestContext ctx, String region, String accountId) {
        String queueUrl = ctx.getUriInfo().getQueryParameters().getFirst("QueueUrl");
        if (queueUrl == null) {
            // Try form param for Query-protocol
            queueUrl = firstFormParam(ctx, "QueueUrl");
        }
        if (queueUrl == null) {
            JsonNode json = readJsonBody(ctx);
            if (json != null && json.isObject()) {
                if (json.hasNonNull("QueueUrl")) {
                    queueUrl = json.get("QueueUrl").asText().trim();
                } else if (json.hasNonNull("QueueName")) {
                    String queueName = json.get("QueueName").asText().trim();
                    if (!queueName.isEmpty()) {
                        return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
                    }
                }
            }
        }
        if (queueUrl != null && !queueUrl.isEmpty()) {
            String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
            return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
        }
        return AwsArnUtils.Arn.of("sqs", region, accountId, "*").toString();
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────
    private String buildSnsArn(ContainerRequestContext ctx, String region, String accountId) {
        String topicArn = firstFormParam(ctx, "TopicArn");
        if (topicArn == null) {
            JsonNode json = readJsonBody(ctx);
            if (json != null && json.isObject() && json.hasNonNull("TopicArn")) {
                topicArn = json.get("TopicArn").asText().trim();
            }
        }
        return (topicArn != null && !topicArn.isEmpty())
                ? topicArn
                : AwsArnUtils.Arn.of("sns", region, accountId, "*").toString();
    }

    // ── DynamoDB ─────────────────────────────────────────────────────────────────
    private String buildDynamoDbArn(ContainerRequestContext ctx, String region, String accountId) {
        List<String> arns = buildDynamoDbArns(ctx, region, accountId);
        return arns.isEmpty() ? "*" : arns.getFirst();
    }

    private List<String> buildDynamoDbArns(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("TableName")) {
                String tableName = json.get("TableName").asText().trim();
                if (!tableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(tableName, region, accountId));
                }
            }
            if (json.path("TableCreationParameters").hasNonNull("TableName")) {
                var tableName = json.path("TableCreationParameters").get("TableName").asText().trim();
                if (!tableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(tableName, region, accountId));
                }
            }
            if (json.hasNonNull("ResourceArn")) {
                String resourceArn = json.get("ResourceArn").asText().trim();
                if (!resourceArn.isEmpty()) {
                    return List.of(resourceArn);
                }
            }
            if (json.hasNonNull("TableArn")) {
                String tableArn = json.get("TableArn").asText().trim();
                if (!tableArn.isEmpty()) {
                    return List.of(tableArn);
                }
            }
            if (json.hasNonNull("StreamArn")) {
                String streamArn = json.get("StreamArn").asText().trim();
                if (!streamArn.isEmpty()) {
                    return List.of(streamArn);
                }
            }
            if (json.hasNonNull("ExportArn")) {
                String exportArn = json.get("ExportArn").asText().trim();
                if (!exportArn.isEmpty()) {
                    return List.of(exportArn);
                }
            }
            if (json.hasNonNull("ImportArn")) {
                var importArn = json.get("ImportArn").asText().trim();
                if (!importArn.isEmpty()) {
                    return List.of(importArn);
                }
            }
            if (json.hasNonNull("RequestItems") && json.get("RequestItems").isObject()) {
                Set<String> arns = new LinkedHashSet<>();
                var fieldNames = json.get("RequestItems").fieldNames();
                while (fieldNames.hasNext()) {
                    String table = fieldNames.next().trim();
                    if (!table.isEmpty()) {
                        arns.add(toDynamoDbTableArn(table, region, accountId));
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("TransactItems") && json.get("TransactItems").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                JsonNode items = json.get("TransactItems");
                for (JsonNode item : items) {
                    String t = null;
                    if (item.hasNonNull("Put") && item.get("Put").hasNonNull("TableName")) {
                        t = item.get("Put").get("TableName").asText();
                    } else if (item.hasNonNull("Delete") && item.get("Delete").hasNonNull("TableName")) {
                        t = item.get("Delete").get("TableName").asText();
                    } else if (item.hasNonNull("Update") && item.get("Update").hasNonNull("TableName")) {
                        t = item.get("Update").get("TableName").asText();
                    } else if (item.hasNonNull("ConditionCheck") && item.get("ConditionCheck").hasNonNull("TableName")) {
                        t = item.get("ConditionCheck").get("TableName").asText();
                    } else if (item.hasNonNull("Get") && item.get("Get").hasNonNull("TableName")) {
                        t = item.get("Get").get("TableName").asText();
                    }
                    if (t != null) {
                        t = t.trim();
                        if (!t.isEmpty()) {
                            arns.add(toDynamoDbTableArn(t, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("Statement")) {
                String stmt = json.get("Statement").asText().trim();
                String table = extractDynamoDbTableFromPartiQL(stmt);
                if (table != null && !table.isEmpty()) {
                    return List.of(toDynamoDbTableArn(table, region, accountId));
                }
            }
            if (json.hasNonNull("Statements") && json.get("Statements").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                for (JsonNode s : json.get("Statements")) {
                    if (s.hasNonNull("Statement")) {
                        String table = extractDynamoDbTableFromPartiQL(s.get("Statement").asText());
                        if (table != null && !table.isEmpty()) {
                            arns.add(toDynamoDbTableArn(table, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("TransactStatements") && json.get("TransactStatements").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                for (JsonNode s : json.get("TransactStatements")) {
                    if (s.hasNonNull("Statement")) {
                        String table = extractDynamoDbTableFromPartiQL(s.get("Statement").asText());
                        if (table != null && !table.isEmpty()) {
                            arns.add(toDynamoDbTableArn(table, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
        }
        return List.of("*");
    }

    private static String extractDynamoDbTableFromPartiQL(String statement) {
        return io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.extractTable(statement);
    }

    private String toDynamoDbTableArn(String tableName, String region, String accountId) {
        if (AwsArnUtils.isArnFor(tableName, "dynamodb")) {
            return tableName;
        }
        return AwsArnUtils.Arn.of("dynamodb", region, accountId, "table/" + tableName).toString();
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────
    private String buildKinesisArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("StreamARN")) {
                String streamArn = json.get("StreamARN").asText().trim();
                if (!streamArn.isEmpty()) {
                    return streamArn;
                }
            }
            if (json.hasNonNull("StreamName")) {
                String streamName = json.get("StreamName").asText().trim();
                if (!streamName.isEmpty()) {
                    if (AwsArnUtils.isArnFor(streamName, "kinesis")) {
                        return streamName;
                    }
                    return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/" + streamName).toString();
                }
            }
            if (json.hasNonNull("ResourceARN")) {
                String resourceArn = json.get("ResourceARN").asText().trim();
                if (!resourceArn.isEmpty()) {
                    return resourceArn;
                }
            }
        }
        return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/*").toString();
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────
    private String buildSecretsManagerArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("SecretId")) {
                String secretId = json.get("SecretId").asText().trim();
                if (!secretId.isEmpty()) {
                    if (AwsArnUtils.isArnFor(secretId, "secretsmanager")) {
                        return secretId;
                    }
                    return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:" + secretId).toString();
                }
            }
        }
        return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:*").toString();
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────
    private String buildSsmArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("Name")) {
                String name = json.get("Name").asText().trim();
                if (!name.isEmpty()) {
                    if (AwsArnUtils.isArnFor(name, "ssm")) {
                        return name;
                    }
                    String paramResource = name.startsWith("/") ? "parameter" + name : "parameter/" + name;
                    return AwsArnUtils.Arn.of("ssm", region, accountId, paramResource).toString();
                }
            }
        }
        return AwsArnUtils.Arn.of("ssm", region, accountId, "parameter/*").toString();
    }

    // ── KMS ──────────────────────────────────────────────────────────────────────
    private String buildKmsArn(String path, String region, String accountId) {
        String keyId = extractSegmentAfter(path, "keys");
        if (keyId == null) return AwsArnUtils.Arn.of("kms", region, accountId, "key/*").toString();
        return AwsArnUtils.Arn.of("kms", region, accountId, "key/" + keyId).toString();
    }

    // ── SES ──────────────────────────────────────────────────────────────────────
    /**
     * SES authorizes send against the identity of the From address
     * ({@code arn:aws:ses:region:account:identity/user@domain}), not the domain
     * identity the binding was declared on. Domain-scoped policies therefore
     * also grant {@code identity/*@domain}.
     */
    private String buildSesIdentityArn(ContainerRequestContext ctx, String region, String accountId) {
        String from = firstFormParam(ctx, "FromEmailAddress");
        if (from == null || from.isBlank()) {
            from = firstFormParam(ctx, "Source");
        }
        if (from == null || from.isBlank()) {
            from = extractFromEmailAddressFromBody(ctx);
        }
        from = extractEmail(from);
        if (from == null || from.isBlank()) {
            return AwsArnUtils.Arn.of("ses", region, accountId, "identity/*").toString();
        }
        return AwsArnUtils.Arn.of("ses", region, accountId, "identity/" + from).toString();
    }

    private String extractFromEmailAddressFromBody(ContainerRequestContext ctx) {
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            LOG.debugv(e, "Failed to buffer SES body for IAM resource ARN");
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        if (body.length == 0) {
            return null;
        }
        Matcher m = FROM_EMAIL_JSON.matcher(new String(body, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : null;
    }

    /** Strips a display-name wrapper: {@code "Ada" <ada@example.com>} → {@code ada@example.com}. */
    static String extractEmail(String from) {
        if (from == null || from.isBlank()) {
            return from;
        }
        String trimmed = from.trim();
        int lt = trimmed.lastIndexOf('<');
        int gt = trimmed.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return trimmed.substring(lt + 1, gt).trim();
        }
        return trimmed;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private JsonNode readJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty("floci.bufferedJsonBody");
        if (cached instanceof JsonNode node) {
            return node;
        }
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            ctx.setEntityStream(new ByteArrayInputStream(new byte[0]));
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            ctx.setProperty("floci.bufferedJsonBody", node);
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSegmentAfter(String path, String segment) {
        String marker = "/" + segment + "/";
        int idx = path.indexOf(marker);
        if (idx < 0) return null;
        String after = path.substring(idx + marker.length());
        // take only the first segment (stop at next /)
        int slash = after.indexOf('/');
        return slash > 0 ? after.substring(0, slash) : after;
    }

    private String firstFormParam(ContainerRequestContext ctx, String name) {
        // Form params are typically available as query params in REST-Assured / JAX-RS
        String v = ctx.getUriInfo().getQueryParameters().getFirst(name);
        return v;
    }
}
