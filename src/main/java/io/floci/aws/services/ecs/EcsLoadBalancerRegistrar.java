package io.floci.aws.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.EcsLoadBalancer;
import io.floci.aws.services.ecs.model.EcsRegisteredTargets;
import io.floci.aws.services.ecs.model.EcsServiceModel;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.EcsTaskAddress;
import io.floci.aws.services.ecs.model.NetworkBinding;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.Rule;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetHealth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Bridges ECS services to ELBv2: when an ECS service declares a {@code loadBalancers}
 * block, this registrar registers each running task container as a target in the named
 * ELBv2 target group (and deregisters it when the task stops).
 * <p>
 * One-way dependency ECS → ELBv2; the ELBv2 data plane reaches the container over plain
 * TCP and never calls back into ECS, so there is no cycle.
 */
@ApplicationScoped
public class EcsLoadBalancerRegistrar {

    private static final Logger LOG = Logger.getLogger(EcsLoadBalancerRegistrar.class);

    private final ElbV2Service elbV2Service;
    private final EcsContainerManager containerManager;
    // taskArn → the targets registered for it, persisted so they can be released after a restart
    private final AccountAwareStorageBackend<EcsRegisteredTargets> ledger;

    @Inject
    public EcsLoadBalancerRegistrar(ElbV2Service elbV2Service, EcsContainerManager containerManager,
                                    StorageFactory storageFactory) {
        this.elbV2Service = elbV2Service;
        this.containerManager = containerManager;
        this.ledger = storageFactory.create("ecs", "ecs-registered-targets.json",
                new TypeReference<Map<String, EcsRegisteredTargets>>() {});
    }

    /**
     * Registers the task's load-balanced containers as ELBv2 targets. The targets are recorded
     * before they are registered, so a process killed in between still leaves a record for the
     * next run to release. A target already in its group is recorded too: its address and port
     * are the task's own container, so it reaches nothing else, and once the task stops it would
     * reach nothing or whichever container Docker hands the address to next. ECS on AWS likewise
     * deregisters a stopping task's target whoever registered it.
     */
    public void registerTask(EcsTask task, EcsServiceModel svc, String region) {
        List<EcsRegisteredTargets.Target> planned = new ArrayList<>();
        forEachTarget(task, svc, (tgArn, td) ->
                planned.add(new EcsRegisteredTargets.Target(tgArn, td.getId(), td.getPort())));
        if (planned.isEmpty()) {
            return;
        }
        String taskArn = task.getTaskArn();
        if (taskArn != null) {
            ledger.put(taskArn, new EcsRegisteredTargets(region, List.copyOf(planned)));
        }
        List<EcsRegisteredTargets.Target> registered = new ArrayList<>();
        for (EcsRegisteredTargets.Target target : planned) {
            try {
                elbV2Service.registerTargets(region, target.targetGroupArn(), List.of(target.toDescription()));
                registered.add(target);
                LOG.infov("Registered ECS task target {0}:{1} into target group {2}",
                        target.id(), target.port(), target.targetGroupArn());
            } catch (Exception e) {
                LOG.warnv("Could not register ECS target into {0}: {1}", target.targetGroupArn(), e.getMessage());
            }
        }
        if (taskArn == null || registered.size() == planned.size()) {
            return;
        }
        if (registered.isEmpty()) {
            ledger.delete(taskArn);
        } else {
            ledger.put(taskArn, new EcsRegisteredTargets(region, List.copyOf(registered)));
        }
    }

    /**
     * Deregisters the targets {@link #registerTask} recorded for the task, and no other: a task
     * with no record registered nothing, as when none of its containers had a reachable binding.
     * Only a task without an ARN, which {@link #registerTask} cannot record, falls back to the
     * targets its containers resolve to now.
     */
    public void deregisterTask(EcsTask task, EcsServiceModel svc, String region) {
        if (task.getTaskArn() == null) {
            forEachTarget(task, svc, (tgArn, td) -> deregister(region, tgArn, td));
            return;
        }
        Optional<EcsRegisteredTargets> recorded = ledger.get(task.getTaskArn());
        if (recorded.isPresent()) {
            deregister(recorded.get());
            ledger.delete(task.getTaskArn());
        }
    }

