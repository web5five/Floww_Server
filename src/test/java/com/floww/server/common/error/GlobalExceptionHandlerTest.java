package com.floww.server.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue #13: 모든 오류가 SA 7장 형식(reasonCode, 한·영 메시지, taskId/attemptId, retryable)으로 나가는지 확인한다.
 * DB 없이 standalone MockMvc로 실행한다.
 */
class GlobalExceptionHandlerTest {
    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000013");

    private MockMvc mvc;

    @RestController
    static class Probe {
        @GetMapping("/probe/missing")
        String missing() { throw new ApiException(ErrorCode.EXECUTION_NOT_FOUND, TASK); }

        @GetMapping("/probe/uuid/{id}")
        String uuid(@PathVariable UUID id) { return id.toString(); }

        @PostMapping("/probe/body")
        String body(@RequestBody JsonNode body) { return body.toString(); }

        @GetMapping("/probe/boom")
        String boom() { throw new IllegalStateException("secret-value-must-not-leak"); }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void apiExceptionUsesSaErrorShapeWithLegacyAlias() throws Exception {
        mvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reasonCode").value("EXECUTION_NOT_FOUND"))
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message.en").value("Execution not found"))
                .andExpect(jsonPath("$.message.ko").isNotEmpty())
                .andExpect(jsonPath("$.taskId").value(TASK.toString()))
                .andExpect(jsonPath("$.attemptId").value(nullValue()))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void malformedUuidBecomesInvalidInput() throws Exception {
        mvc.perform(get("/probe/uuid/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("INVALID_INPUT"));
    }

    @Test
    void brokenJsonBecomesMalformedJson() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("MALFORMED_JSON"));
    }

    @Test
    void unsupportedMethodReturns405WithAllowHeader() throws Exception {
        mvc.perform(delete("/probe/missing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.reasonCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unexpectedErrorHidesInternalDetails() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.reasonCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(content().string(not(containsString("secret-value-must-not-leak"))));
    }
}
