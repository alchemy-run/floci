package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.iam.model.AccessAdvisorJob;
import io.github.hectorvent.floci.services.iam.model.AccountPasswordPolicy;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.LoginProfile;
import io.github.hectorvent.floci.services.iam.model.OidcProvider;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
import io.github.hectorvent.floci.services.iam.model.SamlProvider;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import io.github.hectorvent.floci.services.iam.model.ServiceSpecificCredential;
import io.github.hectorvent.floci.services.iam.model.SigningCertificate;
import io.github.hectorvent.floci.services.iam.model.SshPublicKey;
import io.github.hectorvent.floci.services.iam.model.VirtualMfaDevice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Query-protocol handlers for IAM operations beyond the core user/group/role/policy set.
 */
@ApplicationScoped
public class IamExtendedQueryHandler {

    private final IamService iamService;
    private final IamExtendedService extra;
    private final IamPolicyEvaluator policyEvaluator;

    @Inject
    public IamExtendedQueryHandler(IamService iamService, IamExtendedService extra,
                                   IamPolicyEvaluator policyEvaluator) {
        this.iamService = iamService;
        this.extra = extra;
        this.policyEvaluator = policyEvaluator;
    }

    public Optional<Response> handle(String action, MultivaluedMap<String, String> params) {
        try {
            return Optional.ofNullable(switch (action) {
                case "CreateAccountAlias" -> handleCreateAccountAlias(params);
                case "DeleteAccountAlias" -> handleDeleteAccountAlias(params);
                case "ListAccountAliases" -> handleListAccountAliases();
                case "GetAccountPasswordPolicy" -> handleGetAccountPasswordPolicy();
                case "UpdateAccountPasswordPolicy" -> handleUpdateAccountPasswordPolicy(params);
                case "DeleteAccountPasswordPolicy" -> handleDeleteAccountPasswordPolicy();
                case "CreateLoginProfile" -> handleCreateLoginProfile(params);
                case "GetLoginProfile" -> handleGetLoginProfile(params);
                case "UpdateLoginProfile" -> handleUpdateLoginProfile(params);
                case "DeleteLoginProfile" -> handleDeleteLoginProfile(params);
                case "CreateVirtualMFADevice" -> handleCreateVirtualMfaDevice(params);
                case "ListVirtualMFADevices" -> handleListVirtualMfaDevices(params);
                case "GetMFADevice" -> handleGetMfaDevice(params);
                case "ListMFADevices" -> handleListMfaDevices(params);
                case "EnableMFADevice" -> handleEnableMfaDevice(params);
                case "DeactivateMFADevice" -> handleDeactivateMfaDevice(params);
                case "DeleteVirtualMFADevice" -> handleDeleteVirtualMfaDevice(params);
                case "ListMFADeviceTags" -> handleListMfaDeviceTags(params);
                case "TagMFADevice" -> handleTagMfaDevice(params);
                case "UntagMFADevice" -> handleUntagMfaDevice(params);
                case "CreateSAMLProvider" -> handleCreateSamlProvider(params);
                case "GetSAMLProvider" -> handleGetSamlProvider(params);
                case "UpdateSAMLProvider" -> handleUpdateSamlProvider(params);
                case "DeleteSAMLProvider" -> handleDeleteSamlProvider(params);
                case "ListSAMLProviders" -> handleListSamlProviders();
                case "ListSAMLProviderTags" -> handleListSamlProviderTags(params);
                case "TagSAMLProvider" -> handleTagSamlProvider(params);
                case "UntagSAMLProvider" -> handleUntagSamlProvider(params);
                case "CreateOpenIDConnectProvider" -> handleCreateOidcProvider(params);
                case "GetOpenIDConnectProvider" -> handleGetOidcProvider(params);
                case "DeleteOpenIDConnectProvider" -> handleDeleteOidcProvider(params);
                case "ListOpenIDConnectProviders" -> handleListOidcProviders();
                case "AddClientIDToOpenIDConnectProvider" -> handleAddOidcClientId(params);
                case "RemoveClientIDFromOpenIDConnectProvider" -> handleRemoveOidcClientId(params);
                case "UpdateOpenIDConnectProviderThumbprint" -> handleUpdateOidcThumbprints(params);
                case "ListOpenIDConnectProviderTags" -> handleListOidcProviderTags(params);
                case "TagOpenIDConnectProvider" -> handleTagOidcProvider(params);
                case "UntagOpenIDConnectProvider" -> handleUntagOidcProvider(params);
                case "UploadSSHPublicKey" -> handleUploadSshPublicKey(params);
                case "GetSSHPublicKey" -> handleGetSshPublicKey(params);
                case "ListSSHPublicKeys" -> handleListSshPublicKeys(params);
                case "UpdateSSHPublicKey" -> handleUpdateSshPublicKey(params);
                case "DeleteSSHPublicKey" -> handleDeleteSshPublicKey(params);
                case "UploadSigningCertificate" -> handleUploadSigningCertificate(params);
                case "ListSigningCertificates" -> handleListSigningCertificates(params);
                case "UpdateSigningCertificate" -> handleUpdateSigningCertificate(params);
                case "DeleteSigningCertificate" -> handleDeleteSigningCertificate(params);
                case "CreateServiceSpecificCredential" -> handleCreateServiceSpecificCredential(params);
                case "ListServiceSpecificCredentials" -> handleListServiceSpecificCredentials(params);
                case "UpdateServiceSpecificCredential" -> handleUpdateServiceSpecificCredential(params);
                case "DeleteServiceSpecificCredential" -> handleDeleteServiceSpecificCredential(params);
                case "UploadServerCertificate" -> handleUploadServerCertificate(params);
                case "GetServerCertificate" -> handleGetServerCertificate(params);
                case "ListServerCertificates" -> handleListServerCertificates(params);
                case "DeleteServerCertificate" -> handleDeleteServerCertificate(params);
                case "ListServerCertificateTags" -> handleListServerCertificateTags(params);
                case "TagServerCertificate" -> handleTagServerCertificate(params);
                case "UntagServerCertificate" -> handleUntagServerCertificate(params);
                case "GetAccountSummary" -> handleGetAccountSummary();
                case "GetAccountAuthorizationDetails" -> handleGetAccountAuthorizationDetails(params);
                case "GenerateCredentialReport" -> handleGenerateCredentialReport();
                case "GetCredentialReport" -> handleGetCredentialReport();
                case "GenerateServiceLastAccessedDetails" -> handleGenerateServiceLastAccessedDetails(params);
                case "GetServiceLastAccessedDetails" -> handleGetServiceLastAccessedDetails(params);
                case "GetServiceLastAccessedDetailsWithEntities" -> handleGetServiceLastAccessedDetailsWithEntities(params);
                case "ListPoliciesGrantingServiceAccess" -> handleListPoliciesGrantingServiceAccess(params);
                case "GetContextKeysForCustomPolicy" -> handleGetContextKeysForCustomPolicy(params);
                case "GetContextKeysForPrincipalPolicy" -> handleGetContextKeysForPrincipalPolicy(params);
                case "SimulateCustomPolicy" -> handleSimulateCustomPolicy(params);
                case "GetAccessKeyLastUsed" -> handleGetAccessKeyLastUsed(params);
                case "TagInstanceProfile" -> handleTagInstanceProfile(params);
                case "UntagInstanceProfile" -> handleUntagInstanceProfile(params);
                case "ListInstanceProfileTags" -> handleListInstanceProfileTags(params);
                default -> null;
            });
        } catch (AwsException e) {
            return Optional.of(AwsQueryResponse.error(e.getErrorCode(), e.getMessage(),
                    AwsNamespaces.IAM, e.getHttpStatus()));
        }
    }

