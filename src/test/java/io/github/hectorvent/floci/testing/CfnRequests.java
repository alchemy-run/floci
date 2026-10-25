package io.github.hectorvent.floci.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

public final class CfnRequests {
    private CfnRequests() {}

    /** A CloudFormation Query request; a null {@code stackName} leaves {@code StackName} out. */
    public static RequestSpecification cfn(String action, String stackName) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        return stackName == null ? request : request.formParam("StackName", stackName);
    }
}
