package com.floww.server.auth.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.floww.server.auth.application.AdminAuthService;
import com.floww.server.auth.config.AuthInputs;
import com.floww.server.auth.presentation.dto.response.SigninResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {
    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/signin")
    public SigninResponse signin(@RequestBody JsonNode body) {
        return adminAuthService.signin(AuthInputs.signin(body));
    }
}