    private Response handleCreateAccountAlias(MultivaluedMap<String, String> params) {
        extra.createAccountAlias(params.getFirst("AccountAlias"));
        return okNoResult("CreateAccountAlias");
    }

    private Response handleDeleteAccountAlias(MultivaluedMap<String, String> params) {
        extra.deleteAccountAlias(params.getFirst("AccountAlias"));
        return okNoResult("DeleteAccountAlias");
    }

    private Response handleListAccountAliases() {
        var xml = new XmlBuilder().start("AccountAliases");
        extra.getAccountAlias().ifPresent(alias -> xml.elem("member", alias));
        xml.end("AccountAliases").elem("IsTruncated", false);
        return ok("ListAccountAliases", xml.build());
    }

    private Response handleGetAccountPasswordPolicy() {
        AccountPasswordPolicy policy = extra.getAccountPasswordPolicy();
        String result = new XmlBuilder().start("PasswordPolicy")
                .raw(passwordPolicyXml(policy))
                .end("PasswordPolicy").build();
        return ok("GetAccountPasswordPolicy", result);
    }

    private Response handleUpdateAccountPasswordPolicy(MultivaluedMap<String, String> params) {
        AccountPasswordPolicy policy = new AccountPasswordPolicy();
        policy.setMinimumPasswordLength(getInteger(params, "MinimumPasswordLength"));
        policy.setRequireSymbols(getBoolean(params, "RequireSymbols"));
        policy.setRequireNumbers(getBoolean(params, "RequireNumbers"));
        policy.setRequireUppercaseCharacters(getBoolean(params, "RequireUppercaseCharacters"));
        policy.setRequireLowercaseCharacters(getBoolean(params, "RequireLowercaseCharacters"));
        policy.setAllowUsersToChangePassword(getBoolean(params, "AllowUsersToChangePassword"));
        policy.setMaxPasswordAge(getInteger(params, "MaxPasswordAge"));
        policy.setPasswordReusePrevention(getInteger(params, "PasswordReusePrevention"));
        policy.setHardExpiry(getBoolean(params, "HardExpiry"));
        extra.updateAccountPasswordPolicy(policy);
        return okNoResult("UpdateAccountPasswordPolicy");
    }

    private Response handleDeleteAccountPasswordPolicy() {
        extra.deleteAccountPasswordPolicy();
        return okNoResult("DeleteAccountPasswordPolicy");
    }

    private Response handleCreateLoginProfile(MultivaluedMap<String, String> params) {
        LoginProfile profile = extra.createLoginProfile(
                params.getFirst("UserName"),
                params.getFirst("Password"),
                getBoolean(params, "PasswordResetRequired"));
        return ok("CreateLoginProfile", loginProfileXml(profile));
    }

    private Response handleGetLoginProfile(MultivaluedMap<String, String> params) {
        return ok("GetLoginProfile", loginProfileXml(extra.getLoginProfile(params.getFirst("UserName"))));
    }

    private Response handleUpdateLoginProfile(MultivaluedMap<String, String> params) {
        extra.updateLoginProfile(
                params.getFirst("UserName"),
                params.getFirst("Password"),
                params.containsKey("PasswordResetRequired")
                        ? getBoolean(params, "PasswordResetRequired") : null);
        return okNoResult("UpdateLoginProfile");
    }

    private Response handleDeleteLoginProfile(MultivaluedMap<String, String> params) {
        extra.deleteLoginProfile(params.getFirst("UserName"));
        return okNoResult("DeleteLoginProfile");
    }

    private Response handleCreateVirtualMfaDevice(MultivaluedMap<String, String> params) {
        VirtualMfaDevice device = extra.createVirtualMfaDevice(
                params.getFirst("VirtualMFADeviceName"),
                params.getFirst("Path"),
                extractTags(params));
        String result = new XmlBuilder().start("VirtualMFADevice")
                .raw(virtualMfaXml(device, true))
                .end("VirtualMFADevice").build();
        return ok("CreateVirtualMFADevice", result);
    }

    private Response handleListVirtualMfaDevices(MultivaluedMap<String, String> params) {
        var xml = new XmlBuilder().start("VirtualMFADevices");
        for (VirtualMfaDevice device : extra.listVirtualMfaDevices(params.getFirst("AssignmentStatus"))) {
            xml.start("member").raw(virtualMfaXml(device, false)).end("member");
        }
        xml.end("VirtualMFADevices").elem("IsTruncated", false);
        return ok("ListVirtualMFADevices", xml.build());
    }

    private Response handleGetMfaDevice(MultivaluedMap<String, String> params) {
        VirtualMfaDevice device = extra.getVirtualMfaDevice(params.getFirst("SerialNumber"));
        String result = new XmlBuilder()
                .elem("UserName", device.getUserName())
                .elem("SerialNumber", device.getSerialNumber())
                .elem("EnableDate", iso(device.getEnableDate()))
                .build();
        return ok("GetMFADevice", result);
    }

