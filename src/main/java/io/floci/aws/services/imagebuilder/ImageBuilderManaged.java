package io.floci.aws.services.imagebuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Amazon owns in Image Builder, ARNs in the {@code aws} account: the managed parent images (each mapped to the
 * EC2 AMI Floci launches for it), managed components and the managed workflows Image Builder runs when an image
 * names none of its own. The workflows are the documents Floci's engine interprets, step by step, like any other.
 */
final class ImageBuilderManaged {

    static final String OWNER_ACCOUNT = "aws";
    static final String DATE_VERSION = "2026.11.1";

    private ImageBuilderManaged() {
    }

    /** A managed parent image: its AMI, platform, OS version and architecture. */
    record ManagedImage(String name, String amiId, String platform, String osVersion, String architecture) {
    }

    static final List<ManagedImage> IMAGES = List.of(
            new ManagedImage("amazon-linux-2023-x86", "ami-0abcdef1234567891", "Linux", "Amazon Linux 2023", "x86_64"),
            new ManagedImage("amazon-linux-2023-arm64", "ami-amazonlinux2023-arm64", "Linux", "Amazon Linux 2023",
                    "arm64"),
            new ManagedImage("amazon-linux-2-x86", "ami-0abcdef1234567890", "Linux", "Amazon Linux 2", "x86_64"),
            new ManagedImage("ubuntu-server-20-lts-x86", "ami-0abcdef1234567892", "Linux", "Ubuntu 20", "x86_64"),
            new ManagedImage("ubuntu-server-22-lts-x86", "ami-ubuntu2204", "Linux", "Ubuntu 22", "x86_64"),
            new ManagedImage("ubuntu-server-24-lts-x86", "ami-ubuntu2404-amd64", "Linux", "Ubuntu 24", "x86_64"),
            new ManagedImage("ubuntu-server-24-lts-arm64", "ami-ubuntu2404-arm64", "Linux", "Ubuntu 24", "arm64"),
            new ManagedImage("debian-12-x86", "ami-debian12", "Linux", "Debian 12", "x86_64"));

    static ManagedImage image(String name) {
        for (ManagedImage image : IMAGES) {
            if (image.name().equals(name)) {
                return image;
            }
        }
        return null;
    }

    /** A managed component: its type and AWSTOE document. */
    record ManagedComponent(String name, String version, String type, String description, String data) {
    }

    static final List<ManagedComponent> COMPONENTS = List.of(
            new ManagedComponent("update-linux", "1.0.2", "BUILD", "Updates Linux with the latest security updates.",
                    """
                    name: update-linux
                    description: Updates Linux with the latest security updates.
                    schemaVersion: 1.0
                    phases:
                      - name: build
                        steps:
                          - name: UpdateOS
                            action: UpdateOS
                    """),
            new ManagedComponent("reboot-linux", "1.0.1", "BUILD", "Reboots the instance.", """
                    name: reboot-linux
                    description: Reboots the instance.
                    schemaVersion: 1.0
                    phases:
                      - name: build
                        steps:
                          - name: Reboot
                            action: Reboot
                    """),
            new ManagedComponent("hello-world-linux", "1.0.0", "BUILD", "Prints hello world in each phase.", """
                    name: hello-world-linux
                    description: Prints hello world in each phase.
                    schemaVersion: 1.0
                    phases:
                      - name: build
                        steps:
                          - name: HelloWorldStep
                            action: ExecuteBash
                            inputs:
                              commands:
                                - echo 'Hello World from the build phase.'
                      - name: validate
                        steps:
                          - name: HelloWorldStep
                            action: ExecuteBash
                            inputs:
                              commands:
                                - echo 'Hello World from the validate phase.'
                      - name: test
                        steps:
                          - name: HelloWorldStep
                            action: ExecuteBash
                            inputs:
                              commands:
                                - echo 'Hello World from the test phase.'
                    """),
            new ManagedComponent("python-3-linux", "1.0.0", "BUILD", "Installs Python 3.", """
                    name: python-3-linux
                    description: Installs Python 3.
                    schemaVersion: 1.0
                    phases:
                      - name: build
                        steps:
                          - name: InstallPython
                            action: ExecuteBash
                            inputs:
                              commands:
                                - if command -v dnf >/dev/null; then dnf install -y python3;
                                  elif command -v yum >/dev/null; then yum install -y python3;
                                  else apt-get update && apt-get install -y python3; fi
                      - name: validate
                        steps:
                          - name: ValidatePython
                            action: ExecuteBash
                            inputs:
                              commands:
                                - python3 --version
                    """),
            new ManagedComponent("simple-boot-test-linux", "1.0.0", "TEST", "Checks the instance boots.", """
                    name: simple-boot-test-linux
                    description: Checks the instance boots.
                    schemaVersion: 1.0
                    phases:
                      - name: test
                        steps:
                          - name: Uptime
                            action: ExecuteBash
                            inputs:
                              commands:
                                - test -r /etc/os-release && cat /etc/os-release
                    """),
            new ManagedComponent("reboot-test-linux", "1.0.0", "TEST", "Checks the instance reboots.", """
                    name: reboot-test-linux
                    description: Checks the instance reboots.
                    schemaVersion: 1.0
                    phases:
                      - name: test
                        steps:
                          - name: Reboot
                            action: Reboot
                    """));