    /**
     * Deregisters every target ECS recorded for a task, in every account. Only for startup, when
     * ECS holds no task at all: task state is memory-only, so each recorded target belongs to a
     * task of a previous run whose container is gone or is being removed, and Docker is free to
     * hand its address to an unrelated container. Targets ECS did not register are never touched.
     */
    public void releaseRecordedTargets() {
        for (AccountAwareStorageBackend.AccountEntry<EcsRegisteredTargets> entry
                : ledger.scanAllAccountEntries(key -> true)) {
            RequestScopes.runAs(entry.accountId(), () -> deregister(entry.value()));
            ledger.deleteForAccount(entry.accountId(), entry.key());
            LOG.infov("Released the load balancer targets of ECS task {0} left by a previous run", entry.key());
        }
    }

    /**
     * Deregisters, from the given target groups, each target at one of a starting task's addresses
     * and ports that no ECS task recorded. The address was free until this task took it, so such a target
     * was left by a task that is gone, as one registered by a Floci version that kept no record,
     * and would now send the group's traffic to this task, which belongs to some other service.
     * A target a live task recorded stays, whichever group it is in.
     */
    public void evictUnrecordedTargets(EcsTask task, Collection<String> targetGroupArns,
                                       Collection<EcsTaskAddress> addresses, String region) {
        if (targetGroupArns.isEmpty() || addresses.isEmpty()) {
            return;
        }
        Set<EcsRegisteredTargets.Target> recorded = new HashSet<>();
        for (EcsRegisteredTargets entry : ledger.scan(key -> true)) {
            if (entry.targets() != null) {
                recorded.addAll(entry.targets());
            }
        }
        for (String tgArn : targetGroupArns) {
            List<TargetHealth> health;
            try {
                health = elbV2Service.describeTargetHealth(region, tgArn, null);
            } catch (Exception e) {
                // A target group deleted while a service still names it: nothing in it to evict.
                LOG.debugv("Could not list the targets of {0}: {1}", tgArn, e.getMessage());
                continue;
            }
            for (TargetHealth th : health) {
                TargetDescription td = th.getTarget();
                boolean atTaskAddress = addresses.stream().anyMatch(a -> a.matches(td.getId(), td.getPort()));
                if (!atTaskAddress
                        || recorded.contains(new EcsRegisteredTargets.Target(tgArn, td.getId(), td.getPort()))) {
                    continue;
                }
                try {
                    elbV2Service.deregisterTargets(region, tgArn, List.of(td));
                    LOG.warnv("Deregistered stale target {0}:{1} from target group {2}: no ECS task registered it,"
                            + " and its address now belongs to ECS task {3}", td.getId(), td.getPort(), tgArn,
                            task.getTaskArn());
                } catch (Exception e) {
                    LOG.warnv("Could not deregister stale target {0}:{1} from {2}: {3}", td.getId(), td.getPort(),
                            tgArn, e.getMessage());
                }
            }
        }
    }

    private void deregister(EcsRegisteredTargets recorded) {
        if (recorded.targets() == null) {
            return;
        }
        for (EcsRegisteredTargets.Target target : recorded.targets()) {
            deregister(recorded.region(), target.targetGroupArn(), target.toDescription());
        }
    }

    private void deregister(String region, String tgArn, TargetDescription td) {
        try {
            elbV2Service.deregisterTargets(region, tgArn, List.of(td));
            LOG.infov("Deregistered ECS task target {0}:{1} from target group {2}",
                    td.getId(), td.getPort(), tgArn);
        } catch (Exception e) {
            LOG.warnv("Could not deregister ECS target from {0}: {1}", tgArn, e.getMessage());
        }
    }

    private void forEachTarget(EcsTask task, EcsServiceModel svc,
                               BiConsumer<String, TargetDescription> action) {
        if (svc == null || svc.getLoadBalancers() == null || svc.getLoadBalancers().isEmpty()) {
            return;
        }
        if (task.getContainers() == null || task.getContainers().isEmpty()) {
            return;
        }
        for (EcsLoadBalancer lb : svc.getLoadBalancers()) {
            if (lb.getTargetGroupArn() == null || lb.getTargetGroupArn().isBlank()) {
                continue;
            }
            String servingTargetGroupArn = lb.getServingTargetGroupArn();
            Container container = task.getContainers().stream()
                    .filter(c -> lb.getContainerName() == null
                            || lb.getContainerName().equals(c.getName()))
                    .findFirst()
                    .orElse(null);
            if (container == null || container.getNetworkBindings() == null) {
                continue;
            }
            NetworkBinding binding = container.getNetworkBindings().stream()
                    .filter(b -> lb.getContainerPort() == null
                            || lb.getContainerPort() == b.containerPort())
                    .findFirst()
                    .orElse(null);
            if (binding == null) {
                continue;
            }
            TargetDescription td = new TargetDescription();
            td.setId(containerManager.resolveContainerHost(container));
            td.setPort(EcsTaskAddress.isLoopback(td.getId()) ? binding.hostPort() : binding.containerPort());
            action.accept(servingTargetGroupArn, td);
        }
    }