    private Response handleListMfaDevices(MultivaluedMap<String, String> params) {
        String userName = params.getFirst("UserName");
        var xml = new XmlBuilder().start("MFADevices");
        extra.listVirtualMfaDevices("Assigned").stream()
                .filter(d -> userName == null || userName.equals(d.getUserName()))
                .forEach(d -> xml.start("member")
                        .elem("UserName", d.getUserName())
                        .elem("SerialNumber", d.getSerialNumber())
                        .elem("EnableDate", iso(d.getEnableDate()))
                        .end("member"));
        xml.end("MFADevices").elem("IsTruncated", false);
        return ok("ListMFADevices", xml.build());
    }

    private Response handleEnableMfaDevice(MultivaluedMap<String, String> params) {
        extra.enableMfaDevice(params.getFirst("UserName"), params.getFirst("SerialNumber"));
        return okNoResult("EnableMFADevice");
    }

    private Response handleDeactivateMfaDevice(MultivaluedMap<String, String> params) {
        extra.deactivateMfaDevice(params.getFirst("UserName"), params.getFirst("SerialNumber"));
        return okNoResult("DeactivateMFADevice");
    }

    private Response handleDeleteVirtualMfaDevice(MultivaluedMap<String, String> params) {
        extra.deleteVirtualMfaDevice(params.getFirst("SerialNumber"));
        return okNoResult("DeleteVirtualMFADevice");
    }

    private Response handleListMfaDeviceTags(MultivaluedMap<String, String> params) {
        return ok("ListMFADeviceTags", tagsResult(extra.listMfaDeviceTags(params.getFirst("SerialNumber"))));
    }

    private Response handleTagMfaDevice(MultivaluedMap<String, String> params) {
        extra.tagMfaDevice(params.getFirst("SerialNumber"), extractTags(params));
        return okNoResult("TagMFADevice");
    }

    private Response handleUntagMfaDevice(MultivaluedMap<String, String> params) {
        extra.untagMfaDevice(params.getFirst("SerialNumber"), extractTagKeys(params));
        return okNoResult("UntagMFADevice");
    }

    private Response handleCreateSamlProvider(MultivaluedMap<String, String> params) {
        SamlProvider provider = extra.createSamlProvider(
                params.getFirst("Name"),
                params.getFirst("SAMLMetadataDocument"),
                params.getFirst("AssertionEncryptionMode"),
                extractTags(params));
        String result = new XmlBuilder()
                .elem("SAMLProviderArn", provider.getArn())
                .start("Tags").raw(tagsXml(provider.getTags())).end("Tags")
                .build();
        return ok("CreateSAMLProvider", result);
    }

    private Response handleGetSamlProvider(MultivaluedMap<String, String> params) {
        SamlProvider provider = extra.getSamlProvider(params.getFirst("SAMLProviderArn"));
        String result = new XmlBuilder()
                .elem("SAMLProviderUUID", provider.getUuid())
                .elem("SAMLMetadataDocument", provider.getMetadataDocument())
                .elem("CreateDate", iso(provider.getCreateDate()))
                .elem("AssertionEncryptionMode", provider.getAssertionEncryptionMode())
                .start("Tags").raw(tagsXml(provider.getTags())).end("Tags")
                .build();
        return ok("GetSAMLProvider", result);
    }

    private Response handleUpdateSamlProvider(MultivaluedMap<String, String> params) {
        extra.updateSamlProvider(
                params.getFirst("SAMLProviderArn"),
                params.getFirst("SAMLMetadataDocument"),
                params.getFirst("AssertionEncryptionMode"));
        return ok("UpdateSAMLProvider", "");
    }

    private Response handleDeleteSamlProvider(MultivaluedMap<String, String> params) {
        extra.deleteSamlProvider(params.getFirst("SAMLProviderArn"));
        return okNoResult("DeleteSAMLProvider");
    }

    private Response handleListSamlProviders() {
        var xml = new XmlBuilder().start("SAMLProviderList");
        for (SamlProvider provider : extra.listSamlProviders()) {
            xml.start("member")
                    .elem("Arn", provider.getArn())
                    .elem("CreateDate", iso(provider.getCreateDate()))
                    .end("member");
        }
        xml.end("SAMLProviderList");
        return ok("ListSAMLProviders", xml.build());
    }

    private Response handleListSamlProviderTags(MultivaluedMap<String, String> params) {
        return ok("ListSAMLProviderTags", tagsResult(extra.listSamlProviderTags(params.getFirst("SAMLProviderArn"))));
    }

    private Response handleTagSamlProvider(MultivaluedMap<String, String> params) {
        extra.tagSamlProvider(params.getFirst("SAMLProviderArn"), extractTags(params));
        return okNoResult("TagSAMLProvider");
    }

    private Response handleUntagSamlProvider(MultivaluedMap<String, String> params) {
        extra.untagSamlProvider(params.getFirst("SAMLProviderArn"), extractTagKeys(params));
        return okNoResult("UntagSAMLProvider");
    }

    private Response handleCreateOidcProvider(MultivaluedMap<String, String> params) {
        OidcProvider provider = extra.createOidcProvider(
                params.getFirst("Url"),
                extractIndexed(params, "ClientIDList.member"),
                extractIndexed(params, "ThumbprintList.member"),
                extractTags(params));
        String result = new XmlBuilder()
                .elem("OpenIDConnectProviderArn", provider.getArn())
                .start("Tags").raw(tagsXml(provider.getTags())).end("Tags")
                .build();
        return ok("CreateOpenIDConnectProvider", result);
    }

    private Response handleGetOidcProvider(MultivaluedMap<String, String> params) {
        OidcProvider provider = extra.getOidcProvider(params.getFirst("OpenIDConnectProviderArn"));
        var xml = new XmlBuilder()
                .elem("Url", provider.getUrl())
                .start("ClientIDList");
        for (String clientId : provider.getClientIds()) {
            xml.elem("member", clientId);
        }
        xml.end("ClientIDList").start("ThumbprintList");
        for (String thumbprint : provider.getThumbprints()) {
            xml.elem("member", thumbprint);
        }
        xml.end("ThumbprintList")
                .elem("CreateDate", iso(provider.getCreateDate()))
                .start("Tags").raw(tagsXml(provider.getTags())).end("Tags");
        return ok("GetOpenIDConnectProvider", xml.build());
    }

