package io.github.hectorvent.floci.services.dsql.proxy;

@FunctionalInterface
public interface PasswordValidator {
    boolean validate(String username, String password);
}
