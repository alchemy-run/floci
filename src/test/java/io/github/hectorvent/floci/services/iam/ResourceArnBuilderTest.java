package io.github.hectorvent.floci.services.iam;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceArnBuilderTest {

    @Test
    void emrServerlessScopesApplicationsJobRunsAndSessions() {
        String[][] routes = {
                {"applications/app", "applications/app"},
                {"applications/app/start", "applications/app"},
                {"applications/app/jobruns", "applications/app"},
                {"applications/app/jobruns/run", "applications/app/jobruns/run"},
                {"applications/app/jobruns/run/attempts", "applications/app/jobruns/run"},
                {"applications/app/sessions", "applications/app"},
                {"applications/app/sessions/session/endpoint", "applications/app/sessions/session"}
        };
        for (String[] route : routes) {
            for (String prefix : new String[]{"", "/", "/_emrserverless/"}) {
                assertEquals("arn:aws:emr-serverless:eu-west-1:123456789012:/" + route[1],
                        builder.build("emr-serverless", mockJsonCtx(prefix + route[0], "{}"),
                                "eu-west-1", "123456789012"));
            }
        }
        assertEquals("*", builder.build("emr-serverless", mockJsonCtx("/applications", "{}"),
                "eu-west-1", "123456789012"));
        String arn = "arn:aws:emr-serverless:eu-west-1:123456789012:/applications/app";
        assertEquals(arn, builder.build("emr-serverless", mockJsonCtx("/tags/" + arn, "{}"),
                "eu-west-1", "123456789012"));
    }

    @Test
    void guardDutyScopesDetectorChildrenAndCreateFilterNames() {
        for (String kind : List.of("filter", "ipset", "threatintelset")) {
            String resource = "detector/d/" + kind + "/n";
            assertEquals("arn:aws:guardduty:eu-west-1:123456789012:" + resource,
                    builder.build("guardduty", mockJsonCtx("/" + resource, "{}"),
                            "eu-west-1", "123456789012"));
            assertEquals("*", builder.build("guardduty", mockJsonCtx("/detector/d/" + kind, "{}"),
                    "eu-west-1", "123456789012"));
        }
        ContainerRequestContext create = mockJsonCtx("/detector/d/filter", "{\"name\":\"named\"}");
        when(create.getMethod()).thenReturn("POST");
        assertEquals("arn:aws:guardduty:eu-west-1:123456789012:detector/d/filter/named",
                builder.build("guardduty", create, "eu-west-1", "123456789012"));
        assertEquals("arn:aws:guardduty:eu-west-1:123456789012:detector/d",
                builder.build("guardduty", mockJsonCtx("/detector/d/findings/get", "{}"),
                        "eu-west-1", "123456789012"));
        assertEquals("*", builder.build("guardduty", mockJsonCtx("/invitation", "{}"),
                "eu-west-1", "123456789012"));
    }

    @Test
    void inspector2ScopesMutationsButNotListFilters() {
        String arn = "arn:aws:inspector2:eu-west-1:123456789012:owner/123456789012/filter/f";
        for (String operation : List.of("update", "delete")) {
            assertEquals(arn, builder.build("inspector2", mockJsonCtx("/filters/" + operation,
                    "{\"filterArn\":\"" + arn + "\"}"), "eu-west-1", "123456789012"));
        }
        assertEquals("arn:aws:inspector2:eu-west-1:123456789012:owner/123456789012/filter/*",
                builder.build("inspector2", mockJsonCtx("/filters/create", "{}"), "eu-west-1", "123456789012"));
        assertEquals("*", builder.build("inspector2", mockJsonCtx("/filters/list",
                "{\"arns\":[\"" + arn + "\"]}"), "eu-west-1", "123456789012"));
        for (String accountLevel : List.of("/findings/list", "/coverage/list", "/members/list",
                "/delegatedadminaccounts/get", "/reporting/status/get")) {
            assertEquals("*", builder.build("inspector2", mockJsonCtx(accountLevel, "{}"),
                    "eu-west-1", "123456789012"));
        }
        assertEquals(arn, builder.build("inspector2", mockJsonCtx("/tags/" + arn, "{}"),
                "eu-west-1", "123456789012"));
        String detectorArn = "arn:aws:guardduty:eu-west-1:123456789012:detector/d/filter/f";
        assertEquals(detectorArn, builder.build("guardduty", mockJsonCtx("/tags/" + detectorArn, "{}"),
                "eu-west-1", "123456789012"));
    }

    @Test
    void emrServerlessDashboardScopesTheRequestedSession() {
        ContainerRequestContext request = mockJsonCtx("/applications/app/dashboard", "{}");
        request.getUriInfo().getQueryParameters().putSingle("resourceId", "session");
        assertEquals("arn:aws:emr-serverless:eu-west-1:123456789012:/applications/app/sessions/session",
                builder.build("emr-serverless", request, "eu-west-1", "123456789012"));
    }

    @Test
    void executionRoleArnReadPreservesTheRequestBody() throws Exception {
        String role = "arn:aws:iam::123456789012:role/JobRole";
        String body = "{\"executionRoleArn\":\"" + role + "\",\"jobDriver\":{}}";
        AtomicReference<InputStream> stream = new AtomicReference<>(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(role, builder.buildExecutionRoleArn(mockJsonCtx("/applications/app/jobruns", stream)));
        assertEquals(body, new String(stream.get().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void sesIdentityArnFromJsonFromEmailAddress() {
        String body = "{\"FromEmailAddress\":\"noreply@bound.example.com\",\"Content\":{}}";
        ContainerRequestContext ctx = mockJsonCtx("/v2/email/outbound-emails", body);

        String arn = builder.build("ses", ctx, "us-east-1", "000000000000");

        assertEquals("arn:aws:ses:us-east-1:000000000000:identity/noreply@bound.example.com", arn);
    }

    @Test
    void sesIdentityArnStripsDisplayName() {
        String body = "{\"FromEmailAddress\":\"Ada <ada@bound.example.com>\"}";
        ContainerRequestContext ctx = mockJsonCtx("/v2/email/outbound-emails", body);

        String arn = builder.build("ses", ctx, "us-east-1", "000000000000");

        assertEquals("arn:aws:ses:us-east-1:000000000000:identity/ada@bound.example.com", arn);
    }

    @Test
    void sesIdentityArnRestoresEntityStream() throws Exception {
        String body = "{\"FromEmailAddress\":\"a@b.test\"}";
        AtomicReference<InputStream> stream = new AtomicReference<>(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        ContainerRequestContext ctx = mockJsonCtx("/v2/email/outbound-emails", stream);

        builder.build("ses", ctx, "us-east-1", "000000000000");

        assertEquals(body, new String(stream.get().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void extractEmailUnwrapsAngleBrackets() {
        assertEquals("ada@example.com", ResourceArnBuilder.extractEmail("Ada Lovelace <ada@example.com>"));
        assertEquals("ada@example.com", ResourceArnBuilder.extractEmail("ada@example.com"));
    }

    private static ContainerRequestContext mockJsonCtx(String path, String body) {
        return mockJsonCtx(path, new AtomicReference<>(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    }

    private static ContainerRequestContext mockJsonCtx(String path, AtomicReference<InputStream> stream) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getEntityStream()).thenAnswer(inv -> stream.get());
        doAnswer(inv -> {
            stream.set(inv.getArgument(0));
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
        return ctx;
    }

    private ResourceArnBuilder builder;
    private ContainerRequestContext ctx;
    private UriInfo uriInfo;
    private MultivaluedMap<String, String> queryParams;
    private Map<String, Object> contextProperties;

    @BeforeEach
    void setUp() {
        builder = new ResourceArnBuilder(new ObjectMapper());
        ctx = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        queryParams = new MultivaluedHashMap<>();
        contextProperties = new HashMap<>();

        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);

        when(ctx.getProperty(any())).thenAnswer(inv -> contextProperties.get(inv.getArgument(0)));
        doAnswer(inv -> {
            contextProperties.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setProperty(any(), any());
    }

    private void setJsonBody(String json) {
        contextProperties.clear();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        when(ctx.getEntityStream()).thenReturn(in);
        doAnswer(inv -> {
            InputStream newIn = inv.getArgument(0);
            when(ctx.getEntityStream()).thenReturn(newIn);
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
    }

    // ── DynamoDB ─────────────────────────────────────────────────────────────────

    @Test
    void dynamoDbBuildsArnFromShortTableName() {
        setJsonBody("{\"TableName\":\"FgacTable\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable", arn);
    }

    /**
     * Pass-through used to be a {@code startsWith("arn:aws:dynamodb:")} probe, so a table ARN from
     * any other partition was not recognised as an ARN at all and got rebuilt as
     * {@code table/arn:aws-cn:dynamodb:...}, a resource name that matches no policy.
     */
    @Test
    void dynamoDbReturnsExactArnForAnyPartition() {
        for (String fullArn : List.of(
                "arn:aws-us-gov:dynamodb:us-gov-west-1:000000000000:table/FgacTable",
                "arn:aws-cn:dynamodb:cn-north-1:000000000000:table/FgacTable")) {
            setJsonBody("{\"TableName\":\"" + fullArn + "\"}");
            assertEquals(fullArn, builder.build("dynamodb", ctx, "us-east-1", "000000000000"));
        }
    }

    /** A bare name that merely looks ARN-ish is still a name, not an ARN. */
    @Test
    void dynamoDbTreatsAnIncompleteArnAsATableName() {
        setJsonBody("{\"TableName\":\"arn:aws:dynamodb:us-east-1\"}");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/arn:aws:dynamodb:us-east-1",
                builder.build("dynamodb", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void dynamoDbReturnsExactArnIfTableNameIsAlreadyArn() {
        String fullArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        setJsonBody("{\"TableName\":\"" + fullArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(fullArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromResourceArn() {
        String tagArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        setJsonBody("{\"ResourceArn\":\"" + tagArn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(tagArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromTableArn() {
        String exportArn = "arn:aws:dynamodb:us-east-1:000000000000:table/ExportTable";
        setJsonBody("{\"TableArn\":\"" + exportArn + "\",\"S3Bucket\":\"my-bucket\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(exportArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromStreamArn() {
        String streamArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable/stream/2026-09-01T00:00:00.000";
        setJsonBody("{\"StreamArn\":\"" + streamArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(streamArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromExportArn() {
        String exportArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable/export/01693526400000-abcd";
        setJsonBody("{\"ExportArn\":\"" + exportArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(exportArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromSingleTableBatchRequest() {
        setJsonBody("{\"RequestItems\":{\"MyTable\":{\"Keys\":[]}}}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/MyTable", arn);
    }

    @Test
    void dynamoDbBuildsArnsFromMultiTableBatchRequest() {
        setJsonBody("{\"RequestItems\":{\"TableA\":{\"Keys\":[]},\"TableB\":{\"Keys\":[]}}}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromSingleTableTransactRequest() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"MyTable\"}},{\"Delete\":{\"TableName\":\"MyTable\"}}]}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/MyTable", arn);
    }

    @Test
    void dynamoDbBuildsArnsFromMultiTableTransactRequest() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"TableA\"}},{\"Delete\":{\"TableName\":\"TableB\"}},{\"Get\":{\"TableName\":\"TableA\"}}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromPartiQLStatement() {
        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"UsersTable\\\" WHERE id = '1'\"}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/UsersTable"), arns);

        setJsonBody("{\"Statement\":\"INSERT INTO OrdersTable VALUE {'id': '1'}\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/OrdersTable"), arns);

        setJsonBody("{\"Statement\":\"UPDATE \\\"ProductsTable\\\" SET price=10 WHERE id='1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/ProductsTable"), arns);

        setJsonBody("{\"Statement\":\"DELETE FROM \\\"LogsTable\\\" WHERE id='1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/LogsTable"), arns);

        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"Table With Spaces & Special:Chars\\\" WHERE id = '1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Table With Spaces & Special:Chars"), arns);

        setJsonBody("{\"Statement\":\"SELECT * FROM 'Table.With.Single.Quotes#1' WHERE id = '1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Table.With.Single.Quotes#1"), arns);

        setJsonBody("{\"Statement\":\"SELECT \\\"FROM TableEvil\\\" FROM \\\"TableA\\\" WHERE pk = 1\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/TableA"), arns);
    }

    @Test
    void dynamoDbBuildsArnsFromPartiQLBatchStatements() {
        setJsonBody("{\"Statements\":[{\"Statement\":\"SELECT * FROM \\\"TableA\\\" WHERE id = '1'\"},{\"Statement\":\"SELECT * FROM TableB WHERE id = '2'\"}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnsFromPartiQLTransactStatements() {
        setJsonBody("{\"TransactStatements\":[{\"Statement\":\"INSERT INTO \\\"TableA\\\" VALUE {'id': '1'}\"},{\"Statement\":\"UPDATE \\\"TableB\\\" SET x=1 WHERE id='2'\"}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromTableCreationParameters() {
        setJsonBody("{\"TableCreationParameters\":{\"TableName\":\"ImportTarget\"},\"InputFormat\":\"DYNAMODB_JSON\"}");
        var arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/ImportTarget", arn);
    }

    @Test
    void dynamoDbReturnsWildcardWhenNoTableSpecified() {
        setJsonBody("{}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("*", arn);
    }

    @Test
    void entityStreamRemainsReadableAfterResourceArnBuilding() throws IOException {
        String json = "{\"TableName\":\"FgacTable\",\"Item\":{\"PK\":{\"S\":\"USER_1\"}}}";
        setJsonBody(json);

        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable", arn);

        InputStream entityStream = ctx.getEntityStream();
        assertNotNull(entityStream);
        byte[] readBack = entityStream.readAllBytes();
        assertEquals(json, new String(readBack, StandardCharsets.UTF_8));
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────

    @Test
    void kinesisBuildsArnFromStreamName() {
        setJsonBody("{\"StreamName\":\"order-events\"}");
        String arn = builder.build("kinesis", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:kinesis:us-east-1:000000000000:stream/order-events", arn);
    }

    @Test
    void kinesisBuildsArnFromStreamArn() {
        String fullArn = "arn:aws:kinesis:us-east-1:000000000000:stream/order-events";
        setJsonBody("{\"StreamARN\":\"" + fullArn + "\"}");
        String arn = builder.build("kinesis", ctx, "us-east-1", "000000000000");
        assertEquals(fullArn, arn);
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────

    @Test
    void secretsManagerBuildsArnFromSecretId() {
        setJsonBody("{\"SecretId\":\"app/prod/database\"}");
        String arn = builder.build("secretsmanager", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/prod/database", arn);
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────

    @Test
    void ssmBuildsArnFromNameWithLeadingSlash() {
        setJsonBody("{\"Name\":\"/config/env\"}");
        String arn = builder.build("ssm", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/config/env", arn);
    }

    @Test
    void ssmBuildsArnFromNameWithoutLeadingSlash() {
        setJsonBody("{\"Name\":\"config-key\"}");
        String arn = builder.build("ssm", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/config-key", arn);
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────

    @Test
    void sqsBuildsArnFromJsonQueueName() {
        setJsonBody("{\"QueueName\":\"order-queue\"}");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:order-queue", arn);
    }

    @Test
    void sqsBuildsArnFromQueryParam() {
        queryParams.putSingle("QueueUrl", "http://localhost:4566/000000000000/my-queue");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:my-queue", arn);
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────

    @Test
    void snsBuildsArnFromJsonTopicArn() {
        String topicArn = "arn:aws:sns:us-east-1:000000000000:alerts";
        setJsonBody("{\"TopicArn\":\"" + topicArn + "\"}");
        String arn = builder.build("sns", ctx, "us-east-1", "000000000000");
        assertEquals(topicArn, arn);
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────

    @Test
    void s3BuildsBucketArn() {
        when(uriInfo.getPath()).thenReturn("/my-bucket");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket", arn);
    }

    @Test
    void s3BuildsObjectArn() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/folder/file.json");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket/folder/file.json", arn);
    }

    /**
     * S3VirtualHostFilter rewrites a virtual-hosted bucket-level request (GET /) to
     * /bucket/. The resource is still the bucket, so the ARN must carry no trailing
     * slash or a policy naming arn:aws:s3:::bucket stops matching and an allowed
     * ListBucket is denied for every SDK that defaults to virtual-hosted addressing.
     */
    @Test
    void s3BuildsBucketArnForVirtualHostedRewrittenPath() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket", arn);
    }

    @Test
    void s3KeepsTrailingSlashOfAFolderMarkerKey() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/folder/");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket/folder/", arn);
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────

    @Test
    void lambdaBuildsFunctionArn() {
        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/my-function/invocations");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function", arn);
    }
}
