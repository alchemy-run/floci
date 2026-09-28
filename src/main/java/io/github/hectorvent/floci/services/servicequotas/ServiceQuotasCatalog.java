package io.github.hectorvent.floci.services.servicequotas;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static catalog of AWS default service quotas: real service codes, quota codes, names, default
 * values, units and adjustable/global flags as published by Service Quotas. Only services listed
 * here exist; any other service code is unknown, exactly as in AWS.
 */
final class ServiceQuotasCatalog {

    record ServiceDefinition(String serviceCode, String serviceName, List<QuotaDefinition> quotas) {
    }

    record QuotaDefinition(String quotaCode, String quotaName, double defaultValue, String unit,
                           boolean adjustable, boolean globalQuota) {
    }

    private static final Map<String, ServiceDefinition> SERVICES = build(List.of(
            service("cloudformation", "AWS CloudFormation",
                    quota("L-0485CB21", "Stack count", 2000)),
            service("codebuild", "AWS CodeBuild",
                    quota("L-2DC20C30", "Concurrently running builds", 5000)),
            service("dynamodb", "Amazon DynamoDB",
                    quota("L-F98FE922", "Maximum number of tables", 2500)),
            service("ec2", "Amazon Elastic Compute Cloud (Amazon EC2)",
                    quota("L-1216C47A", "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances", 5),
                    quota("L-DB2E81BA", "Running On-Demand G and VT instances", 0),
                    quota("L-417A185B", "Running On-Demand P instances", 0),
                    quota("L-34B43A08", "All Standard (A, C, D, H, I, M, R, T, Z) Spot Instance Requests", 5),
                    quota("L-0263D0A3", "EC2-VPC Elastic IPs", 5)),
            service("ecs", "Amazon Elastic Container Service (Amazon ECS)",
                    quota("L-21C621EB", "Clusters per account", 10000),
                    quota("L-9EF96962", "Services per cluster", 5000)),
            service("elasticloadbalancing", "Elastic Load Balancing (ELB)",
                    quota("L-53DA6B97", "Application Load Balancers per Region", 50),
                    quota("L-69A177A2", "Network Load Balancers per Region", 50),
                    quota("L-B22855CB", "Target Groups per Region", 3000)),
            service("iam", "AWS Identity and Access Management (IAM)",
                    globalQuota("L-FE177D64", "Roles per account", 1000),
                    globalQuota("L-F4A5425F", "Users per account", 5000),
                    globalQuota("L-F55AF5E4", "Groups per account", 300),
                    globalQuota("L-0DA4ABF3", "Managed policies per role", 10),
                    globalQuota("L-E95E4862", "Customer managed policies per account", 1500),
                    globalQuota("L-858F3967", "Instance profiles per account", 1000)),
            service("lambda", "AWS Lambda",
                    quota("L-B99A9384", "Concurrent executions", 1000),
                    quota("L-9FEE3D26", "Elastic network interfaces per VPC", 500)),
            service("organizations", "AWS Organizations",
                    globalQuota("L-E619E033", "Default maximum number of accounts", 10)),
            service("rds", "Amazon Relational Database Service (Amazon RDS)",
                    quota("L-7B6409FD", "DB instances", 40),
                    quota("L-952B80B8", "DB clusters", 40)),
            service("vpc", "Amazon Virtual Private Cloud (Amazon VPC)",
                    quota("L-F678F1CE", "VPCs per Region", 5),
                    quota("L-407747CB", "Subnets per VPC", 200),
                    quota("L-A4707A72", "Internet gateways per Region", 5),
                    quota("L-45FE3B85", "Egress-only internet gateways per Region", 5),
                    quota("L-FE5A380F", "NAT gateways per Availability Zone", 5),
                    quota("L-83CA0A9D", "IPv4 CIDR blocks per VPC", 5),
                    quota("L-589F43AA", "Route tables per VPC", 200),
                    quota("L-93826ACB", "Routes per route table", 50),
                    quota("L-B4A6D682", "Network ACLs per VPC", 200),
                    quota("L-2AEEBF1A", "Rules per network ACL", 20),
                    quota("L-DF5E4CA3", "Network interfaces per Region", 5000),
                    quota("L-E79EC296", "VPC security groups per Region", 2500),
                    quota("L-0EA8095F", "Inbound or outbound rules per security group", 60),
                    quota("L-2AFB9258", "Security groups per network interface", 5),
                    quota("L-7E9ECCDB", "Active VPC peering connections per VPC", 50),
                    quota("L-29B6F2EB", "Interface VPC endpoints per VPC", 50),
                    quota("L-1B52E74A", "Gateway VPC endpoints per Region", 20))));

    private ServiceQuotasCatalog() {
    }

    static List<ServiceDefinition> services() {
        return List.copyOf(SERVICES.values());
    }

    static Optional<ServiceDefinition> service(String serviceCode) {
        return Optional.ofNullable(SERVICES.get(serviceCode));
    }

    private static Map<String, ServiceDefinition> build(List<ServiceDefinition> services) {
        Map<String, ServiceDefinition> byCode = new LinkedHashMap<>();
        services.stream()
                .sorted(Comparator.comparing(ServiceDefinition::serviceCode))
                .forEach(service -> byCode.put(service.serviceCode(), service));
        return Collections.unmodifiableMap(byCode);
    }

    private static ServiceDefinition service(String code, String name, QuotaDefinition... quotas) {
        return new ServiceDefinition(code, name, List.of(quotas));
    }

    private static QuotaDefinition quota(String code, String name, double value) {
        return new QuotaDefinition(code, name, value, "None", true, false);
    }

    private static QuotaDefinition globalQuota(String code, String name, double value) {
        return new QuotaDefinition(code, name, value, "None", true, true);
    }
}
