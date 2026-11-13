package io.floci.aws.services.lambda;

import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.LambdaFunction;

/**
 * Traces Lambda invocations, as X-Ray does for functions with active tracing: {@link #begin} decides whether an
 * invocation is sampled and names the trace the runtime receives in {@code Lambda-Runtime-Trace-Id}, and {@link #end}
 * records the Lambda service's and the function's segments once it finishes. Implemented by the X-Ray service, so
 * Lambda stays independent of it: it finds the implementation through CDI.
 */
public interface LambdaTracing {

    /** The trace an invocation joins: its root, the function segment the runtime's subsegments attach to, sampling. */
    record Trace(String traceId, String serviceSegmentId, String functionSegmentId, boolean sampled) {

        /** The {@code X-Amzn-Trace-Id} header the runtime receives. */
        public String header() {
            return "Root=" + traceId + ";Parent=" + functionSegmentId + ";Sampled=" + (sampled ? "1" : "0");
        }
    }

    /** Starts the invocation's trace, or returns null when the function isn't traced. */
    Trace begin(LambdaFunction fn);

    /** Records the invocation's segments; {@code coldStart} adds the initialization the invocation waited for. */
    void end(Trace trace, LambdaFunction fn, long startMillis, long initEndMillis, long endMillis, boolean coldStart,
             InvokeResult result);
}