    /**
     * ECS native blue/green: a new deployment of a {@code BLUE_GREEN} service runs its tasks in
     * the target group that is not serving, then points the production listener rule at it. Floci
     * completes the shift at once, with the bake time already passed, so the new group takes all
     * traffic and the old tasks are drained as the rolling path drains them.
     */
    public void shiftBlueGreenTraffic(EcsServiceModel svc, String region) {
        Object strategy = svc.getDeploymentConfiguration() == null
                ? null : svc.getDeploymentConfiguration().get("strategy");
        if (!"BLUE_GREEN".equals(strategy) || svc.getLoadBalancers() == null) {
            return;
        }
        for (EcsLoadBalancer lb : svc.getLoadBalancers()) {
            Map<String, Object> advanced = lb.getAdvancedConfiguration();
            if (advanced == null || lb.getTargetGroupArn() == null) {
                continue;
            }
            String alternate = advanced.get("alternateTargetGroupArn") instanceof String s ? s : null;
            String productionRule = advanced.get("productionListenerRule") instanceof String s ? s : null;
            if (alternate == null || productionRule == null) {
                continue;
            }
            String next = null;
            try {
                Rule rule = elbV2Service.describeRules(region, null, List.of(productionRule)).getFirst();
                // The rule says which group serves now. UpdateService may have replaced the service's
                // load balancers since the last shift, so the remembered serving group is only a fallback.
                String serving = servingGroup(rule, lb.getTargetGroupArn(), alternate);
                if (serving == null) {
                    serving = lb.getServingTargetGroupArn();
                }
                next = serving.equals(lb.getTargetGroupArn()) ? alternate : lb.getTargetGroupArn();
                List<Action> actions = new ArrayList<>();
                for (Action action : rule.getActions()) {
                    if ("forward".equals(action.getType())) {
                        if (action.getTargetGroups() != null && !action.getTargetGroups().isEmpty()) {
                            for (Action.TargetGroupTuple tuple : action.getTargetGroups()) {
                                tuple.setWeight(next.equals(tuple.getTargetGroupArn()) ? 100 : 0);
                            }
                        } else {
                            action.setTargetGroupArn(next);
                        }
                    }
                    actions.add(action);
                }
                elbV2Service.modifyRule(region, productionRule, null, actions);
                lb.setServingTargetGroupArn(next);
                LOG.infov("Blue/green: shifted {0} production traffic to {1}", svc.getServiceName(), next);
            } catch (RuntimeException e) {
                LOG.warnv("Blue/green: could not shift {0} to {1}: {2}", svc.getServiceName(), next, e.getMessage());
            }
        }
    }

    /** The group of the pair the rule forwards the larger weight to, or null when it names neither. */
    private static String servingGroup(Rule rule, String primary, String alternate) {
        String serving = null;
        int best = -1;
        for (Action action : rule.getActions()) {
            if (!"forward".equals(action.getType())) {
                continue;
            }
            if (action.getTargetGroups() == null || action.getTargetGroups().isEmpty()) {
                String arn = action.getTargetGroupArn();
                if (primary.equals(arn) || alternate.equals(arn)) {
                    return arn;
                }
                continue;
            }
            for (Action.TargetGroupTuple tuple : action.getTargetGroups()) {
                String arn = tuple.getTargetGroupArn();
                int weight = tuple.getWeight() == null ? 1 : tuple.getWeight();
                if ((primary.equals(arn) || alternate.equals(arn)) && weight > best) {
                    serving = arn;
                    best = weight;
                }
            }
        }
        return serving;
    }

    /**
     * Whether every target the task's load-balanced containers were registered as reports
     * {@code healthy}: ECS waits for the load balancer health check before it counts a task
     * towards a deployment's steady state. A task with no target to register has none to wait for.
     */
    public boolean targetsHealthy(EcsTask task, EcsServiceModel svc, String region) {
        boolean[] healthy = {true};
        forEachTarget(task, svc, (tgArn, td) -> {
            if (!healthy[0]) {
                return;
            }
            try {
                List<TargetHealth> health = elbV2Service.describeTargetHealth(region, tgArn, List.of(td));
                healthy[0] = !health.isEmpty()
                        && health.stream().allMatch(h -> "healthy".equals(h.getState()));
            } catch (Exception e) {
                LOG.debugv("Could not read ECS target health in {0}: {1}", tgArn, e.getMessage());
                healthy[0] = false;
            }
        });
        return healthy[0];
    }
}
