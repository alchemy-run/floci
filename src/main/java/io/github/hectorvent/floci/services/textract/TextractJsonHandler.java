package io.github.hectorvent.floci.services.textract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

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

    @Inject
    public TextractJsonHandler(TextractService textractService) {
        this.textractService = textractService;
    }

    /**
     * Dispatches Textract actions received via the AwsJson11Controller.
     * Document analysis stubs ignore the document payload; adapter management
     * operations parse the JSON body.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Textract action: {0}", action);
        return switch (action) {
            case "DetectDocumentText" -> textractService.detectDocumentText();
            case "AnalyzeDocument" -> textractService.analyzeDocument();
            case "AnalyzeExpense" -> textractService.analyzeExpense(request);
            case "AnalyzeID" -> textractService.analyzeID(request);
            case "StartDocumentTextDetection" -> textractService.startDocumentTextDetection();
            case "GetDocumentTextDetection" -> textractService.getDocumentTextDetection(
                    getStringField(request, "JobId"));
            case "StartDocumentAnalysis" -> textractService.startDocumentAnalysis();
            case "GetDocumentAnalysis" -> textractService.getDocumentAnalysis(
                    getStringField(request, "JobId"));
            case "StartExpenseAnalysis" -> textractService.startExpenseAnalysis();
            case "GetExpenseAnalysis" -> textractService.getExpenseAnalysis(
                    getStringField(request, "JobId"));
            case "StartLendingAnalysis" -> textractService.startLendingAnalysis();
            case "GetLendingAnalysis" -> textractService.getLendingAnalysis(
                    getStringField(request, "JobId"));
            case "GetLendingAnalysisSummary" -> textractService.getLendingAnalysisSummary(
                    getStringField(request, "JobId"));
            case "CreateAdapter" -> textractService.createAdapter(request, region);
            case "GetAdapter" -> textractService.getAdapter(request);
            case "ListAdapters" -> textractService.listAdapters(request);
            case "UpdateAdapter" -> textractService.updateAdapter(request);
            case "DeleteAdapter" -> textractService.deleteAdapter(request);
            case "ListAdapterVersions" -> textractService.listAdapterVersions(request);
            case "GetAdapterVersion" -> textractService.getAdapterVersion(request);
            case "CreateAdapterVersion" -> textractService.createAdapterVersion(request);
            case "DeleteAdapterVersion" -> textractService.deleteAdapterVersion(request);
            case "TagResource" -> textractService.tagResource(request);
            case "UntagResource" -> textractService.untagResource(request);
            case "ListTagsForResource" -> textractService.listTagsForResource(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Textract." + action))
                    .build();
        };
    }

    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }
}
