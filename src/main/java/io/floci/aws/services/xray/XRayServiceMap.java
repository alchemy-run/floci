package io.floci.aws.services.xray;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The services, operations and calls that traces observed in a time window, as Application Signals discovers them:
 * each segment is a service (named by the segment, with the environment its origin implies) and an operation (its
 * method and path), and each call to another service is a dependency.
 */
@ApplicationScoped
public class XRayServiceMap {

    /** A service as Application Signals identifies it. */
    public record ServiceKey(String type, String name, String environment) {
    }

    /** One service's operations and calls, with request outcome counts. */
    public static final class ObservedService {
        public final ServiceKey key;
        public final String resourceArn;
        public final Map<String, long[]> operations = new LinkedHashMap<>();
        public final List<Call> calls = new ArrayList<>();
        public long requests;
        public long faults;
        public long errors;
        public double latencySeconds;
        public double lastSeen;

        ObservedService(ServiceKey key, String resourceArn) {
            this.key = key;
            this.resourceArn = resourceArn;
        }
    }

    /** A call from one of a service's operations to another service's operation. */
    public record Call(String operation, ServiceKey target, String targetOperation) {
    }

    private final XRayService xray;

    @Inject
    public XRayServiceMap(XRayService xray) {
        this.xray = xray;
    }

    /** The services the account's traces in the Region observed between {@code start} and {@code end}. */
    public List<ObservedService> services(String region, double start, double end) {
        Map<ServiceKey, ObservedService> out = new LinkedHashMap<>();
        for (XRayTraces.Trace trace : xray.tracesBetween(region, start, end)) {
            for (XRayTraces.Node segment : trace.segments) {
                if (segment.end < start || segment.start > end) {
                    continue;
                }
                ServiceKey key = keyOf(segment);
                ObservedService service = out.computeIfAbsent(key, k -> new ObservedService(k, segment.resourceArn));
                String operation = operationOf(segment);
                long[] counts = service.operations.computeIfAbsent(operation, o -> new long[3]);
                counts[0]++;
                service.requests++;
                service.latencySeconds += segment.duration();
                service.lastSeen = Math.max(service.lastSeen, segment.end);
                if (segment.fault) {
                    counts[1]++;
                    service.faults++;
                } else if (segment.error) {
                    counts[2]++;
                    service.errors++;
                }
                List<XRayTraces.Node> under = new ArrayList<>();
                segment.walk(under);
                for (XRayTraces.Node call : under) {
                    if (!XRayTraces.isRemote(call)) {
                        continue;
                    }
                    XRayTraces.Node called = calledSegment(trace, call);
                    ServiceKey target = called != null ? keyOf(called) : remoteKey(call);
                    String targetOperation = called != null ? operationOf(called) : operationOf(call);
                    Call edge = new Call(operation, target, targetOperation);
                    if (!service.calls.contains(edge)) {
                        service.calls.add(edge);
                    }
                    if (called == null) {
                        out.computeIfAbsent(target, k -> new ObservedService(k, null)).operations
                                .computeIfAbsent(targetOperation, o -> new long[3]);
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private static XRayTraces.Node calledSegment(XRayTraces.Trace trace, XRayTraces.Node call) {
        for (XRayTraces.Node segment : trace.segments) {
            if (Objects.equals(call.id, segment.parentId)) {
                return segment;
            }
        }
        return null;
    }

    /** A service's environment, as Application Signals names it from where the service runs. */
    private static ServiceKey keyOf(XRayTraces.Node segment) {
        String origin = segment.origin == null ? "" : segment.origin;
        String environment;
        if (origin.startsWith("AWS::Lambda")) {
            environment = "lambda:default";
        } else if (origin.startsWith("AWS::ECS")) {
            environment = "ecs:default";
        } else if (origin.startsWith("AWS::EKS")) {
            environment = "eks:default";
        } else if (origin.startsWith("AWS::EC2")) {
            environment = "ec2:default";
        } else {
            environment = "generic:default";
        }
        return new ServiceKey("Service", segment.name, environment);
    }

    /** A downstream call that sent no segment of its own: an AWS service or a remote endpoint. */
    private static ServiceKey remoteKey(XRayTraces.Node call) {
        String type = XRayTraces.type(call);
        if (type != null && type.startsWith("AWS::")) {
            return new ServiceKey("AWS::Service", type, null);
        }
        return new ServiceKey("RemoteService", call.name, null);
    }

    /** An operation: the request's method and path, or the segment's name when it has no HTTP request. */
    private static String operationOf(XRayTraces.Node node) {
        if (node.httpMethod == null || node.httpUrl == null) {
            return "InternalOperation";
        }
        String path;
        try {
            path = URI.create(node.httpUrl).getPath();
        } catch (IllegalArgumentException e) {
            path = node.httpUrl;
        }
        return node.httpMethod + " " + (path == null || path.isEmpty() ? "/" : path);
    }
}
