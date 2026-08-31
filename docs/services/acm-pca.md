# ACM PCA

**Protocol:** JSON 1.1 (`X-Amz-Target: ACMPrivateCA.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCertificateAuthority` | Create a root or subordinate private CA |
| `DescribeCertificateAuthority` | Read CA status and configuration |
| `ListCertificateAuthorities` | List private CAs |
| `UpdateCertificateAuthority` | Update status or revocation configuration |
| `DeleteCertificateAuthority` | Soft-delete a CA into the restoration window |
| `ListTags` | List tags on a CA |
| `TagCertificateAuthority` | Add or update tags |
| `UntagCertificateAuthority` | Remove tags |
| `CreatePermission` | Grant ACM permissions on a CA |
| `DeletePermission` | Revoke a permission |
| `ListPermissions` | List permissions |
| `PutPolicy` | Attach a resource policy |
| `GetPolicy` | Read a resource policy |
| `DeletePolicy` | Delete a resource policy |
| `GetCertificateAuthorityCsr` | Return the CA CSR PEM |
| `IssueCertificate` | Sign a CSR (root template allowed while pending) |
| `GetCertificate` | Return an issued certificate PEM |
| `ImportCertificateAuthorityCertificate` | Install the CA certificate and activate |
| `GetCertificateAuthorityCertificate` | Return the installed CA certificate |
| `RevokeCertificate` | Revoke an issued certificate by serial |
| `CreateCertificateAuthorityAuditReport` | Write an audit report to S3 |
| `DescribeCertificateAuthorityAuditReport` | Poll an audit report |
<!-- floci:actions:end -->

## Emulation Behavior

- Create generates a real RSA/EC key pair and PKCS#10 CSR. Status starts at `PENDING_CERTIFICATE`.
- `IssueCertificate` with `arn:aws:acm-pca:::template/RootCACertificate/V1` self-signs the CA CSR while pending.
- `ImportCertificateAuthorityCertificate` installs that certificate and sets `ACTIVE`.
- End-entity certificates are signed with the CA private key and returned immediately from `GetCertificate`.
- Audit reports are written to the named S3 bucket as JSON or CSV with status `SUCCESS`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACM_PCA_ENABLED` | `true` | Enable or disable the service |
