package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.ScheduledAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Redshift Serverless scheduled actions. They are stored and echoed, not run: Floci never takes
 * the snapshot on the schedule, and a cron schedule reports no upcoming invocations.
 *
 * <p>Required members, name patterns and lengths come from the AWS model through
 * {@link RedshiftServerlessRequestShapes}; what the model cannot express (the schedule union, the time
 * window, the retention range and the namespaces that must exist) is checked here.
 *
 * <p>An action belongs to a namespace but lives in its own store, so deleting the namespace needs
 * no hook here. Each read checks that the namespace is still there and drops the action otherwise.
 */
@ApplicationScoped
public class RedshiftServerlessScheduledActionService implements Resettable {

    private static final int MAX_RETENTION_DAYS = 3653;

    private final AccountAwareStorageBackend<ScheduledAction> actions;
    private final RedshiftServerlessService serverless;

    @Inject
    public RedshiftServerlessScheduledActionService(StorageFactory storageFactory,
                                                    RedshiftServerlessService serverless) {
        this.actions = storageFactory.create("redshiftserverless", "redshiftserverless-scheduledactions.json",
                new TypeReference<Map<String, ScheduledAction>>() {});
        this.serverless = serverless;
    }

    /**
     * Creates the action described by {@code request}. {@code enabled} defaults to true. The target
     * namespace of the snapshot and the owning namespace must both exist.
     */
    public synchronized ScheduledAction createScheduledAction(ScheduledAction request, String region) {
        requireNamespace(request.getNamespaceName(), region);
        validateSchedule(request);
        validateTarget(request, region);
        validateWindow(request);
        if (live(region).stream().anyMatch(existing ->
                existing.getScheduledActionName().equals(request.getScheduledActionName()))) {
            throw ServerlessSupport.conflict("The scheduled action " + request.getScheduledActionName()
                    + " already exists.");
        }

        ScheduledAction action = new ScheduledAction(request);
        action.setOwnerId(namespaceIdOf(request.getNamespaceName(), region));
        action.setScheduledActionUuid(UUID.randomUUID().toString());
        action.setEnabled(request.getEnabled() == null || request.getEnabled());
        actions.put(storageKey(region, action.getScheduledActionName()), action);
        return new ScheduledAction(action);
    }

    public synchronized ScheduledAction getScheduledAction(String scheduledActionName, String region) {
        return new ScheduledAction(require(scheduledActionName, region));
    }

    public synchronized PaginatedResult<ScheduledAction> listScheduledActions(String namespaceName, String region,
                                                                              Integer maxResults, String nextToken) {
        int limit = ServerlessSupport.pageSize(maxResults);
        List<ScheduledAction> matching = live(region).stream()
                .filter(action -> namespaceName == null || namespaceName.equals(action.getNamespaceName()))
                .sorted(Comparator.comparing(ScheduledAction::getScheduledActionName))
                .map(ScheduledAction::new)
                .toList();
        return Pagination.paginate(matching, ScheduledAction::getScheduledActionName,
                limit, nextToken, "scheduled-action",
                token -> ServerlessSupport.invalidPagination("Invalid nextToken."));
    }

    /**
     * Applies the members {@code changes} carries. The schedule and the target replace the stored
     * ones as a whole, because each is a union of which exactly one member may be set.
     */
    public synchronized ScheduledAction updateScheduledAction(ScheduledAction changes, String region) {
        ScheduledAction current = require(changes.getScheduledActionName(), region);
        ScheduledAction updated = new ScheduledAction(current);
        if (changes.getRoleArn() != null) {
            updated.setRoleArn(changes.getRoleArn());
        }
        if (changes.getDescription() != null) {
            updated.setDescription(changes.getDescription());
        }
        if (changes.getEnabled() != null) {
            updated.setEnabled(changes.getEnabled());
        }
        if (changes.getStartTime() != null) {
            updated.setStartTime(changes.getStartTime());
        }
        if (changes.getEndTime() != null) {
            updated.setEndTime(changes.getEndTime());
        }
        if (changes.isScheduleSpecified()) {
            validateSchedule(changes);
            updated.setScheduleAt(changes.getScheduleAt());
            updated.setScheduleCron(changes.getScheduleCron());
        }
        boolean changesTarget = changes.isTargetSpecified()
                || changes.getTargetNamespaceName() != null || changes.getSnapshotNamePrefix() != null
                || changes.getRetentionPeriod() != null || changes.getSnapshotTags() != null;
        if (changesTarget) {
            validateTarget(changes, region);
            updated.setTargetNamespaceName(changes.getTargetNamespaceName());
            updated.setSnapshotNamePrefix(changes.getSnapshotNamePrefix());
            updated.setRetentionPeriod(changes.getRetentionPeriod());
            updated.setSnapshotTags(changes.getSnapshotTags());
        }
        validateWindow(updated);
        actions.put(storageKey(region, updated.getScheduledActionName()), updated);
        return new ScheduledAction(updated);
    }

    public synchronized ScheduledAction deleteScheduledAction(String scheduledActionName, String region) {
        ScheduledAction existing = new ScheduledAction(require(scheduledActionName, region));
        actions.delete(storageKey(region, scheduledActionName));
        return existing;
    }

    @Override
    public void clear() {
        actions.clear();
    }

    private ScheduledAction require(String scheduledActionName, String region) {
        ScheduledAction action = actions.get(storageKey(region, scheduledActionName)).orElse(null);
        if (action != null && !isOnSameNamespace(action, region)) {
            actions.delete(storageKey(region, scheduledActionName));
            action = null;
        }
        if (action == null) {
            throw ServerlessSupport.notFound("The scheduled action " + scheduledActionName + " was not found.");
        }
        return action;
    }

    /** The region's actions whose namespace is still there; any other is deleted as it is found. */
    private List<ScheduledAction> live(String region) {
        return ServerlessSupport.live(actions, region,
                action -> isOnSameNamespace(action, region),
                action -> storageKey(region, action.getScheduledActionName()));
    }

    /** A namespace recreated under the same name has a new id, so it never inherits its predecessor's actions. */
    private boolean isOnSameNamespace(ScheduledAction action, String region) {
        return serverless.findNamespace(action.getNamespaceName(), region)
                .map(namespace -> Objects.equals(namespace.getNamespaceId(), action.getOwnerId()))
                .orElse(false);
    }

    private String namespaceIdOf(String namespaceName, String region) {
        return serverless.findNamespace(namespaceName, region).map(Namespace::getNamespaceId).orElse(null);
    }

    private void requireNamespace(String namespaceName, String region) {
        if (!serverless.namespaceExists(namespaceName, region)) {
            throw ServerlessSupport.notFound("The namespace " + namespaceName + " was not found.");
        }
    }

    /** Exactly one of {@code at} and {@code cron} must be set: the schedule is a union. */
    private static void validateSchedule(ScheduledAction action) {
        boolean hasAt = action.getScheduleAt() != null;
        boolean hasCron = action.getScheduleCron() != null && !action.getScheduleCron().isBlank();
        if (hasAt == hasCron) {
            throw ServerlessSupport.validation("schedule must set exactly one of at and cron.");
        }
    }

    private void validateTarget(ScheduledAction action, String region) {
        if (action.getTargetNamespaceName() == null) {
            throw ServerlessSupport.validation("targetAction must set createSnapshot.");
        }
        requireNamespace(action.getTargetNamespaceName(), region);
        Integer retention = action.getRetentionPeriod();
        if (retention != null && retention != -1 && (retention < 1 || retention > MAX_RETENTION_DAYS)) {
            throw ServerlessSupport.validation("targetAction.createSnapshot.retentionPeriod must be -1 "
                    + "(indefinite) or between 1 and " + MAX_RETENTION_DAYS + ".");
        }
    }

    private static void validateWindow(ScheduledAction action) {
        if (action.getStartTime() != null && action.getEndTime() != null
                && !action.getEndTime().isAfter(action.getStartTime())) {
            throw ServerlessSupport.validation("endTime must be after startTime.");
        }
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }
}