    private Response handleDeleteOidcProvider(MultivaluedMap<String, String> params) {
        extra.deleteOidcProvider(params.getFirst("OpenIDConnectProviderArn"));
        return okNoResult("DeleteOpenIDConnectProvider");
    }

    private Response handleListOidcProviders() {
        var xml = new XmlBuilder().start("OpenIDConnectProviderList");
        for (OidcProvider provider : extra.listOidcProviders()) {
            xml.start("member").elem("Arn", provider.getArn()).end("member");
        }
        xml.end("OpenIDConnectProviderList");
        return ok("ListOpenIDConnectProviders", xml.build());
    }

    private Response handleAddOidcClientId(MultivaluedMap<String, String> params) {
        extra.addOidcClientId(params.getFirst("OpenIDConnectProviderArn"), params.getFirst("ClientID"));
        return okNoResult("AddClientIDToOpenIDConnectProvider");
    }

    private Response handleRemoveOidcClientId(MultivaluedMap<String, String> params) {
        extra.removeOidcClientId(params.getFirst("OpenIDConnectProviderArn"), params.getFirst("ClientID"));
        return okNoResult("RemoveClientIDFromOpenIDConnectProvider");
    }

    private Response handleUpdateOidcThumbprints(MultivaluedMap<String, String> params) {
        extra.updateOidcThumbprints(
                params.getFirst("OpenIDConnectProviderArn"),
                extractIndexed(params, "ThumbprintList.member"));
        return okNoResult("UpdateOpenIDConnectProviderThumbprint");
    }

    private Response handleListOidcProviderTags(MultivaluedMap<String, String> params) {
        return ok("ListOpenIDConnectProviderTags",
                tagsResult(extra.listOidcProviderTags(params.getFirst("OpenIDConnectProviderArn"))));
    }

    private Response handleTagOidcProvider(MultivaluedMap<String, String> params) {
        extra.tagOidcProvider(params.getFirst("OpenIDConnectProviderArn"), extractTags(params));
        return okNoResult("TagOpenIDConnectProvider");
    }

    private Response handleUntagOidcProvider(MultivaluedMap<String, String> params) {
        extra.untagOidcProvider(params.getFirst("OpenIDConnectProviderArn"), extractTagKeys(params));
        return okNoResult("UntagOpenIDConnectProvider");
    }

    private Response handleUploadSshPublicKey(MultivaluedMap<String, String> params) {
        SshPublicKey key = extra.uploadSshPublicKey(
                params.getFirst("UserName"), params.getFirst("SSHPublicKeyBody"));
        String result = new XmlBuilder().start("SSHPublicKey").raw(sshXml(key)).end("SSHPublicKey").build();
        return ok("UploadSSHPublicKey", result);
    }

    private Response handleGetSshPublicKey(MultivaluedMap<String, String> params) {
        SshPublicKey key = extra.getSshPublicKey(
                params.getFirst("UserName"), params.getFirst("SSHPublicKeyId"));
        String result = new XmlBuilder().start("SSHPublicKey").raw(sshXml(key)).end("SSHPublicKey").build();
        return ok("GetSSHPublicKey", result);
    }

    private Response handleListSshPublicKeys(MultivaluedMap<String, String> params) {
        var xml = new XmlBuilder().start("SSHPublicKeys");
        for (SshPublicKey key : extra.listSshPublicKeys(params.getFirst("UserName"))) {
            xml.start("member")
                    .elem("UserName", key.getUserName())
                    .elem("SSHPublicKeyId", key.getSshPublicKeyId())
                    .elem("Status", key.getStatus())
                    .elem("UploadDate", iso(key.getUploadDate()))
                    .end("member");
        }
        xml.end("SSHPublicKeys").elem("IsTruncated", false);
        return ok("ListSSHPublicKeys", xml.build());
    }

    private Response handleUpdateSshPublicKey(MultivaluedMap<String, String> params) {
        extra.updateSshPublicKey(params.getFirst("UserName"),
                params.getFirst("SSHPublicKeyId"), params.getFirst("Status"));
        return okNoResult("UpdateSSHPublicKey");
    }

    private Response handleDeleteSshPublicKey(MultivaluedMap<String, String> params) {
        extra.deleteSshPublicKey(params.getFirst("UserName"), params.getFirst("SSHPublicKeyId"));
        return okNoResult("DeleteSSHPublicKey");
    }

    private Response handleUploadSigningCertificate(MultivaluedMap<String, String> params) {
        SigningCertificate cert = extra.uploadSigningCertificate(
                params.getFirst("UserName"), params.getFirst("CertificateBody"));
        String result = new XmlBuilder().start("Certificate").raw(signingXml(cert)).end("Certificate").build();
        return ok("UploadSigningCertificate", result);
    }

    private Response handleListSigningCertificates(MultivaluedMap<String, String> params) {
        var xml = new XmlBuilder().start("Certificates");
        for (SigningCertificate cert : extra.listSigningCertificates(params.getFirst("UserName"))) {
            xml.start("member").raw(signingXml(cert)).end("member");
        }
        xml.end("Certificates").elem("IsTruncated", false);
        return ok("ListSigningCertificates", xml.build());
    }

    private Response handleUpdateSigningCertificate(MultivaluedMap<String, String> params) {
        extra.updateSigningCertificate(params.getFirst("UserName"),
                params.getFirst("CertificateId"), params.getFirst("Status"));
        return okNoResult("UpdateSigningCertificate");
    }

    private Response handleDeleteSigningCertificate(MultivaluedMap<String, String> params) {
        extra.deleteSigningCertificate(params.getFirst("UserName"), params.getFirst("CertificateId"));
        return okNoResult("DeleteSigningCertificate");
    }

    private Response handleCreateServiceSpecificCredential(MultivaluedMap<String, String> params) {
        ServiceSpecificCredential cred = extra.createServiceSpecificCredential(
                params.getFirst("UserName"),
                params.getFirst("ServiceName"),
                getInteger(params, "CredentialAgeDays"));
        String result = new XmlBuilder().start("ServiceSpecificCredential")
                .raw(serviceCredXml(cred, true))
                .end("ServiceSpecificCredential").build();
        return ok("CreateServiceSpecificCredential", result);
    }

