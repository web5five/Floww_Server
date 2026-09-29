package com.floww.server.adminaudit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.floww.server.auth.config.TokenAudience;
import com.floww.server.auth.domain.AuthProvider;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;
import com.floww.server.auth.domain.UserStatus;
import com.floww.server.auth.infrastructure.JwtProvider;
import com.floww.server.common.auth.JwtAuthFilter;
import com.floww.server.common.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

class AdminAuditHttpTest {
    private MockMvc mvc;
    private AdminAuditRepository repository;
    private String admin;
    private String client;
    private UUID taskId;

    @BeforeEach
    void setup() {
        repository = org.mockito.Mockito.mock(AdminAuditRepository.class);
        JwtProvider jwt = new JwtProvider("audit-test-signing-key-is-at-least-32-bytes");
        admin = jwt.issue(user(UserRole.ADMIN), TokenAudience.ADMIN).value();
        client = jwt.issue(user(UserRole.USER), TokenAudience.CLIENT).value();
        taskId = UUID.randomUUID();
        AdminAuditRepository.TaskRow row = new AdminAuditRepository.TaskRow(taskId, UUID.randomUUID(),
                "0x1111111111111111111111111111111111111111", "Find item", "ACTIVE", null,
                UUID.randomUUID(), 1, "CONFIRMED", "item-1", "1200000",
                "0x2222222222222222222222222222222222222222", 6, Instant.now(),
                Instant.now(), Instant.now(), null);
        org.mockito.Mockito.when(repository.list(null, null, 0, 20))
                .thenReturn(new AdminAuditRepository.TaskPage(List.of(row), 1, 0, 20));
        org.mockito.Mockito.when(repository.detail(taskId))
                .thenReturn(new AdminAuditRepository.Detail(row, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new AdminAuditController(repository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JwtAuthFilter(jwt, new ObjectMapper(), false, false)).build();
    }

    private User user(UserRole role) {
        Instant now = Instant.now();
        return new User(UUID.randomUUID(), "admin@example.test", "unused", role, UserStatus.ACTIVE,
                AuthProvider.EMAIL, "Operator", now, now);
    }

    @Test
    void adminAudienceOnlyAndNoStore() throws Exception {
        String path = "/api/v1/admin/audit/tasks";
        mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).servletPath(path).header("Authorization", "Bearer " + client)).andExpect(status().isForbidden());
        mvc.perform(get(path).servletPath(path).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tasks[0].taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.total").value(1));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1)).list(null, null, 0, 20);
    }

    @Test
    void detailExcludesSensitiveFieldsAndMutations() throws Exception {
        String path = "/api/v1/admin/audit/tasks/" + taskId;
        String body = mvc.perform(get(path).servletPath(path).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains(taskId.toString()));
        for (String forbidden : List.of("accessToken", "passwordHash", "signature", "rawTransaction",
                "typedData", "approvalNonce", "payload", "privateKey")) assertFalse(body.contains(forbidden));
        mvc.perform(post(path).servletPath(path).header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void rejectsUnboundedAndUnknownFiltersBeforeRepositoryRead() throws Exception {
        for (String query : List.of("?limit=51", "?limit=0", "?page=-1", "?page=100001", "?status=UNKNOWN")) {
            mvc.perform(get("/api/v1/admin/audit/tasks" + query).servletPath("/api/v1/admin/audit/tasks").header("Authorization", "Bearer " + admin))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
