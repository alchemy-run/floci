package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.SsmResourceBackend;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/** Provisions {@code AWS::SSM::Parameter}. */
@ApplicationScoped
public class SsmCfnProvisioner implements CfnResourceProvisioner {

    private static final int PARAMETER_NAME_MAX_LENGTH = 2048;

    private final SsmService ssmService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SsmResourceBackend backend;

    public SsmCfnProvisioner(SsmService ssmService) {
        this.ssmService = ssmService;
        this.backend = new SsmResourceBackend(ssmService, mapper);
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SSM::Parameter");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"),
                r.getLogicalId(), PARAMETER_NAME_MAX_LENGTH, false);
        String value = ctx.resolveOptional(props, "Value");
        if (value == null) {
            value = "";
        }
        String type = ctx.resolveOptional(props, "Type");
        if (type == null) {
            type = "String";
        }
        ObjectNode desired = mapper.createObjectNode();
        if (props != null) {
            JsonNode resolved = ctx.engine().resolveNode(props);
            if (resolved.isObject()) desired.setAll((ObjectNode) resolved);
        }
        desired.put("Name", name);
        desired.put("Type", type);
        desired.put("Value", value);
        backend.write(name, desired, name.equals(ctx.priorPhysicalId()), ctx.region());
        r.setPhysicalId(name);
        r.getAttributes().put("Name", name);
        r.getAttributes().put("Type", type);
        r.getAttributes().put("Value", value);
        r.getAttributes().put("Arn", parameterArn(name, ctx));
    }

    /** AWS's form is {@code parameter/<name>} whether or not the name starts with a slash. */
    private static String parameterArn(String name, ProvisionContext ctx) {
        String path = name.startsWith("/") ? name : "/" + name;
        return AwsArnUtils.Arn.of("ssm", ctx.region(), ctx.accountId(), "parameter" + path).toString();
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        try {
            ssmService.deleteParameter(physicalId, region);
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            if (!"ParameterNotFound".equals(e.getErrorCode())) throw e;
        }
    }
}