    private Response handleListServiceSpecificCredentials(MultivaluedMap<String, String> params) {
        var xml = new XmlBuilder().start("ServiceSpecificCredentials");
        for (ServiceSpecificCredential cred : extra.listServiceSpecificCredentials(
                params.getFirst("UserName"), params.getFirst("ServiceName"))) {
            xml.start("member").raw(serviceCredXml(cred, false)).end("member");
        }
        xml.end("ServiceSpecificCredentials").elem("IsTruncated", false);
        return ok("ListServiceSpecificCredentials", xml.build());
    }

    private Response handleUpdateServiceSpecificCredential(MultivaluedMap<String, String> params) {
        extra.updateServiceSpecificCredential(params.getFirst("UserName"),
                params.getFirst("ServiceSpecificCredentialId"), params.getFirst("Status"));
        return okNoResult("UpdateServiceSpecificCredential");
    }

    private Response handleDeleteServiceSpecificCredential(MultivaluedMap<String, String> params) {
        extra.deleteServiceSpecificCredential(params.getFirst("UserName"),
                params.getFirst("ServiceSpecificCredentialId"));
        return okNoResult("DeleteServiceSpecificCredential");
    }

    private Response handleUploadServerCertificate(MultivaluedMap<String, String> params) {
        ServerCertificate cert = extra.uploadServerCertificate(
                params.getFirst("ServerCertificateName"),
                params.getFirst("Path"),
                params.getFirst("CertificateBody"),
                params.getFirst("CertificateChain"),
                params.getFirst("PrivateKey"),
                extractTags(params));
        String result = new XmlBuilder().start("ServerCertificateMetadata")
                .raw(serverCertMetadataXml(cert))
                .end("ServerCertificateMetadata").build();
        return ok("UploadServerCertificate", result);
    }

    private Response handleGetServerCertificate(MultivaluedMap<String, String> params) {
        ServerCertificate cert = extra.getServerCertificate(params.getFirst("ServerCertificateName"));
        String result = new XmlBuilder().start("ServerCertificate")
                .start("ServerCertificateMetadata").raw(serverCertMetadataXml(cert)).end("ServerCertificateMetadata")
                .elem("CertificateBody", cert.getCertificateBody())
                .elem("CertificateChain", cert.getCertificateChain())
                .start("Tags").raw(tagsXml(cert.getTags())).end("Tags")
                .end("ServerCertificate").build();
        return ok("GetServerCertificate", result);
    }

    private Response handleListServerCertificates(MultivaluedMap<String, String> params) {
        var xml = new XmlBuilder().start("ServerCertificateMetadataList");
        for (ServerCertificate cert : extra.listServerCertificates(params.getFirst("PathPrefix"))) {
            xml.start("member").raw(serverCertMetadataXml(cert)).end("member");
        }
        xml.end("ServerCertificateMetadataList").elem("IsTruncated", false);
        return ok("ListServerCertificates", xml.build());
    }

    private Response handleDeleteServerCertificate(MultivaluedMap<String, String> params) {
        extra.deleteServerCertificate(params.getFirst("ServerCertificateName"));
        return okNoResult("DeleteServerCertificate");
    }

    private Response handleListServerCertificateTags(MultivaluedMap<String, String> params) {
        return ok("ListServerCertificateTags",
                tagsResult(extra.listServerCertificateTags(params.getFirst("ServerCertificateName"))));
    }

    private Response handleTagServerCertificate(MultivaluedMap<String, String> params) {
        extra.tagServerCertificate(params.getFirst("ServerCertificateName"), extractTags(params));
        return okNoResult("TagServerCertificate");
    }

    private Response handleUntagServerCertificate(MultivaluedMap<String, String> params) {
        extra.untagServerCertificate(params.getFirst("ServerCertificateName"), extractTagKeys(params));
        return okNoResult("UntagServerCertificate");
    }

    private Response handleGetAccountSummary() {
        IamExtendedService.AccountCounts counts = extra.accountCounts();
        var xml = new XmlBuilder().start("SummaryMap");
        summary(xml, "Users", counts.users());
        summary(xml, "UsersQuota", 5000);
        summary(xml, "Groups", counts.groups());
        summary(xml, "GroupsQuota", 300);
        summary(xml, "Roles", counts.roles());
        summary(xml, "RolesQuota", 1000);
        summary(xml, "Policies", counts.policies());
        summary(xml, "PoliciesQuota", 1500);
        summary(xml, "InstanceProfiles", counts.instanceProfiles());
        summary(xml, "InstanceProfilesQuota", 1000);
        summary(xml, "ServerCertificates", counts.serverCertificates());
        summary(xml, "ServerCertificatesQuota", 20);
        summary(xml, "Providers", counts.providers());
        summary(xml, "MFADevices", counts.mfaDevices());
        summary(xml, "MFADevicesInUse", counts.mfaInUse());
        summary(xml, "AccountMFAEnabled", 0);
        summary(xml, "AccountAccessKeysPresent", 1);
        summary(xml, "AccountPasswordPresent", 0);
        summary(xml, "VersionsPerPolicyQuota", 5);
        summary(xml, "PolicyVersionsInUseQuota", 10000);
        summary(xml, "GlobalEndpointTokenVersion", 2);
        xml.end("SummaryMap");
        return ok("GetAccountSummary", xml.build());
    }

