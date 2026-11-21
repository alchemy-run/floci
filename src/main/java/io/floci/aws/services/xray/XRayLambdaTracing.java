package io.floci.aws.services.xray;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.lambda.LambdaTracing;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Traces Lambda invocations as X-Ray does for functions with active tracing: X-Ray's sampling rules decide which
 * invocations are traced, and each traced invocation records the Lambda service's segment ({@code AWS::Lambda}) and
 * the function's ({@code AWS::Lambda::Function}), with its initialization, invocation and overhead subsegments. A
 * function error faults the function segment with the error as its cause. The runtime receives the trace header, so
 * the subsegments a function's X-Ray SDK sends through the daemon join the same trace.
 */
@ApplicationScoped
public class XRayLambdaTracing implements LambdaTracing {

    private static final Logger LOG = Logger.getLogger(XRayLambdaTracing.class);

    private final XRayService xray;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public XRayLambdaTracing(XRayService xray, ObjectMapper mapper, EmulatorConfig config) {
        this.xray = xray;
        this.mapper = mapper;
        this.config = config;
    }

    @Override
    public Trace begin(LambdaFunction fn) {
        if (!config.services().xray().enabled() || !"Active".equals(fn.getTracingMode())) {
            return null;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(fn.getFunctionArn());
        boolean sampled = RequestScopes.callAs(arn.accountId(), arn.region(), () -> xray.sample(arn.region(),
                fn.getFunctionName(), "AWS::Lambda::Function", "*", "*", "*"));
        return new Trace(XRayService.newTraceId(), XRayService.randomHex(16), XRayService.randomHex(16), sampled);
    }

    @Override
    public void end(Trace trace, LambdaFunction fn, long startMillis, long initEndMillis, long endMillis,
                    boolean coldStart, InvokeResult result) {
        if (!trace.sampled()) {
            return;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(fn.getFunctionArn());
        double start = startMillis / 1000.0;
        double initEnd = Math.max(start, initEndMillis / 1000.0);
        double end = Math.max(initEnd, endMillis / 1000.0);
        String functionError = result == null ? null : result.getFunctionError();

        ObjectNode service = segment(trace, trace.serviceSegmentId(), fn, "AWS::Lambda", start, end, arn);
        service.putObject("http").putObject("response").put("status", result == null ? 500 : result.getStatusCode());
        if (result != null && result.getStatusCode() >= 500) {
            service.put("fault", true);
        }

        ObjectNode function = segment(trace, trace.functionSegmentId(), fn, "AWS::Lambda::Function", initEnd, end,
                arn);
        function.put("parent_id", trace.serviceSegmentId());
        ArrayNode subsegments = function.putArray("subsegments");
        if (coldStart) {
            function.put("start_time", start);
            subsegments.addObject().put("id", XRayService.randomHex(16)).put("name", "Initialization")
                    .put("start_time", start).put("end_time", initEnd);
        }
        double overhead = Math.max(initEnd, end - 0.001);
        ObjectNode invocation = subsegments.addObject().put("id", XRayService.randomHex(16)).put("name", "Invocation")
                .put("start_time", initEnd).put("end_time", overhead);
        subsegments.addObject().put("id", XRayService.randomHex(16)).put("name", "Overhead")
                .put("start_time", overhead).put("end_time", end);
        if (functionError != null) {
            ObjectNode cause = cause(result);
            for (ObjectNode node : new ObjectNode[]{function, invocation}) {
                node.put("fault", true);
                node.set("cause", cause.deepCopy());
            }
        }
        RequestScopes.runAs(arn.accountId(), arn.region(), () -> {
            for (ObjectNode document : new ObjectNode[]{service, function}) {
                xray.ingest(arn.region(), document.toString()).ifPresent(rejected ->
                        LOG.debugv("X-Ray rejected a Lambda segment of {0}: {1}", fn.getFunctionName(), rejected));
            }
        });
    }

    private ObjectNode segment(Trace trace, String id, LambdaFunction fn, String origin, double start, double end,
                               AwsArnUtils.Arn arn) {
        ObjectNode segment = mapper.createObjectNode().put("id", id).put("trace_id", trace.traceId())
                .put("name", fn.getFunctionName()).put("origin", origin).put("start_time", start)
                .put("end_time", end).put("resource_arn", fn.getFunctionArn());
        segment.putObject("aws").put("account_id", arn.accountId()).put("function_arn", fn.getFunctionArn());
        return segment;
    }

    private ObjectNode cause(InvokeResult result) {
        String type = result.getFunctionError();
        String message = "The function failed.";
        try {
            JsonNode payload = mapper.readTree(result.getPayload() == null ? new byte[0] : result.getPayload());
            if (payload != null) {
                type = payload.path("errorType").asText(type);
                message = payload.path("errorMessage").asText(message);
            }
        } catch (Exception e) {
            LOG.debugv("Unreadable Lambda error payload: {0}", e.getMessage());
        }
        ObjectNode cause = mapper.createObjectNode();
        cause.putArray("exceptions").addObject().put("id", XRayService.randomHex(16)).put("type", type)
                .put("message", message);
        return cause;
    }
}
