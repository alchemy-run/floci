package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.User;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDbUserHandlerTest {

    private MemoryDbService service;
    private MemoryDbHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(MemoryDbService.class);
        handler = new MemoryDbHandler(service, objectMapper);
        when(service.aclNamesForUser(any())).thenReturn(List.of());
    }

    @Test
    void createUserParsesTags() throws Exception {
        when(service.createUser(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        JsonNode request = objectMapper.readTree(
                "{\"UserName\":\"app-user\","
                        + "\"AccessString\":\"on ~* +@all\","
                        + "\"AuthenticationMode\":{\"Type\":\"password\",\"Passwords\":[\"s3cret\"]},"
                        + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"memorydb-user\"}]}");

        Response response = handler.handle("CreateUser", request, "us-east-1");
        assertEquals(200, response.getStatus());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(service).createUser(captor.capture(), eq("us-east-1"));
        assertEquals("memorydb-user", captor.getValue().getTags().get("fixture"));
    }

    @Test
    void updateUserForwardsAccessString() throws Exception {
        when(service.updateUser(any(), any(), any(), any())).thenAnswer(inv -> {
            User user = new User();
            user.setName(inv.getArgument(0));
            user.setAccessString(inv.getArgument(1));
            user.setStatus("active");
            user.setAuthMode(AuthMode.PASSWORD);
            return user;
        });
        JsonNode request = objectMapper.readTree(
                "{\"UserName\":\"app-user\",\"AccessString\":\"on ~app:* +@read\"}");

        Response response = handler.handle("UpdateUser", request, "us-east-1");
        assertEquals(200, response.getStatus());
        verify(service).updateUser(eq("app-user"), eq("on ~app:* +@read"), isNull(), eq(List.of()));
    }
}