    private Response handleGetAccountAuthorizationDetails(MultivaluedMap<String, String> params) {
        List<String> filters = extractIndexed(params, "Filter.member");
        boolean all = filters.isEmpty();
        var xml = new XmlBuilder();
        if (all || filters.contains("User")) {
            xml.start("UserDetailList");
            for (IamUser user : iamService.listUsers("/")) {
                xml.start("member")
                        .elem("Path", user.getPath())
                        .elem("UserName", user.getUserName())
                        .elem("UserId", user.getUserId())
                        .elem("Arn", user.getArn())
                        .elem("CreateDate", iso(user.getCreateDate()))
                        .start("UserPolicyList");
                user.getInlinePolicies().forEach((name, document) -> xml.start("member")
                        .elem("PolicyName", name)
                        .elem("PolicyDocument", document)
                        .end("member"));
                xml.end("UserPolicyList").start("GroupList");
                user.getGroupNames().forEach(g -> xml.elem("member", g));
                xml.end("GroupList").start("AttachedManagedPolicies");
                attachedPolicies(xml, user.getAttachedPolicyArns());
                xml.end("AttachedManagedPolicies").end("member");
            }
            xml.end("UserDetailList");
        }
        if (all || filters.contains("Group")) {
            xml.start("GroupDetailList");
            for (IamGroup group : iamService.listGroups("/")) {
                xml.start("member")
                        .elem("Path", group.getPath())
                        .elem("GroupName", group.getGroupName())
                        .elem("GroupId", group.getGroupId())
                        .elem("Arn", group.getArn())
                        .elem("CreateDate", iso(group.getCreateDate()))
                        .start("GroupPolicyList");
                group.getInlinePolicies().forEach((name, document) -> xml.start("member")
                        .elem("PolicyName", name)
                        .elem("PolicyDocument", document)
                        .end("member"));
                xml.end("GroupPolicyList").start("AttachedManagedPolicies");
                attachedPolicies(xml, group.getAttachedPolicyArns());
                xml.end("AttachedManagedPolicies").end("member");
            }
            xml.end("GroupDetailList");
        }
        if (all || filters.contains("Role")) {
            xml.start("RoleDetailList");
            for (IamRole role : iamService.listRoles("/")) {
                xml.start("member")
                        .elem("Path", role.getPath())
                        .elem("RoleName", role.getRoleName())
                        .elem("RoleId", role.getRoleId())
                        .elem("Arn", role.getArn())
                        .elem("CreateDate", iso(role.getCreateDate()))
                        .elem("AssumeRolePolicyDocument", role.getAssumeRolePolicyDocument())
                        .start("InstanceProfileList");
                for (InstanceProfile profile : iamService.listInstanceProfilesForRole(role.getRoleName())) {
                    xml.start("member")
                            .elem("InstanceProfileName", profile.getInstanceProfileName())
                            .elem("InstanceProfileId", profile.getInstanceProfileId())
                            .elem("Arn", profile.getArn())
                            .elem("Path", profile.getPath())
                            .elem("CreateDate", iso(profile.getCreateDate()))
                            .end("member");
                }
                xml.end("InstanceProfileList").start("RolePolicyList");
                role.getInlinePolicies().forEach((name, document) -> xml.start("member")
                        .elem("PolicyName", name)
                        .elem("PolicyDocument", document)
                        .end("member"));
                xml.end("RolePolicyList").start("AttachedManagedPolicies");
                attachedPolicies(xml, role.getAttachedPolicyArns());
                xml.end("AttachedManagedPolicies").end("member");
            }
            xml.end("RoleDetailList");
        }
        if (all || filters.contains("LocalManagedPolicy")) {
            xml.start("Policies");
            for (IamPolicy policy : iamService.listPolicies("Local", "/")) {
                xml.start("member")
                        .elem("PolicyName", policy.getPolicyName())
                        .elem("PolicyId", policy.getPolicyId())
                        .elem("Arn", policy.getArn())
                        .elem("Path", policy.getPath())
                        .elem("DefaultVersionId", policy.getDefaultVersionId())
                        .elem("AttachmentCount", (long) policy.getAttachmentCount())
                        .elem("IsAttachable", true)
                        .elem("CreateDate", iso(policy.getCreateDate()))
                        .elem("UpdateDate", iso(policy.getUpdateDate()))
                        .start("PolicyVersionList");
                for (PolicyVersion version : iamService.listPolicyVersions(policy.getArn())) {
                    xml.start("member")
                            .elem("Document", version.getDocument())
                            .elem("VersionId", version.getVersionId())
                            .elem("IsDefaultVersion", version.isDefaultVersion())
                            .elem("CreateDate", iso(version.getCreateDate()))
                            .end("member");
                }
                xml.end("PolicyVersionList").end("member");
            }
            xml.end("Policies");
        }
        xml.elem("IsTruncated", false);
        return ok("GetAccountAuthorizationDetails", xml.build());
    }

    private Response handleGenerateCredentialReport() {
        String state = extra.generateCredentialReport();
        return ok("GenerateCredentialReport", new XmlBuilder().elem("State", state).build());
    }

    private Response handleGetCredentialReport() {
        IamExtendedService.CredentialReport report = extra.getCredentialReport();
        String content = Base64.getEncoder().encodeToString(report.csv().getBytes(StandardCharsets.UTF_8));
        String result = new XmlBuilder()
                .elem("Content", content)
                .elem("ReportFormat", "text/csv")
                .elem("GeneratedTime", iso(report.generatedTime()))
                .build();
        return ok("GetCredentialReport", result);
    }

    private Response handleGenerateServiceLastAccessedDetails(MultivaluedMap<String, String> params) {
        AccessAdvisorJob job = extra.generateServiceLastAccessedDetails(
                params.getFirst("Arn"), params.getFirst("Granularity"));
        return ok("GenerateServiceLastAccessedDetails",
                new XmlBuilder().elem("JobId", job.getJobId()).build());
    }

    private Response handleGetServiceLastAccessedDetails(MultivaluedMap<String, String> params) {
        AccessAdvisorJob job = extra.getAccessAdvisorJob(params.getFirst("JobId"));
        var xml = new XmlBuilder()
                .elem("JobStatus", job.getStatus())
                .elem("JobType", job.getGranularity())
                .elem("JobCreationDate", iso(job.getCreationDate()))
                .elem("JobCompletionDate", iso(job.getCompletionDate()))
                .start("ServicesLastAccessed");
        for (String namespace : job.getServiceNamespaces()) {
            xml.start("member")
                    .elem("ServiceName", namespace)
                    .elem("ServiceNamespace", namespace)
                    .elem("TotalAuthenticatedEntities", 1L)
                    .end("member");
        }
        xml.end("ServicesLastAccessed").elem("IsTruncated", false);
        return ok("GetServiceLastAccessedDetails", xml.build());
    }

    private Response handleGetServiceLastAccessedDetailsWithEntities(MultivaluedMap<String, String> params) {
        AccessAdvisorJob job = extra.getAccessAdvisorJob(params.getFirst("JobId"));
        var xml = new XmlBuilder()
                .elem("JobStatus", job.getStatus())
                .elem("JobCreationDate", iso(job.getCreationDate()))
                .elem("JobCompletionDate", iso(job.getCompletionDate()))
                .start("EntityDetailsList");
        if (job.getEntityName() != null) {
            xml.start("member").start("EntityInfo")
                    .elem("Arn", job.getArn())
                    .elem("Name", job.getEntityName())
                    .elem("Type", job.getEntityType())
                    .elem("Id", job.getEntityId())
                    .elem("Path", job.getEntityPath())
                    .end("EntityInfo")
                    .end("member");
        }
        xml.end("EntityDetailsList").elem("IsTruncated", false);
        return ok("GetServiceLastAccessedDetailsWithEntities", xml.build());
    }

    private Response handleListPoliciesGrantingServiceAccess(MultivaluedMap<String, String> params) {
        String arn = params.getFirst("Arn");
        List<String> namespaces = extractIndexed(params, "ServiceNamespaces.member");
        var xml = new XmlBuilder().start("PoliciesGrantingServiceAccess");
        for (String namespace : namespaces) {
            xml.start("member").elem("ServiceNamespace", namespace).start("Policies");
            for (IamExtendedService.PolicyGrant grant : extra.policiesGrantingAccess(arn, namespace)) {
                xml.start("member")
                        .elem("PolicyName", grant.policyName())
                        .elem("PolicyType", grant.policyType())
                        .elem("PolicyArn", grant.policyArn())
                        .elem("EntityType", grant.entityType())
                        .elem("EntityName", grant.entityName())
                        .end("member");
            }
            xml.end("Policies").end("member");
        }
        xml.end("PoliciesGrantingServiceAccess").elem("IsTruncated", false);
        return ok("ListPoliciesGrantingServiceAccess", xml.build());
    }

    private Response handleGetContextKeysForCustomPolicy(MultivaluedMap<String, String> params) {
        List<String> keys = extra.contextKeysForDocuments(extractIndexed(params, "PolicyInputList.member"));
        return ok("GetContextKeysForCustomPolicy", contextKeysXml(keys));
    }

    private Response handleGetContextKeysForPrincipalPolicy(MultivaluedMap<String, String> params) {
        List<String> keys = extra.contextKeysForPrincipal(
                params.getFirst("PolicySourceArn"),
                extractIndexed(params, "PolicyInputList.member"));
        return ok("GetContextKeysForPrincipalPolicy", contextKeysXml(keys));
    }

    private Response handleSimulateCustomPolicy(MultivaluedMap<String, String> params) {
        List<String> documents = extractIndexed(params, "PolicyInputList.member");
        List<String> actions = extractIndexed(params, "ActionNames.member");
        List<String> resources = extractIndexed(params, "ResourceArns.member");
        if (resources.isEmpty()) {
            resources = List.of("*");
        }
        Map<String, String> context = extractContextEntries(params);
        XmlBuilder results = new XmlBuilder().start("EvaluationResults");
        for (String action : actions) {
            for (String resource : resources) {
                IamPolicyEvaluator.Decision decision =
                        policyEvaluator.simulateCustomPolicy(documents, action, resource, context);
                results.start("member")
                        .elem("EvalActionName", action)
                        .elem("EvalResourceName", resource)
                        .elem("EvalDecision", decision == IamPolicyEvaluator.Decision.ALLOW ? "allowed" : "implicitDeny")
                        .start("MatchedStatements").end("MatchedStatements")
                        .start("MissingContextValues").end("MissingContextValues")
                        .end("member");
            }
        }
        return ok("SimulateCustomPolicy", results.end("EvaluationResults").elem("IsTruncated", false).build());
    }

    private Response handleGetAccessKeyLastUsed(MultivaluedMap<String, String> params) {
        String accessKeyId = params.getFirst("AccessKeyId");
        Optional<String> userName = iamService.findUserNameByAccessKeyId(accessKeyId);
        var xml = new XmlBuilder();
        userName.ifPresent(name -> xml.elem("UserName", name));
        xml.start("AccessKeyLastUsed")
                .elem("ServiceName", "N/A")
                .elem("Region", "N/A")
                .end("AccessKeyLastUsed");
        return ok("GetAccessKeyLastUsed", xml.build());
    }

    private Response handleTagInstanceProfile(MultivaluedMap<String, String> params) {
        iamService.tagInstanceProfile(params.getFirst("InstanceProfileName"), extractTags(params));
        return okNoResult("TagInstanceProfile");
    }

    private Response handleUntagInstanceProfile(MultivaluedMap<String, String> params) {
        iamService.untagInstanceProfile(params.getFirst("InstanceProfileName"), extractTagKeys(params));
        return okNoResult("UntagInstanceProfile");
    }

    private Response handleListInstanceProfileTags(MultivaluedMap<String, String> params) {
        return ok("ListInstanceProfileTags",
                tagsResult(iamService.listInstanceProfileTags(params.getFirst("InstanceProfileName"))));
    }

    // =========================================================================
    // XML helpers
    // =========================================================================

