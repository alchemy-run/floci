# Application Signals

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/slo/...`, `/services`, ..., signed for `application-signals`

CloudWatch Application Signals monitors applications by their services, operations and service level objectives.

## Services

Services are discovered from the account's X-Ray traces. Each segment is a service, named by the segment. Its
environment follows from where it runs: `lambda:default`, `ecs:default`, `eks:default`, `ec2:default` or
`generic:default`. An operation is the request's method and path.

Calls to other services are the service's dependencies, and the calling services are its dependents. A downstream
call that sends no segment of its own appears as an `AWS::Service` or a `RemoteService`. Service summaries carry
the `ApplicationSignals` latency, fault and error metrics, and the groups the account's grouping configuration gives
them. A query covers at most 24 hours.

## Service level objectives

An SLO's indicator is either a CloudWatch metric query or a service operation's `ApplicationSignals` metric:

- **Period-based:** each period of the goal's interval either meets the threshold or doesn't.
- **Request-based:** good or bad requests are counted against the total.

Budget reports read the metric data from CloudWatch. They give the attainment, the total and remaining error budget,
and a status: `OK`, `WARNING` below the warning threshold, `BREACHED` once the budget is spent, or
`INSUFFICIENT_DATA` with no data. Exclusion windows are left out of the evaluation. Audit findings report breached
SLOs and services that return faults for more than 5% of their requests.

## Instrumentation configurations

Probes and breakpoints are kept per service, environment and code location, identified by a location hash. Agents
list the configurations that changed since their last sync and report their status, which
`GetInstrumentationConfigurationStatus` returns.

## Configuration

```yaml
floci:
  services:
    application-signals:
      enabled: true
```

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `StartDiscovery` | Turn on discovery and create the service-linked role |
| `ListServices` | List services seen in traces |
| `GetService` | Describe a service |
| `ListServiceOperations` | List a service's operations |
| `ListServiceDependencies` | List the services a service calls |
| `ListServiceDependents` | List the services that call a service |
| `ListServiceStates` | List services and their latest changes |
| `ListEntityEvents` | List an entity's change events |
| `ListAuditFindings` | Find breached SLOs and faulting services |
| `CreateServiceLevelObjective` | Create an SLO |
| `GetServiceLevelObjective` | Describe an SLO |
| `UpdateServiceLevelObjective` | Change an SLO |
| `DeleteServiceLevelObjective` | Delete an SLO |
| `ListServiceLevelObjectives` | List SLOs |
| `BatchGetServiceLevelObjectiveBudgetReport` | Attainment and error budget of SLOs |
| `BatchUpdateExclusionWindows` | Add or remove SLO exclusion windows |
| `ListServiceLevelObjectiveExclusionWindows` | List an SLO's exclusion windows |
| `CreateInstrumentationConfiguration` | Create a probe or breakpoint |
| `GetInstrumentationConfiguration` | Describe an instrumentation configuration |
| `DeleteInstrumentationConfiguration` | Delete an instrumentation configuration |
| `BatchDeleteInstrumentationConfigurations` | Delete configurations by scope or ARN |
| `ListInstrumentationConfigurations` | Configurations changed since an agent's last sync |
| `GetInstrumentationConfigurationStatus` | A configuration's status and events |
| `ReportInstrumentationConfigurationStatus` | Report configuration status from an agent |
| `PutGroupingConfiguration` | Set the account's grouping attributes |
| `ListGroupingAttributeDefinitions` | List grouping attributes |
| `DeleteGroupingConfiguration` | Remove the grouping configuration |
| `TagResource` | Tag an SLO or configuration |
| `UntagResource` | Remove tags |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
