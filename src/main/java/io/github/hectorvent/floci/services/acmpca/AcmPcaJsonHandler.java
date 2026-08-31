package io.github.hectorvent.floci.services.acmpca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acmpca.model.AuditReport;
import io.github.hectorvent.floci.services.acmpca.model.CertificateAuthority;
import io.github.hectorvent.floci.services.acmpca.model.IssuedCertificate;
import io.github.hectorvent.floci.services.acmpca.model.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for AWS Private CA. Dispatched from {@code AwsJson11Controller}
 * under the {@code ACMPrivateCA.} target prefix.
 */
@ApplicationScoped
public class AcmPcaJsonHandler {

    private final AcmPcaService service;
    private final ObjectMapper objectMapper;

    @Inject
    public AcmPcaJsonHandler(AcmPcaService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateCertificateAuthority" -> handleCreate(request, region);
            case "DescribeCertificateAuthority" -> handleDescribe(request);
            case "ListCertificateAuthorities" -> handleList(request);
            case "UpdateCertificateAuthority" -> handleUpdate(request);
            case "DeleteCertificateAuthority" -> handleDelete(request);
            case "ListTags" -> handleListTags(request);
            case "TagCertificateAuthority" -> handleTag(request);
            case "UntagCertificateAuthority" -> handleUntag(request);
            case "CreatePermission" -> handleCreatePermission(request);
            case "DeletePermission" -> handleDeletePermission(request);
            case "ListPermissions" -> handleListPermissions(request);
            case "PutPolicy" -> handlePutPolicy(request);
            case "GetPolicy" -> handleGetPolicy(request);
            case "DeletePolicy" -> handleDeletePolicy(request);
            case "GetCertificateAuthorityCsr" -> handleGetCsr(request);
            case "IssueCertificate" -> handleIssue(request);
            case "GetCertificate" -> handleGetCertificate(request);
            case "ImportCertificateAuthorityCertificate" -> handleImport(request);
            case "GetCertificateAuthorityCertificate" -> handleGetCaCertificate(request);
            case "RevokeCertificate" -> handleRevoke(request);
            case "CreateCertificateAuthorityAuditReport" -> handleCreateAudit(request);
            case "DescribeCertificateAuthorityAuditReport" -> handleDescribeAudit(request);
            default -> Response.status(400)
                .entity(new AwsErrorResponse("UnknownOperationException",
                    "Operation " + action + " is not supported."))
                .build();
        };
    }

    private Response handleCreate(JsonNode request, String region) {
        Map<String, Object> configuration = asMap(request.path("CertificateAuthorityConfiguration"));
        Map<String, Object> revocation = asMap(request.path("RevocationConfiguration"));
        String type = textOrNull(request, "CertificateAuthorityType");
        String usageMode = textOrNull(request, "UsageMode");
        String security = textOrNull(request, "KeyStorageSecurityStandard");
        String idempotency = textOrNull(request, "IdempotencyToken");
        Map<String, String> tags = parseTags(request.path("Tags"));

        CertificateAuthority ca = service.createCertificateAuthority(
            configuration, revocation, type, usageMode, security, tags, idempotency, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CertificateAuthorityArn", ca.getArn());
        return Response.ok(response).build();
    }

    private Response handleDescribe(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        CertificateAuthority ca = service.describeCertificateAuthority(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("CertificateAuthority", toDetail(ca));
        return Response.ok(response).build();
    }

    private Response handleList(JsonNode request) {
        List<CertificateAuthority> cas = service.listCertificateAuthorities();
        int maxResults = request.path("MaxResults").asInt(100);
        if (maxResults <= 0) {
            maxResults = 100;
        }
        int start = 0;
        String nextToken = textOrNull(request, "NextToken");
        if (nextToken != null) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidNextTokenException", "Invalid next token.", 400);
            }
        }
        int end = Math.min(cas.size(), start + maxResults);
        ArrayNode list = objectMapper.createArrayNode();
        for (int i = start; i < end; i++) {
            list.add(toDetail(cas.get(i)));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("CertificateAuthorities", list);
        if (end < cas.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdate(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        Map<String, Object> revocation = asMap(request.path("RevocationConfiguration"));
        String status = textOrNull(request, "Status");
        service.updateCertificateAuthority(arn, revocation, status);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDelete(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        Integer days = request.hasNonNull("PermanentDeletionTimeInDays")
            ? request.get("PermanentDeletionTimeInDays").asInt()
            : null;
        service.deleteCertificateAuthority(arn, days);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTags(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        Map<String, String> tags = service.listTags(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tagsArray(tags));
        return Response.ok(response).build();
    }

    private Response handleTag(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        service.tagCertificateAuthority(arn, parseTags(request.path("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntag(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        List<String> keys = new ArrayList<>();
        JsonNode tags = request.path("Tags");
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                String key = tag.path("Key").asText(null);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        service.untagCertificateAuthority(arn, keys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreatePermission(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        String principal = textOrNull(request, "Principal");
        String sourceAccount = textOrNull(request, "SourceAccount");
        List<String> actions = new ArrayList<>();
        JsonNode actionsNode = request.path("Actions");
        if (actionsNode.isArray()) {
            for (JsonNode action : actionsNode) {
                actions.add(action.asText());
            }
        }
        service.createPermission(arn, principal, sourceAccount, actions);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeletePermission(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        String principal = textOrNull(request, "Principal");
        service.deletePermission(arn, principal);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListPermissions(JsonNode request) {
        String arn = textOrNull(request, "CertificateAuthorityArn");
        List<Permission> permissions = service.listPermissions(arn);
        ArrayNode list = objectMapper.createArrayNode();
        for (Permission permission : permissions) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("CertificateAuthorityArn", permission.getCertificateAuthorityArn());
            node.put("CreatedAt", permission.getCreatedAt());
            node.put("Principal", permission.getPrincipal());
            if (permission.getSourceAccount() != null) {
                node.put("SourceAccount", permission.getSourceAccount());
            }
            ArrayNode actions = objectMapper.createArrayNode();
            for (String action : permission.getActions()) {
                actions.add(action);
            }
            node.set("Actions", actions);
            if (permission.getPolicy() != null) {
                node.put("Policy", permission.getPolicy());
            }
            list.add(node);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Permissions", list);
        return Response.ok(response).build();
    }

    private Response handlePutPolicy(JsonNode request) {
        service.putPolicy(textOrNull(request, "ResourceArn"), textOrNull(request, "Policy"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetPolicy(JsonNode request) {
        String policy = service.getPolicy(textOrNull(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Policy", policy);
        return Response.ok(response).build();
    }

    private Response handleDeletePolicy(JsonNode request) {
        service.deletePolicy(textOrNull(request, "ResourceArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetCsr(JsonNode request) {
        String csr = service.getCertificateAuthorityCsr(textOrNull(request, "CertificateAuthorityArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Csr", csr);
        return Response.ok(response).build();
    }

    private Response handleIssue(JsonNode request) {
        IssuedCertificate issued = service.issueCertificate(
            textOrNull(request, "CertificateAuthorityArn"),
            decodeBlob(textOrNull(request, "Csr")),
            textOrNull(request, "SigningAlgorithm"),
            textOrNull(request, "TemplateArn"),
            asMap(request.path("Validity")));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("CertificateArn", issued.getArn());
        return Response.ok(response).build();
    }

    private Response handleGetCertificate(JsonNode request) {
        IssuedCertificate issued = service.getCertificate(
            textOrNull(request, "CertificateAuthorityArn"),
            textOrNull(request, "CertificateArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Certificate", issued.getPem());
        if (issued.getChainPem() != null) {
            response.put("CertificateChain", issued.getChainPem());
        }
        return Response.ok(response).build();
    }

    private Response handleImport(JsonNode request) {
        service.importCertificateAuthorityCertificate(
            textOrNull(request, "CertificateAuthorityArn"),
            decodeBlob(textOrNull(request, "Certificate")),
            decodeBlob(textOrNull(request, "CertificateChain")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetCaCertificate(JsonNode request) {
        CertificateAuthority ca = service.getCertificateAuthorityCertificate(
            textOrNull(request, "CertificateAuthorityArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Certificate", ca.getCertificatePem());
        if (ca.getCertificateChainPem() != null) {
            response.put("CertificateChain", ca.getCertificateChainPem());
        }
        return Response.ok(response).build();
    }

    private Response handleRevoke(JsonNode request) {
        service.revokeCertificate(
            textOrNull(request, "CertificateAuthorityArn"),
            textOrNull(request, "CertificateSerial"),
            textOrNull(request, "RevocationReason"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreateAudit(JsonNode request) {
        AuditReport report = service.createAuditReport(
            textOrNull(request, "CertificateAuthorityArn"),
            textOrNull(request, "S3BucketName"),
            textOrNull(request, "AuditReportResponseFormat"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AuditReportId", report.getAuditReportId());
        response.put("S3Key", report.getS3Key());
        return Response.ok(response).build();
    }

    private Response handleDescribeAudit(JsonNode request) {
        AuditReport report = service.describeAuditReport(
            textOrNull(request, "CertificateAuthorityArn"),
            textOrNull(request, "AuditReportId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AuditReportStatus", report.getStatus());
        response.put("S3BucketName", report.getS3BucketName());
        response.put("S3Key", report.getS3Key());
        response.put("CreatedAt", report.getCreatedAt() / 1000.0);
        return Response.ok(response).build();
    }

    private ObjectNode toDetail(CertificateAuthority ca) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", ca.getArn());
        node.put("OwnerAccount", ca.getOwnerAccount());
        node.put("CreatedAt", ca.getCreatedAt());
        if (ca.getLastStateChangeAt() != null) {
            node.put("LastStateChangeAt", ca.getLastStateChangeAt());
        }
        node.put("Type", ca.getType());
        node.put("Status", ca.getStatus());
        node.put("UsageMode", ca.getUsageMode());
        node.put("KeyStorageSecurityStandard", ca.getKeyStorageSecurityStandard());
        if (ca.getSerial() != null) {
            node.put("Serial", ca.getSerial());
        }
        if (ca.getNotBefore() != null) {
            node.put("NotBefore", ca.getNotBefore());
        }
        if (ca.getNotAfter() != null) {
            node.put("NotAfter", ca.getNotAfter());
        }
        if (ca.getCertificateAuthorityConfiguration() != null
            && !ca.getCertificateAuthorityConfiguration().isEmpty()) {
            node.set("CertificateAuthorityConfiguration",
                objectMapper.valueToTree(ca.getCertificateAuthorityConfiguration()));
        }
        if (ca.getRevocationConfiguration() != null && !ca.getRevocationConfiguration().isEmpty()) {
            node.set("RevocationConfiguration",
                objectMapper.valueToTree(ca.getRevocationConfiguration()));
        }
        if (ca.getRestorableUntil() != null) {
            node.put("RestorableUntil", ca.getRestorableUntil());
        }
        return node;
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", entry.getKey());
            if (entry.getValue() != null) {
                tag.put("Value", entry.getValue());
            }
            array.add(tag);
        }
        return array;
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || !tagsNode.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagsNode) {
            String key = tag.path("Key").asText(null);
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = tag.path("Value").asText("");
            tags.put(key, value);
        }
        return tags;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    static String decodeBlob(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("-----")) {
            return value;
        }
        try {
            return new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
