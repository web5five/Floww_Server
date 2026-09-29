package com.floww.server.auth.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.floww.server.auth.config.AuthInputs;
import com.floww.server.auth.application.AuthService;
import com.floww.server.auth.presentation.dto.response.SigninResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/email")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public ResponseEntity<SigninResponse> signup(@RequestBody JsonNode body) {
        SigninResponse response = authService.signup(AuthInputs.signup(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/signin")
    public SigninResponse signin(@RequestBody JsonNode body) {
        return authService.signin(AuthInputs.signin(body));
    }
}