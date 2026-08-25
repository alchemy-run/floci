# AWS Audit Manager

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements AWS Audit Manager account registration plus custom control, framework, and assessment lifecycle. Accounts default to `ACTIVE` so local stacks can create resources without the live-AWS RegisterAccount maintenance-mode gate.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `GetAccountStatus` | `GET /account/status` | Return `ACTIVE`, `INACTIVE`, or `PENDING_ACTIVATION` |
| `RegisterAccount` | `POST /account/registerAccount` | Enable Audit Manager (idempotent) |
| `DeregisterAccount` | `POST /account/deregisterAccount` | Disable Audit Manager |
| `CreateControl` | `POST /controls` | Create a custom control |
| `GetControl` | `GET /controls/{controlId}` | Return a control |
| `UpdateControl` | `PUT /controls/{controlId}` | Update a custom control |
| `DeleteControl` | `DELETE /controls/{controlId}` | Delete a custom control |
| `ListControls` | `GET /controls?controlType=` | List controls by type |
| `CreateAssessmentFramework` | `POST /assessmentFrameworks` | Create a custom framework |
| `GetAssessmentFramework` | `GET /assessmentFrameworks/{frameworkId}` | Return a framework |
| `UpdateAssessmentFramework` | `PUT /assessmentFrameworks/{frameworkId}` | Update a custom framework |
| `DeleteAssessmentFramework` | `DELETE /assessmentFrameworks/{frameworkId}` | Delete a custom framework |
| `ListAssessmentFrameworks` | `GET /assessmentFrameworks?frameworkType=` | List frameworks by type |
| `CreateAssessment` | `POST /assessments` | Create an assessment |
| `GetAssessment` | `GET /assessments/{assessmentId}` | Return an assessment |
| `UpdateAssessment` | `PUT /assessments/{assessmentId}` | Update an assessment |
| `DeleteAssessment` | `DELETE /assessments/{assessmentId}` | Delete an assessment |
| `ListAssessments` | `GET /assessments` | List assessments |
| `GetServicesInScope` | `GET /services` | List AWS services in scope |
| `GetInsights` | `GET /insights` | Account-level insights |
| `ListControlDomainInsights` | `GET /insights/control-domains` | Control-domain insights |
| `ListControlInsightsByControlDomain` | `GET /insights/controls` | Control insights for a domain |
| `GetDelegations` | `GET /delegations` | List delegations |
| `GetEvidenceFileUploadUrl` | `GET /evidenceFileUploadUrl` | Presigned evidence upload URL |
| `ListAssessmentReports` | `GET /assessmentReports` | List assessment reports |
| `ValidateAssessmentReportIntegrity` | `POST /assessmentReports/integrity` | Validate a report signature |
| `ListKeywordsForDataSource` | `GET /dataSourceKeywords` | Keywords for a data source |
| `ListNotifications` | `GET /notifications` | List notifications |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List tags |
| `TagResource` | `POST /tags/{resourceArn}` | Add or update tags |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=` | Remove tags |

Missing resources return `ResourceNotFoundException` (HTTP 404) with `resourceId` and `resourceType`. Unregistered accounts reject resource APIs with `AccessDeniedException` (HTTP 403).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AUDITMANAGER_ENABLED` | `true` | Enable or disable Audit Manager |
| `FLOCI_STORAGE_SERVICES_AUDITMANAGER_MODE` | *(inherits global)* | Optional Audit Manager storage-mode override |
| `FLOCI_STORAGE_SERVICES_AUDITMANAGER_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |
