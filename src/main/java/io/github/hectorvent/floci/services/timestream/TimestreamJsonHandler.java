package io.github.hectorvent.floci.services.timestream;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * Timestream for LiveAnalytics ({@code timestream-write} and {@code timestream-query}, JSON 1.0
 * target prefix {@code Timestream_20181101}). AWS has closed the service to new customers: an
 * account that was not onboarded before the closure receives this {@code AccessDeniedException}
 * on every operation, starting with the {@code DescribeEndpoints} discovery call every client
 * makes first. Floci emulates a new account, so it answers every operation the same way.
 */
@ApplicationScoped
public class TimestreamJsonHandler {

    static final String NOT_ONBOARDED_MESSAGE = "Only existing Timestream for LiveAnalytics customers can "
            + "access the service. Reach out to your AWS account team for more information.";

    /** Returns null for an operation neither API defines, which the dispatcher reports as unknown. */
    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            // Shared by timestream-write and timestream-query
            case "DescribeEndpoints",
                 "ListTagsForResource",
                 "TagResource",
                 "UntagResource",
                 // timestream-write
                 "CreateBatchLoadTask",
                 "CreateDatabase",
                 "CreateTable",
                 "DeleteDatabase",
                 "DeleteTable",
                 "DescribeBatchLoadTask",
                 "DescribeDatabase",
                 "DescribeTable",
                 "ListBatchLoadTasks",
                 "ListDatabases",
                 "ListTables",
                 "ResumeBatchLoadTask",
                 "UpdateDatabase",
                 "UpdateTable",
                 "WriteRecords",
                 // timestream-query
                 "CancelQuery",
                 "CreateScheduledQuery",
                 "DeleteScheduledQuery",
                 "DescribeAccountSettings",
                 "DescribeScheduledQuery",
                 "ExecuteScheduledQuery",
                 "ListScheduledQueries",
                 "PrepareQuery",
                 "Query",
                 "UpdateAccountSettings",
                 "UpdateScheduledQuery" -> throw notOnboarded();
            default -> null;
        };
    }

    private static AwsException notOnboarded() {
        return new AwsException("AccessDeniedException", NOT_ONBOARDED_MESSAGE, 403);
    }
}
