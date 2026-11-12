# IAM Roles Anywhere

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/trustanchors` and the rest, signed for `rolesanywhere`; `CreateSession` is
`POST /sessions`, signed with `AWS4-X509-RSA-SHA256` or `AWS4-X509-ECDSA-SHA256`

Workloads outside AWS exchange an X.509 certificate for role credentials, as with
`aws_signing_helper credential-process`.

## Trust anchors, profiles and CRLs

A trust anchor holds a bundle of CA certificates (`CERTIFICATE_BUNDLE`), checked as X.509 CA certificates, or names an
ACM Private CA (`AWS_ACM_PCA`) whose certificate and chain it trusts. Its notification settings default to 45 days
before expiry. A profile lists the roles sessions may assume, a duration (900 to 43,200 seconds), an optional session
policy and managed policies, and attribute mappings, which default to the subject's and issuer's `C`, `CN`, `L`, `O`,
`OU` and `ST`, and the SAN's `DNS`, `URI` and `Name`. CRLs are X.509 CRLs imported for a trust anchor.

## Sessions

`CreateSession` takes the certificate in `X-Amz-X509` (base64 DER) and intermediates in `X-Amz-X509-Chain`. It refuses
the request (`AccessDeniedException`) unless:

- the trust anchor is enabled, and the certificate is an end-entity certificate, valid now, that chains to it;
- no enabled CRL of the trust anchor revokes the certificate or its intermediates;
- the request is signed with the certificate's private key, and the credential names its serial number;
- the profile is enabled, lists the role, and the role exists and trusts `rolesanywhere.amazonaws.com`.

It returns credentials for the role, which STS and IAM recognize as a session of the role, with `sourceIdentity`
`CN=<common name>`. Every attempt, successful or not, is recorded under the certificate's subject (`ListSubjects`,
`GetSubject`).

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateTrustAnchor` | Create a trust anchor |
| `GetTrustAnchor` | Get a trust anchor |
| `UpdateTrustAnchor` | Update a trust anchor |
| `DeleteTrustAnchor` | Delete a trust anchor |
| `EnableTrustAnchor` | Enable a trust anchor |
| `DisableTrustAnchor` | Disable a trust anchor |
| `ListTrustAnchors` | List trust anchors |
| `PutNotificationSettings` | Set a trust anchor's expiry notifications |
| `ResetNotificationSettings` | Reset expiry notifications to their defaults |
| `CreateProfile` | Create a profile |
| `GetProfile` | Get a profile |
| `UpdateProfile` | Update a profile |
| `DeleteProfile` | Delete a profile |
| `EnableProfile` | Enable a profile |
| `DisableProfile` | Disable a profile |
| `ListProfiles` | List profiles |
| `PutAttributeMapping` | Set a profile's attribute mapping |
| `DeleteAttributeMapping` | Remove attribute mapping rules |
| `ImportCrl` | Import a CRL for a trust anchor |
| `GetCrl` | Get a CRL |
| `UpdateCrl` | Update a CRL |
| `DeleteCrl` | Delete a CRL |
| `EnableCrl` | Enable a CRL |
| `DisableCrl` | Disable a CRL |
| `ListCrls` | List CRLs |
| `GetSubject` | Describe a subject and its certificates |
| `ListSubjects` | List the subjects that created sessions |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->
