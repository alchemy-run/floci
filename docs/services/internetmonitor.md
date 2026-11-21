# Amazon CloudWatch Internet Monitor

**Protocol:** REST JSON (`/v20210603/Monitors`, `/v20210603/InternetEvents`, `/tags/<arn>`)
**Endpoint:** `http://localhost:4566/`, signed for `internetmonitor`.

Monitors watch the traffic Floci really serves: each request a monitored CloudFront distribution answers, each request
an Application or Network Load Balancer in a monitored VPC answers, and each connection a monitored Network Load
Balancer's TCP listener accepts. Floci gathers a monitor's traffic over measurement windows of
`floci.services.internetmonitor.measurement-seconds` (300, Internet Monitor's five minutes), honouring
`TrafficPercentageToMonitor` and `MaxCityNetworksToMonitor`. A request counts against availability when it goes
unanswered or is answered with a 5xx, and against performance when its round-trip time exceeds
`floci.services.internetmonitor.performance-threshold-millis` (1000), Floci's stand-in for the baseline Internet
Monitor learns.

Floci has no IP geolocation database, so every viewer is in the client location configured under
`floci.services.internetmonitor.client-location` (city, metro, subdivision and country `Local`, network
`Floci local network`, the private-use ASN 64512). A city-network is that location served from a Region, for load
balancers, or from `CloudFront`.

When a window closes, its measurements are:

- published to the log groups `/aws/internet-monitor/<monitor>/byCity`, `byMetro`, `bySubdivision` and `byCountry`
  (one stream per service location) in Internet Monitor's record format, for the top 500 city-networks;
- delivered, when the monitor's `S3Config` is enabled, to
  `<prefix>/AWSLogs/<account>/internetmonitor/<region>/<yyyy>/<mm>/<dd>/` in the bucket;
- checked against the monitor's `HealthEventsConfig`: an overall availability or performance score below its threshold
  (95) opens an `AVAILABILITY` or `PERFORMANCE` health event, and a city-network below its local threshold (60) with at
  least `MinTrafficImpact` (0.1%) of the traffic impacted opens a `LOCAL_` one. An event updates while the condition
  lasts and resolves in the first window with traffic that no longer crosses it. Each change is sent to EventBridge
  from `aws.internetmonitor` as `Health Event Created`, `Health Event Updated` or `Health Event Closed`;
- stored for the query interface, which runs `MEASUREMENTS`, `TOP_LOCATIONS`, `TOP_LOCATION_DETAILS`,
  `OVERALL_TRAFFIC_SUGGESTIONS` and `OVERALL_TRAFFIC_SUGGESTIONS_DETAILS` over them with Internet Monitor's fields and
  filters. Suggestions compare only the service locations Floci measured, and `ROUTING_SUGGESTIONS` returns no rows:
  Floci's viewers reach it without DNS resolvers.

Internet events are raised from all the traffic Floci serves, monitored or not, when its availability or performance
score falls below 95 in a window, and resolve when it recovers. They are kept in memory.

A created or updated monitor is `PENDING` for `floci.services.internetmonitor.pending-millis` (1000) and then `ACTIVE`
or `INACTIVE`; only an `INACTIVE` monitor can be deleted. Monitors take VPCs, Network Load Balancers, CloudFront
distributions and WorkSpaces directories of the caller's account, regional ones in the monitor's Region.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateMonitor` | Create a monitor of VPCs, Network Load Balancers, distributions or WorkSpaces directories |
| `ListMonitors` | List monitors, by status |
| `GetMonitor` | Get a monitor with its status and processing status |
| `UpdateMonitor` | Add or remove resources, change limits, thresholds and S3 delivery, or activate and deactivate |
| `DeleteMonitor` | Delete an inactive monitor |
| `ListHealthEvents` | List a monitor's health events, by time and status |
| `GetHealthEvent` | Get a health event with its impacted locations |
| `ListInternetEvents` | List internet events, by time, status and type |
| `GetInternetEvent` | Get an internet event |
| `StartQuery` | Query a monitor's stored measurements |
| `GetQueryStatus` | Get a query's status |
| `GetQueryResults` | Get a finished query's fields and rows |
| `StopQuery` | Cancel a queued or running query |
| `ListTagsForResource` | List a monitor's tags |
| `TagResource` | Tag a monitor |
| `UntagResource` | Remove tags from a monitor |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    internetmonitor:
      enabled: true
      measurement-seconds: 300
      pending-millis: 1000
      performance-threshold-millis: 1000
      client-location:
        city: Local
        metro: Local
        subdivision: Local
        subdivision-code: LO
        country: Local
        country-code: ZZ
        latitude: 0
        longitude: 0
        as-name: Floci local network
        as-number: 64512
```
