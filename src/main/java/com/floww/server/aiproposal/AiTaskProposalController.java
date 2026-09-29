package com.floww.server.aiproposal;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import com.floww.server.task.presentation.TaskInputs;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

@RestController
public class AiTaskProposalController {
    private final AiTaskProposalService service;

    public AiTaskProposalController(AiTaskProposalService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/tasks/{taskId}/ai-proposal")
    public ResponseEntity<AiTaskProposalService.Response> propose(
            @RequestAttribute(name = "owner", required = false) String owner,
            @RequestAttribute(name = "role", required = false) String role,
            @PathVariable UUID taskId, HttpServletRequest request) throws IOException {
        if (!"USER".equals(role)) throw new ApiException(ErrorCode.FORBIDDEN);
        UUID ownerId = TaskInputs.owner(owner);
        // A single read rejects both fixed-length and chunked bodies without materializing input.
        if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1)
            throw new ApiException(ErrorCode.INVALID_INPUT);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.propose(ownerId, taskId));
    }

    /** Apply no-store even when authentication or request validation rejects the route. */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class NoStoreFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (request.getServletPath().matches("/api/v1/tasks/[^/]+/ai-proposal")) {
                response.setHeader("Cache-Control", "no-store");
            }
            chain.doFilter(request, response);
        }
    }
}