    private static Response ok(String action, String result) {
        return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.IAM, result)).build();
    }

    private static Response okNoResult(String action) {
        return Response.ok(AwsQueryResponse.envelopeNoResult(action, AwsNamespaces.IAM)).build();
    }

    private static String passwordPolicyXml(AccountPasswordPolicy policy) {
        var xml = new XmlBuilder()
                .elem("MinimumPasswordLength", policy.getMinimumPasswordLength() == null
                        ? null : String.valueOf(policy.getMinimumPasswordLength()))
                .elem("RequireSymbols", policy.isRequireSymbols())
                .elem("RequireNumbers", policy.isRequireNumbers())
                .elem("RequireUppercaseCharacters", policy.isRequireUppercaseCharacters())
                .elem("RequireLowercaseCharacters", policy.isRequireLowercaseCharacters())
                .elem("AllowUsersToChangePassword", policy.isAllowUsersToChangePassword())
                .elem("ExpirePasswords", policy.isExpirePasswords());
        if (policy.getMaxPasswordAge() != null) {
            xml.elem("MaxPasswordAge", policy.getMaxPasswordAge().longValue());
        }
        if (policy.getPasswordReusePrevention() != null) {
            xml.elem("PasswordReusePrevention", policy.getPasswordReusePrevention().longValue());
        }
        return xml.elem("HardExpiry", policy.isHardExpiry()).build();
    }

    private static String loginProfileXml(LoginProfile profile) {
        return new XmlBuilder().start("LoginProfile")
                .elem("UserName", profile.getUserName())
                .elem("CreateDate", iso(profile.getCreateDate()))
                .elem("PasswordResetRequired", profile.isPasswordResetRequired())
                .end("LoginProfile").build();
    }

    private String virtualMfaXml(VirtualMfaDevice device, boolean includeSecrets) {
        var xml = new XmlBuilder().elem("SerialNumber", device.getSerialNumber());
        if (includeSecrets) {
            xml.elem("Base32StringSeed", device.getBase32StringSeed())
                    .elem("QRCodePNG", device.getQrCodePng());
        }
        if (device.isAssigned()) {
            try {
                IamUser user = iamService.getUser(device.getUserName());
                xml.start("User")
                        .elem("Path", user.getPath())
                        .elem("UserName", user.getUserName())
                        .elem("UserId", user.getUserId())
                        .elem("Arn", user.getArn())
                        .elem("CreateDate", iso(user.getCreateDate()))
                        .end("User")
                        .elem("EnableDate", iso(device.getEnableDate()));
            } catch (AwsException ignored) {
                xml.elem("EnableDate", iso(device.getEnableDate()));
            }
        }
        xml.start("Tags").raw(tagsXml(device.getTags())).end("Tags");
        return xml.build();
    }

    private static String sshXml(SshPublicKey key) {
        return new XmlBuilder()
                .elem("UserName", key.getUserName())
                .elem("SSHPublicKeyId", key.getSshPublicKeyId())
                .elem("Fingerprint", key.getFingerprint())
                .elem("SSHPublicKeyBody", key.getSshPublicKeyBody())
                .elem("Status", key.getStatus())
                .elem("UploadDate", iso(key.getUploadDate()))
                .build();
    }

    private static String signingXml(SigningCertificate cert) {
        return new XmlBuilder()
                .elem("UserName", cert.getUserName())
                .elem("CertificateId", cert.getCertificateId())
                .elem("CertificateBody", cert.getCertificateBody())
                .elem("Status", cert.getStatus())
                .elem("UploadDate", iso(cert.getUploadDate()))
                .build();
    }

    private static String serviceCredXml(ServiceSpecificCredential cred, boolean includeSecrets) {
        var xml = new XmlBuilder()
                .elem("CreateDate", iso(cred.getCreateDate()))
                .elem("ExpirationDate", iso(cred.getExpirationDate()))
                .elem("ServiceName", cred.getServiceName())
                .elem("ServiceUserName", cred.getServiceUserName());
        if (includeSecrets) {
            xml.elem("ServicePassword", cred.getServicePassword())
                    .elem("ServiceCredentialSecret", cred.getServiceCredentialSecret());
        }
        return xml.elem("ServiceCredentialAlias", cred.getServiceCredentialAlias())
                .elem("ServiceSpecificCredentialId", cred.getServiceSpecificCredentialId())
                .elem("UserName", cred.getUserName())
                .elem("Status", cred.getStatus())
                .build();
    }

    private static String serverCertMetadataXml(ServerCertificate cert) {
        return new XmlBuilder()
                .elem("Path", cert.getPath())
                .elem("ServerCertificateName", cert.getServerCertificateName())
                .elem("ServerCertificateId", cert.getServerCertificateId())
                .elem("Arn", cert.getArn())
                .elem("UploadDate", iso(cert.getUploadDate()))
                .elem("Expiration", iso(cert.getExpiration()))
                .build();
    }

    private static String tagsResult(Map<String, String> tags) {
        return new XmlBuilder().start("Tags").raw(tagsXml(tags)).end("Tags")
                .elem("IsTruncated", false).build();
    }

    private static String tagsXml(Map<String, String> tags) {
        var xml = new XmlBuilder();
        for (var entry : tags.entrySet()) {
            xml.start("member").elem("Key", entry.getKey()).elem("Value", entry.getValue()).end("member");
        }
        return xml.build();
    }

    private static String contextKeysXml(List<String> keys) {
        var xml = new XmlBuilder().start("ContextKeyNames");
        for (String key : keys) {
            xml.elem("member", key);
        }
        return xml.end("ContextKeyNames").build();
    }

    private void attachedPolicies(XmlBuilder xml, List<String> arns) {
        for (String arn : arns) {
            try {
                IamPolicy policy = iamService.getPolicy(arn);
                xml.start("member")
                        .elem("PolicyName", policy.getPolicyName())
                        .elem("PolicyArn", policy.getArn())
                        .end("member");
            } catch (AwsException ignored) {
                xml.start("member").elem("PolicyArn", arn).end("member");
            }
        }
    }

    private static void summary(XmlBuilder xml, String key, long value) {
        xml.start("entry").elem("key", key).elem("value", value).end("entry");
    }

    private static Map<String, String> extractTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("Tags.member." + i + ".Key");
            if (key == null) {
                break;
            }
            String value = params.getFirst("Tags.member." + i + ".Value");
            tags.put(key, value != null ? value : "");
        }
        return tags;
    }

    private static List<String> extractTagKeys(MultivaluedMap<String, String> params) {
        return extractIndexed(params, "TagKeys.member");
    }

    private static List<String> extractIndexed(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(prefix + "." + i);
            if (value == null) {
                break;
            }
            values.add(value);
        }
        return values;
    }

    private static Map<String, String> extractContextEntries(MultivaluedMap<String, String> params) {
        Map<String, String> context = new HashMap<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("ContextEntries.member." + i + ".ContextKeyName");
            if (name == null) {
                break;
            }
            String value = params.getFirst("ContextEntries.member." + i + ".ContextKeyValues.member.1");
            if (value != null) {
                context.put(name, value);
            }
        }
        return context;
    }

    private static boolean getBoolean(MultivaluedMap<String, String> params, String name) {
        return "true".equalsIgnoreCase(params.getFirst(name));
    }

    private static Integer getInteger(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
