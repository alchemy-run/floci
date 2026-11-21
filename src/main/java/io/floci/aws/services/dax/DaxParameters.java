package io.floci.aws.services.dax;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The DAX engine's parameters and the node types it runs on. DAX exposes two tunables, the item cache and query
 * cache TTLs, both defaulting to five minutes; a change applies to a node once it reboots.
 */
final class DaxParameters {

    static final String DEFAULT_GROUP = "default.dax1.0";
    static final Map<String, String[]> PARAMETERS = new LinkedHashMap<>();

    static {
        PARAMETERS.put("query-ttl-millis", new String[] {"300000",
            "Duration in milliseconds for queries to remain cached"});
        PARAMETERS.put("record-ttl-millis", new String[] {"300000",
            "Duration in milliseconds for records to remain valid in cache (Default: 0 = infinite)"});
    }

    static final Set<String> NODE_TYPES = Set.of(
            "dax.t2.small", "dax.t2.medium", "dax.t3.small", "dax.t3.medium",
            "dax.r3.large", "dax.r3.xlarge", "dax.r3.2xlarge", "dax.r3.4xlarge", "dax.r3.8xlarge",
            "dax.r4.large", "dax.r4.xlarge", "dax.r4.2xlarge", "dax.r4.4xlarge", "dax.r4.8xlarge",
            "dax.r4.16xlarge",
            "dax.r5.large", "dax.r5.xlarge", "dax.r5.2xlarge", "dax.r5.4xlarge", "dax.r5.8xlarge",
            "dax.r5.12xlarge", "dax.r5.16xlarge", "dax.r5.24xlarge");

    private DaxParameters() {
    }

    /** One parameter as DescribeParameters reports it, with {@code value} set by the group or the default. */
    static ObjectNode describe(ObjectMapper mapper, String name, String value) {
        String[] definition = PARAMETERS.get(name);
        ObjectNode parameter = mapper.createObjectNode();
        parameter.put("ParameterName", name);
        parameter.put("ParameterType", "DEFAULT");
        parameter.put("ParameterValue", value == null ? definition[0] : value);
        parameter.putArray("NodeTypeSpecificValues");
        parameter.put("Description", definition[1]);
        parameter.put("Source", value == null || value.equals(definition[0]) ? "system" : "user");
        parameter.put("DataType", "integer");
        parameter.put("AllowedValues", "0-");
        parameter.put("IsModifiable", "TRUE");
        parameter.put("ChangeType", "REQUIRES_REBOOT");
        return parameter;
    }

    static List<String> names() {
        return List.copyOf(PARAMETERS.keySet());
    }
}
