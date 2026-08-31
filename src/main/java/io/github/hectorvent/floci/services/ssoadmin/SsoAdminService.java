package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.identitycenter.IdentityCenterService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * SSO Admin facade over {@link IdentityCenterService} so both JSON 1.1 handlers
 * share one instance/permission-set/assignment store.
 */
@ApplicationScoped
public class SsoAdminService {

    private final IdentityCenterService identityCenter;

    @Inject
    public SsoAdminService(IdentityCenterService identityCenter) {
        this.identityCenter = identityCenter;
    }

    public ObjectNode listInstances() {
        return identityCenter.listInstances();
    }

    public ObjectNode createInstance(JsonNode request, String region) {
        return identityCenter.createInstance(request);
    }

    public ObjectNode deleteInstance(JsonNode request) {
        return identityCenter.deleteInstance(request);
    }

    public ObjectNode describeInstance(JsonNode request) {
        return identityCenter.describeInstance(request);
    }

    public ObjectNode createPermissionSet(JsonNode request) {
        return identityCenter.createPermissionSet(request);
    }

    public ObjectNode describePermissionSet(JsonNode request) {
        return identityCenter.describePermissionSet(request);
    }

    public ObjectNode updatePermissionSet(JsonNode request) {
        return identityCenter.updatePermissionSet(request);
    }

    public ObjectNode deletePermissionSet(JsonNode request) {
        return identityCenter.deletePermissionSet(request);
    }

    public ObjectNode listPermissionSets(JsonNode request) {
        return identityCenter.listPermissionSets(request);
    }

    public ObjectNode createAccountAssignment(JsonNode request) {
        return identityCenter.createAccountAssignment(request);
    }

    public ObjectNode deleteAccountAssignment(JsonNode request) {
        return identityCenter.deleteAccountAssignment(request);
    }

    public ObjectNode describeAccountAssignmentCreationStatus(JsonNode request) {
        return identityCenter.describeAccountAssignmentCreationStatus(request);
    }

    public ObjectNode describeAccountAssignmentDeletionStatus(JsonNode request) {
        return identityCenter.describeAccountAssignmentDeletionStatus(request);
    }

    public ObjectNode listAccountAssignments(JsonNode request) {
        return identityCenter.listAccountAssignments(request);
    }

    public ObjectNode listAccountAssignmentsForPrincipal(JsonNode request) {
        return identityCenter.listAccountAssignmentsForPrincipal(request);
    }

    public ObjectNode listAccountsForProvisionedPermissionSet(JsonNode request) {
        return identityCenter.listAccountsForProvisionedPermissionSet(request);
    }
}
