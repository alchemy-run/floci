package io.floci.aws.services.acmpca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * AWS Private CA JSON 1.1 handler, under the {@code ACMPrivateCA.} target prefix. Requests are
 * checked against the constraints vendored from botocore by {@code tools/aws/regen_acmpca_shapes.py}.
 */
@ApplicationScoped
public class AcmPcaJsonHandler {
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/acm-pca-request-shapes.json", "Private CA request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return validation(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return validation(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return validation(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return validation(path + " is required.");
                }
            });

    private final AcmPcaService service;
    private final ObjectMapper mapper;

    @Inject
    public AcmPcaJsonHandler(AcmPcaService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            if (request != null && request.isObject()) {
                SHAPES.check(action, request);
            }
            Object body = switch (action) {
                case "CreateCertificateAuthority" -> service.create(request, region);
                case "DescribeCertificateAuthority" -> service.describe(request);
                case "ListCertificateAuthorities" -> service.list(request, region);
                case "UpdateCertificateAuthority" -> empty(() -> service.update(request));
                case "DeleteCertificateAuthority" -> empty(() -> service.delete(request));
                case "RestoreCertificateAuthority" -> empty(() -> service.restore(request));
                case "GetCertificateAuthorityCsr" -> service.csr(request);
                case "GetCertificateAuthorityCertificate" -> service.caCertificate(request);
                case "ImportCertificateAuthorityCertificate" -> empty(() -> service.importCertificate(request));
                case "IssueCertificate" -> service.issue(request);
                case "GetCertificate" -> service.getCertificate(request);
                case "RevokeCertificate" -> empty(() -> service.revoke(request));
                case "CreateCertificateAuthorityAuditReport" -> service.createAuditReport(request);
                case "DescribeCertificateAuthorityAuditReport" -> service.describeAuditReport(request);
                case "CreatePermission" -> empty(() -> service.createPermission(request));
                case "DeletePermission" -> empty(() -> service.deletePermission(request));
                case "ListPermissions" -> service.listPermissions(request);
                case "PutPolicy" -> empty(() -> service.putPolicy(request));
                case "GetPolicy" -> service.getPolicy(request);
                case "DeletePolicy" -> empty(() -> service.deletePolicy(request));
                case "TagCertificateAuthority" -> empty(() -> service.tag(request));
                case "UntagCertificateAuthority" -> empty(() -> service.untag(request));
                case "ListTags" -> service.listTags(request);
                default -> null;
            };
            if (body == null) {
                return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                        "Operation " + action + " is not supported.")).build();
            }
            return Response.ok(body).build();
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Object empty(Runnable action) {
        action.run();
        return mapper.createObjectNode();
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
