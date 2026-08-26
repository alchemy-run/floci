# IAM Roles Anywhere

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements IAM Roles Anywhere trust anchors, profiles, CRLs, and subjects. Literal `/subjects`, `/subject/{id}`, `/trustanchors`, `/profiles`, and `/crls` paths take JAX-RS precedence over S3's `/{bucket}` catch-all. Resources are isolated by account and region.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `ListSubjects` | `GET /subjects` | List certificate-identity subjects in the account and region |
| `GetSubject` | `GET /subject/{subjectId}` | Return a subject, or `ResourceNotFoundException` |
| `CreateTrustAnchor` | `POST /trustanchors` | Create a trust anchor |
| `GetTrustAnchor` | `GET /trustanchor/{trustAnchorId}` | Return a trust anchor |
| `ListTrustAnchors` | `GET /trustanchors` | List trust anchors |
| `UpdateTrustAnchor` | `PATCH /trustanchor/{trustAnchorId}` | Update name or source |
| `DeleteTrustAnchor` | `DELETE /trustanchor/{trustAnchorId}` | Delete a trust anchor |
| `EnableTrustAnchor` / `DisableTrustAnchor` | `POST /trustanchor/{id}/enable\|disable` | Toggle enabled |
| `CreateProfile` | `POST /profiles` | Create a profile |
| `GetProfile` | `GET /profile/{profileId}` | Return a profile |
| `ListProfiles` | `GET /profiles` | List profiles |
| `UpdateProfile` | `PATCH /profile/{profileId}` | Update profile fields |
| `DeleteProfile` | `DELETE /profile/{profileId}` | Delete a profile |
| `ImportCrl` | `POST /crls` | Import a CRL |
| `GetCrl` | `GET /crl/{crlId}` | Return a CRL |
| `ListCrls` | `GET /crls` | List CRLs |
| `ListTagsForResource` | `GET /ListTagsForResource` | List tags |
| `TagResource` | `POST /TagResource` | Add or overwrite tags |
| `UntagResource` | `POST /UntagResource` | Remove tags |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ROLESANYWHERE_ENABLED` | `true` | Enable or disable Roles Anywhere |
| `FLOCI_STORAGE_SERVICES_ROLESANYWHERE_MODE` | *(inherits global)* | Optional storage-mode override |
| `FLOCI_STORAGE_SERVICES_ROLESANYWHERE_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |
