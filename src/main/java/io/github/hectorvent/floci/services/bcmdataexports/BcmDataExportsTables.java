package io.github.hectorvent.floci.services.bcmdataexports;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static Data Exports table dictionary used by {@code ListTables} / {@code GetTable}.
 *
 * <p>Column names follow the CUR 2.0 / FOCUS table dictionary so SQL
 * {@code QueryStatement}s that round-trip through CreateExport remain valid
 * against the catalog. Schema is not evaluated at query time.
 *
 * @see <a href="https://docs.aws.amazon.com/cur/latest/userguide/table-dictionary-cur2.html">CUR 2.0 table dictionary</a>
 */
final class BcmDataExportsTables {

    private BcmDataExportsTables() {
    }

    static final String COST_AND_USAGE_REPORT = "COST_AND_USAGE_REPORT";
    static final String COST_AND_USAGE_DASHBOARD = "COST_AND_USAGE_DASHBOARD";
    static final String FOCUS_1_0_AWS = "FOCUS_1_0_AWS";

    record Column(String name, String type, String description) {
    }

    record TableProperty(String name, String defaultValue, String description, List<String> validValues) {
    }

    record TableDefinition(String name, String description, List<TableProperty> properties, List<Column> columns) {
    }

    record TableSnapshot(String tableName, String description, Map<String, String> tableProperties, List<Column> schema) {
    }

    private static final List<TableDefinition> TABLES = List.of(
            cur2(),
            dashboard(),
            focus());

    static List<TableDefinition> list() {
        return TABLES;
    }

    static TableSnapshot get(String tableName, Map<String, String> requestedProperties) {
        TableDefinition def = find(tableName);
        Map<String, String> resolved = resolveProperties(def, requestedProperties);
        List<Column> schema = new ArrayList<>(def.columns());
        if (COST_AND_USAGE_REPORT.equals(def.name())) {
            applyCur2SchemaToggles(schema, resolved);
        }
        return new TableSnapshot(def.name(), def.description(), resolved, List.copyOf(schema));
    }

    private static TableDefinition find(String tableName) {
        for (TableDefinition def : TABLES) {
            if (def.name().equals(tableName)) {
                return def;
            }
        }
        throw new AwsException("ValidationException",
                "Table " + tableName + " is not a valid table.", 400);
    }

    private static Map<String, String> resolveProperties(TableDefinition def, Map<String, String> requested) {
        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, TableProperty> byName = new LinkedHashMap<>();
        for (TableProperty property : def.properties()) {
            byName.put(property.name(), property);
            if (property.defaultValue() != null) {
                resolved.put(property.name(), property.defaultValue());
            }
        }
        if (requested != null) {
            for (Map.Entry<String, String> entry : requested.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                TableProperty spec = byName.get(entry.getKey());
                if (spec == null) {
                    throw new AwsException("ValidationException",
                            "Unknown table property " + entry.getKey() + " for table " + def.name() + ".", 400);
                }
                if (spec.validValues() != null && !spec.validValues().isEmpty()
                        && !spec.validValues().contains(entry.getValue())) {
                    throw new AwsException("ValidationException",
                            "Invalid value for table property " + entry.getKey() + ".", 400);
                }
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return resolved;
    }

    private static void applyCur2SchemaToggles(List<Column> schema, Map<String, String> properties) {
        if ("TRUE".equals(properties.get("INCLUDE_RESOURCES"))) {
            schema.add(col("line_item_resource_id", "String",
                    "The resource ID that incurred the usage, when resource-level granularity is enabled."));
        }
        if ("TRUE".equals(properties.get("INCLUDE_SPLIT_COST_ALLOCATION_DATA"))) {
            schema.add(col("split_line_item_parent_resource_id", "String",
                    "The parent resource ID for a split cost allocation line item."));
            schema.add(col("split_line_item_allocated_cost", "BigDecimal",
                    "The portion of the parent resource's cost allocated to this split line item."));
        }
        if ("TRUE".equals(properties.get("INCLUDE_MANUAL_DISCOUNT_COMPATIBILITY"))) {
            schema.removeIf(c -> "discount".equals(c.name()) || "total_discount".equals(c.name()));
        }
        if ("TRUE".equals(properties.get("INCLUDE_CAPACITY_RESERVATION_DATA"))) {
            schema.add(col("capacity_reservation_capacity_reservation_arn", "String",
                    "ARN of the capacity reservation that this line item was launched against."));
            schema.add(col("capacity_reservation_capacity_reservation_status", "String",
                    "Status of the capacity reservation for this line item."));
            schema.add(col("capacity_reservation_capacity_reservation_type", "String",
                    "Type of the capacity reservation for this line item."));
        }
        if ("TRUE".equals(properties.get("INCLUDE_IAM_PRINCIPAL_DATA"))) {
            schema.add(col("line_item_iam_principal", "String",
                    "IAM principal ARN of the caller that incurred the usage."));
        }
    }

    private static TableDefinition cur2() {
        return new TableDefinition(
                COST_AND_USAGE_REPORT,
                "Cost and Usage Report",
                List.of(
                        prop("TIME_GRANULARITY", "HOURLY",
                                "The granularity of the line-items in the table.",
                                List.of("DAILY", "MONTHLY", "HOURLY")),
                        prop("INCLUDE_MANUAL_DISCOUNT_COMPATIBILITY", "FALSE",
                                "Whether to simulate manual discounts for automated discount data.",
                                List.of("TRUE", "FALSE")),
                        prop("INCLUDE_SPLIT_COST_ALLOCATION_DATA", "FALSE",
                                "Whether to include fission columns.",
                                List.of("TRUE", "FALSE")),
                        prop("INCLUDE_RESOURCES", "FALSE",
                                "Whether to include resource IDs on line items.",
                                List.of("TRUE", "FALSE")),
                        prop("INCLUDE_CAPACITY_RESERVATION_DATA", "FALSE",
                                "Specifies whether to include capacity reservation data in the data export.",
                                List.of("TRUE", "FALSE")),
                        new TableProperty("BILLING_VIEW_ARN", null,
                                "The Amazon Resource Name (ARN) of the billing view for this data export.",
                                List.of()),
                        prop("INCLUDE_IAM_PRINCIPAL_DATA", "FALSE",
                                "Specifies whether to include Identity and Access Management (IAM) principal columns.",
                                List.of("TRUE", "FALSE"))),
                cur2Columns());
    }

    private static TableDefinition dashboard() {
        return new TableDefinition(
                COST_AND_USAGE_DASHBOARD,
                "A view over CUR for CostAndUsageDashboard",
                List.of(),
                List.of(
                        col("identity_line_item_id", "String", "Unique identifier for a line item in a given partition."),
                        col("line_item_unblended_cost", "BigDecimal", "Unblended cost of the line item."),
                        col("line_item_usage_start_date", "Timestamp", "Start date of the line item usage.")));
    }

    private static TableDefinition focus() {
        return new TableDefinition(
                FOCUS_1_0_AWS,
                "FinOps Open Cost and Usage Specification (FOCUS) 1.0 with AWS columns",
                List.of(),
                List.of(
                        col("BillingPeriodStart", "Timestamp", "Beginning of the billing period."),
                        col("BillingPeriodEnd", "Timestamp", "End of the billing period."),
                        col("ChargePeriodStart", "Timestamp", "Beginning of the charge period."),
                        col("ChargePeriodEnd", "Timestamp", "End of the charge period."),
                        col("BillingAccountId", "String", "Payer account ID."),
                        col("SubAccountId", "String", "Usage account ID."),
                        col("ServiceName", "String", "Name of the AWS service."),
                        col("Region", "String", "AWS region of the resource."),
                        col("ResourceId", "String", "Resource identifier."),
                        col("BilledCost", "BigDecimal", "Billed cost of the charge."),
                        col("EffectiveCost", "BigDecimal", "Effective cost of the charge."),
                        col("ListCost", "BigDecimal", "List cost of the charge."),
                        col("BillingCurrency", "String", "Billing currency code.")));
    }

    private static List<Column> cur2Columns() {
        return new ArrayList<>(List.of(
                col("identity_line_item_id", "String",
                        "Generated for each line item and unique in a given partition."),
                col("identity_time_interval", "String",
                        "Time interval this line item applies to, in UTC."),
                col("bill_invoice_id", "String",
                        "ID associated with a specific line item. Blank until the report is final."),
                col("bill_invoicing_entity", "String", "AWS entity that issues the invoice."),
                col("bill_billing_entity", "String", "Seller of record for the line item."),
                col("bill_bill_type", "String", "Type of bill covered by this report."),
                col("bill_payer_account_id", "String", "Account ID of the paying account."),
                col("bill_payer_account_name", "String", "Account name of the paying account."),
                col("bill_billing_period_start_date", "Timestamp", "Start date of the billing period."),
                col("bill_billing_period_end_date", "Timestamp", "End date of the billing period."),
                col("line_item_usage_account_id", "String", "Account ID that used this line item."),
                col("line_item_usage_account_name", "String", "Account name that used this line item."),
                col("line_item_line_item_type", "String", "Type of the line item (Usage, Tax, Credit, ...)."),
                col("line_item_usage_start_date", "Timestamp", "Start date of the line item usage."),
                col("line_item_usage_end_date", "Timestamp", "End date of the line item usage."),
                col("line_item_product_code", "String", "Product code of the service."),
                col("line_item_usage_type", "String", "Usage details of the line item."),
                col("line_item_operation", "String", "Specific AWS operation covered by this line item."),
                col("line_item_availability_zone", "String", "Availability zone of the line item."),
                col("line_item_usage_amount", "BigDecimal", "Amount of usage incurred."),
                col("line_item_normalization_factor", "BigDecimal", "Factor used to normalize usage."),
                col("line_item_normalized_usage_amount", "BigDecimal", "Usage amount multiplied by the normalization factor."),
                col("line_item_currency_code", "String", "Currency of the line item."),
                col("line_item_unblended_rate", "BigDecimal", "Unblended rate of the line item."),
                col("line_item_unblended_cost", "BigDecimal", "Unblended cost of the line item."),
                col("line_item_blended_rate", "BigDecimal", "Blended rate of the line item."),
                col("line_item_blended_cost", "BigDecimal", "Blended cost of the line item."),
                col("line_item_net_unblended_rate", "BigDecimal", "Net unblended rate after discounts."),
                col("line_item_net_unblended_cost", "BigDecimal", "Net unblended cost after discounts."),
                col("line_item_line_item_description", "String", "Description of the line item."),
                col("line_item_tax_type", "String", "Type of tax applied to the line item."),
                col("line_item_legal_entity", "String", "Seller of Record of the line item."),
                col("product", "Map", "Product attributes as key-value pairs."),
                col("product_sku", "String", "Unique product SKU."),
                col("product_product_family", "String", "Product category for this line item."),
                col("product_region", "String", "Region the product is offered in."),
                col("product_servicecode", "String", "Service code of the product."),
                col("product_instance_type", "String", "Instance type of the product."),
                col("pricing_rate_code", "String", "Unique code for a product/offer/pricing-tier combination."),
                col("pricing_term", "String", "Whether the usage is Reserved or On-Demand."),
                col("pricing_unit", "String", "Pricing unit used to calculate the cost."),
                col("pricing_public_on_demand_rate", "BigDecimal", "Public On-Demand rate of the line item."),
                col("pricing_public_on_demand_cost", "BigDecimal", "Public On-Demand cost of the line item."),
                col("reservation_reservation_a_r_n", "String", "ARN of the reservation this line item is associated with."),
                col("savings_plan_savings_plan_a_r_n", "String", "ARN of the Savings Plan this line item is associated with."),
                col("savings_plan_savings_plan_effective_cost", "BigDecimal", "Effective cost of the Savings Plan."),
                col("discount", "Map", "Discount attributes as key-value pairs."),
                col("total_discount", "BigDecimal", "Total discount applied to the line item."),
                col("resource_tags", "Map", "Resource tags as key-value pairs."),
                col("cost_category", "Map", "Cost category values as key-value pairs."),
                col("tags", "Map", "User, account, cost category and resource tags.")));
    }

    private static TableProperty prop(String name, String defaultValue, String description, List<String> validValues) {
        return new TableProperty(name, defaultValue, description, validValues);
    }

    private static Column col(String name, String type, String description) {
        return new Column(name, type, description);
    }
}
