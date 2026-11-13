package io.floci.aws.services.textract;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
/**
 * JSON 1.1 handler for Amazon Textract API operations.
 * Dispatches X-Amz-Target: Textract.* actions to {@link TextractService}.
 *
 * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Operations.html">Textract API Reference</a>
 */
@ApplicationScoped
public class TextractJsonHandler {
    private static final Logger LOG = Logger.getLogger(TextractJsonHandler.class);
    private final TextractService textractService;
    private final TextractDocuments documents;
    private final TextractAdapterService adapters;

    @Inject
    public TextractJsonHandler(TextractService textractService, TextractDocuments documents,
                               TextractAdapterService adapters) {
        this.textractService = textractService;
        this.documents = documents;
        this.adapters = adapters;
    }

    /**
     * Dispatches Textract actions received via the AwsJson11Controller: analysis and
     * async jobs to {@link TextractService}, after {@link TextractDocuments} has read
     * the document and found its mock keys, and adapters, adapter versions, and tags
     * to {@link TextractAdapterService}.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Textract action: {0}", action);
        return switch (action) {
            case "DetectDocumentText" -> textractService.detectDocumentText(document(request, "Document"));
            case "AnalyzeDocument" -> textractService.analyzeDocument(document(request, "Document"));
            case "AnalyzeExpense" -> textractService.analyzeExpense(document(request, "Document"));
            case "AnalyzeID" -> {
                List<List<String>> pages = new ArrayList<>();
                request.path("DocumentPages").forEach(page -> pages.add(documents.keys(page)));
                yield textractService.analyzeID(pages);
            }
            case "StartDocumentTextDetection" -> textractService.startDocumentTextDetection(
                    document(request, "DocumentLocation"));
            case "GetDocumentTextDetection" -> textractService.getDocumentTextDetection(
                    getStringField(request, "JobId"));
            case "StartDocumentAnalysis" -> textractService.startDocumentAnalysis(
                    document(request, "DocumentLocation"));
            case "GetDocumentAnalysis" -> textractService.getDocumentAnalysis(getStringField(request, "JobId"));
            case "StartExpenseAnalysis" -> textractService.startExpenseAnalysis(document(request, "DocumentLocation"));
            case "GetExpenseAnalysis" -> textractService.getExpenseAnalysis(getStringField(request, "JobId"));
            case "StartLendingAnalysis" -> textractService.startLendingAnalysis(document(request, "DocumentLocation"));
            case "GetLendingAnalysis" -> textractService.getLendingAnalysis(getStringField(request, "JobId"));
            case "GetLendingAnalysisSummary" -> textractService.getLendingAnalysisSummary(
                    getStringField(request, "JobId"));
            case "CreateAdapter" -> ok(adapters.createAdapter(request, region));
            case "GetAdapter" -> ok(adapters.getAdapter(request, region));
            case "UpdateAdapter" -> ok(adapters.updateAdapter(request, region));
            case "DeleteAdapter" -> ok(adapters.deleteAdapter(request, region));
            case "ListAdapters" -> ok(adapters.listAdapters(request, region));
            case "CreateAdapterVersion" -> ok(adapters.createAdapterVersion(request, region));
            case "GetAdapterVersion" -> ok(adapters.getAdapterVersion(request, region));
            case "DeleteAdapterVersion" -> ok(adapters.deleteAdapterVersion(request, region));
            case "ListAdapterVersions" -> ok(adapters.listAdapterVersions(request, region));
            case "TagResource" -> ok(adapters.tagResource(request, region));
            case "UntagResource" -> ok(adapters.untagResource(request, region));
            case "ListTagsForResource" -> ok(adapters.listTagsForResource(request, region));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Textract." + action))
                    .build();
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    /** The mock keys of the document in {@code field}, once it is known to be readable. */
    private List<String> document(JsonNode request, String field) {
        JsonNode document = request == null ? null : request.get(field);
        if (document == null || !document.isObject()) {
            throw new AwsException("InvalidParameterException", field + " is a required field.", 400);
        }
        return documents.keys(document);
    }

    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }
}
