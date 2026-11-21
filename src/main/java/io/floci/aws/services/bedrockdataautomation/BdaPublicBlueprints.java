package io.floci.aws.services.bedrockdataautomation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The AWS-managed blueprints every account can use, owned by {@code aws}
 * ({@code arn:aws:bedrock:<region>:aws:blueprint/bedrock-data-automation-public-<name>}): common document types
 * with the fields AWS's catalog gives them.
 */
final class BdaPublicBlueprints {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String[]> BLUEPRINTS = new LinkedHashMap<>();

    static {
        BLUEPRINTS.put("invoice", new String[] {"Invoice", "invoice",
            "invoice_number:The invoice number|invoice_date:The date the invoice was issued|vendor_name:The name of "
                + "the vendor issuing the invoice|total_amount:The total amount due|due_date:The date payment is due"});
        BLUEPRINTS.put("receipt", new String[] {"Receipt", "receipt",
            "merchant_name:The name of the merchant|transaction_date:The date of the purchase|total:The total paid"
                + "|tax:The tax charged"});
        BLUEPRINTS.put("bank-statement", new String[] {"Bank-Statement", "bank statement",
            "account_number:The account number|statement_period:The period the statement covers|opening_balance:The "
                + "balance at the start of the period|closing_balance:The balance at the end of the period"});
        BLUEPRINTS.put("payslip", new String[] {"Payslip", "payslip",
            "employee_name:The employee's name|pay_period:The pay period|gross_pay:The gross pay|net_pay:The net pay"});
        BLUEPRINTS.put("us-driver-license", new String[] {"US-Driver-License", "driver license",
            "name:The license holder's full name|license_number:The driver license number|date_of_birth:The holder's "
                + "date of birth|expiration_date:The date the license expires|state:The issuing state"});
        BLUEPRINTS.put("w2-form", new String[] {"W2-Form", "W-2",
            "employee_name:The employee's name|employer_name:The employer's name|wages:Wages, tips, other "
                + "compensation (box 1)|federal_income_tax_withheld:Federal income tax withheld (box 2)"});
    }

    private BdaPublicBlueprints() {
    }

    static List<ObjectNode> all(String region) {
        List<ObjectNode> out = new ArrayList<>();
        BLUEPRINTS.keySet().forEach(id -> out.add(blueprint(region, id)));
        return out;
    }

    static Optional<ObjectNode> find(String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return Optional.empty();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String prefix = "blueprint/bedrock-data-automation-public-";
        if (!"aws".equals(parsed.accountId()) || !parsed.resource().startsWith(prefix)) { // partition-literal: "aws" is the account that owns AWS-managed blueprints
            return Optional.empty();
        }
        String id = parsed.resource().substring(prefix.length());
        return BLUEPRINTS.containsKey(id) ? Optional.of(blueprint(parsed.region(), id)) : Optional.empty();
    }

    private static ObjectNode blueprint(String region, String id) {
        String[] spec = BLUEPRINTS.get(id);
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("description", "AWS-managed blueprint for " + spec[1] + " documents.");
        schema.put("class", spec[1]);
        schema.put("type", "object");
        schema.putObject("definitions");
        ObjectNode properties = schema.putObject("properties");
        for (String field : spec[2].split("\\|")) {
            String[] parts = field.split(":", 2);
            properties.putObject(parts[0]).put("type", "string").put("inferenceType", "explicit")
                    .put("instruction", parts[1]);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("blueprintArn", AwsArnUtils.Arn.of("bedrock", region, "aws",
                "blueprint/bedrock-data-automation-public-" + id).toString());
        out.put("schema", schema.toString());
        out.put("type", "DOCUMENT");
        out.put("creationTime", 1_730_419_200.0);
        out.put("lastModifiedTime", 1_730_419_200.0);
        out.put("blueprintName", spec[0]);
        out.put("blueprintStage", BdaService.STAGE_LIVE);
        return out;
    }
}