    static ManagedComponent component(String name) {
        for (ManagedComponent component : COMPONENTS) {
            if (component.name().equals(name)) {
                return component;
            }
        }
        return null;
    }

    /** A managed workflow: its type and document. */
    record ManagedWorkflow(String name, String type, String description, String data) {
    }

    private static final String BUILD_IMAGE = """
            name: build-image
            description: Workflow to build an AMI
            schemaVersion: 1.0
            steps:
              - name: LaunchBuildInstance
                action: LaunchInstance
                onFailure: Abort
                inputs:
                  waitFor: "ssmAgent"
                  healthCheck: "HEALTH_CHECK"
              - name: ApplyBuildComponents
                action: ExecuteComponents
                onFailure: Abort
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
              - name: InventoryCollection
                action: CollectImageMetadata
                onFailure: Abort
                if:
                  and:
                    - stringEquals: "AMI"
                      value: "$.imagebuilder.imageType"
                    - booleanEquals: true
                      value: "$.imagebuilder.collectImageMetadata"
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
              - name: RunSanitizeScript
                action: SanitizeInstance
                onFailure: Abort
                if:
                  and:
                    - stringEquals: "AMI"
                      value: "$.imagebuilder.imageType"
                    - not:
                        stringEquals: "Windows"
                        value: "$.imagebuilder.platform"
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
              - name: RunSysPrepScript
                action: RunSysPrep
                onFailure: Abort
                if:
                  and:
                    - stringEquals: "AMI"
                      value: "$.imagebuilder.imageType"
                    - stringEquals: "Windows"
                      value: "$.imagebuilder.platform"
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
              - name: CreateOutputAMI
                action: CreateImage
                onFailure: Abort
                if:
                  stringEquals: "AMI"
                  value: "$.imagebuilder.imageType"
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
              - name: TerminateBuildInstance
                action: TerminateInstance
                onFailure: Continue
                if:
                  booleanEquals: true
                  value: "$.imagebuilder.terminateInstanceOnCompletion"
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
            outputs:
              - name: "ImageId"
                value: "$.stepOutputs.CreateOutputAMI.imageId"
            """;

    private static final String TEST_IMAGE = """
            name: test-image
            description: Workflow to test an AMI
            schemaVersion: 1.0
            steps:
              - name: LaunchTestInstance
                action: LaunchInstance
                onFailure: Abort
                inputs:
                  waitFor: "ssmAgent"
                  healthCheck: "HEALTH_CHECK"
              - name: CollectImageScanFindings
                action: CollectImageScanFindings
                onFailure: Continue
                if:
                  booleanEquals: true
                  value: "$.imagebuilder.collectImageScanFindings"
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchTestInstance.instanceId"
              - name: ApplyTestComponents
                action: ExecuteComponents
                onFailure: Abort
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchTestInstance.instanceId"
              - name: TerminateTestInstance
                action: TerminateInstance
                onFailure: Continue
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchTestInstance.instanceId"
            """;

