package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class AccountBootstrap {
    private final EmulatorConfig config;
    private final AccountService service;
    private final ObjectMapper mapper;

    @Inject
    public AccountBootstrap(EmulatorConfig config, AccountService service, ObjectMapper mapper) {
        this.config = config;
        this.service = service;
        this.mapper = mapper;
    }

    void initialize(@Observes StartupEvent event) {
        if (!config.services().account().enabled()) {
            return;
        }
        config.services().account().bootstrapContactInformation().ifPresent(contact -> {
            var request = mapper.createObjectNode();
            try {
                request.set("ContactInformation", mapper.readTree(contact));
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("Account bootstrap contact information must be valid JSON", error);
            }
            try {
                service.getContactInformation(config.defaultAccountId(), mapper.createObjectNode());
                return;
            } catch (AwsException error) {
                if (!"ResourceNotFoundException".equals(error.getErrorCode())) {
                    throw error;
                }
            }
            service.putContactInformation(config.defaultAccountId(), request);
        });
    }
}