    private static final String BUILD_CONTAINER = """
            name: build-container
            description: Workflow to build a container image
            schemaVersion: 1.0
            steps:
              - name: LaunchBuildInstance
                action: LaunchInstance
                onFailure: Abort
                inputs:
                  waitFor: "ssmAgent"
                  healthCheck: "HEALTH_CHECK"
              - name: BootstrapBuildInstance
                action: BootstrapInstanceForContainer
                onFailure: Abort
                if:
                  stringEquals: "DOCKER"
                  value: "$.imagebuilder.imageType"
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
              - name: ApplyBuildComponents
                action: ExecuteComponents
                onFailure: Abort
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
              - name: TerminateBuildInstance
                action: TerminateInstance
                onFailure: Continue
                if:
                  booleanEquals: true
                  value: "$.imagebuilder.terminateInstanceOnCompletion"
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchBuildInstance.instanceId"
            """;

    private static final String TEST_CONTAINER = """
            name: test-container
            description: Workflow to test a container image
            schemaVersion: 1.0
            steps:
              - name: LaunchTestInstance
                action: LaunchInstance
                onFailure: Abort
                inputs:
                  waitFor: "ssmAgent"
                  healthCheck: "HEALTH_CHECK"
              - name: BootstrapTestInstance
                action: BootstrapInstanceForContainer
                onFailure: Abort
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchTestInstance.instanceId"
              - name: ApplyTestComponents
                action: ExecuteComponents
                onFailure: Abort
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchTestInstance.instanceId"
              - name: TerminateTestInstance
                action: TerminateInstance
                onFailure: Continue
                inputs:
                  instanceId.$: "$.stepOutputs.LaunchTestInstance.instanceId"
            """;

    static final Map<String, ManagedWorkflow> WORKFLOWS = workflows();

    private static Map<String, ManagedWorkflow> workflows() {
        Map<String, ManagedWorkflow> workflows = new LinkedHashMap<>();
        workflows.put("build/build-image", new ManagedWorkflow("build-image", "BUILD",
                "Default Amazon-managed AMI build workflow.", BUILD_IMAGE));
        workflows.put("build/express-build-image", new ManagedWorkflow("express-build-image", "BUILD",
                "Express Amazon-managed AMI build workflow.", BUILD_IMAGE.replace("name: build-image",
                "name: express-build-image").replace("HEALTH_CHECK", "RUNNING")));
        workflows.put("test/test-image", new ManagedWorkflow("test-image", "TEST",
                "Default Amazon-managed AMI test workflow.", TEST_IMAGE));
        workflows.put("test/express-test-image", new ManagedWorkflow("express-test-image", "TEST",
                "Express Amazon-managed AMI test workflow.", TEST_IMAGE.replace("name: test-image",
                "name: express-test-image").replace("HEALTH_CHECK", "RUNNING")));
        workflows.put("build/build-container", new ManagedWorkflow("build-container", "BUILD",
                "Default Amazon-managed container build workflow.", BUILD_CONTAINER));
        workflows.put("build/express-build-container", new ManagedWorkflow("express-build-container", "BUILD",
                "Express Amazon-managed container build workflow.", BUILD_CONTAINER.replace("name: build-container",
                "name: express-build-container").replace("HEALTH_CHECK", "RUNNING")));
        workflows.put("test/test-container", new ManagedWorkflow("test-container", "TEST",
                "Default Amazon-managed container test workflow.", TEST_CONTAINER));
        workflows.replaceAll((key, workflow) -> new ManagedWorkflow(workflow.name(), workflow.type(),
                workflow.description(), workflow.data().replace("HEALTH_CHECK", "STATUS_CHECK_PASSED")));
        return workflows;
    }
}
