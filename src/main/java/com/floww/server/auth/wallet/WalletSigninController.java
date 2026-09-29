package com.floww.server.auth.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.floww.server.auth.presentation.dto.response.SigninResponse;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/wallet")
@ConditionalOnProperty(prefix = "floww.auth.wallet", name = "enabled", havingValue = "true")
final class WalletSigninController {
    private final WalletSigninService service;

    WalletSigninController(WalletSigninService service) { this.service = service; }

    @PostMapping(path = "/nonce", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WalletSigninService.NonceResponse> nonce(HttpServletRequest request) {
        JsonNode body = (JsonNode) request.getAttribute(WalletSigninJsonFilter.BODY_ATTRIBUTE);
        if (body == null || !body.isObject() || body.size() != 2
                || !body.has("address") || !body.has("chainId")) throw new ApiException(ErrorCode.INVALID_INPUT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.nonce(
                WalletSigninInputs.address(body.get("address")), WalletSigninInputs.chainId(body.get("chainId"))));
    }

    @PostMapping(path = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SigninResponse> verify(HttpServletRequest request) {
        JsonNode body = (JsonNode) request.getAttribute(WalletSigninJsonFilter.BODY_ATTRIBUTE);
        if (body == null || !body.isObject() || body.size() != 2
                || !body.has("message") || !body.has("signature")) throw new ApiException(ErrorCode.INVALID_INPUT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.verify(
                WalletSigninInputs.message(body.get("message")), WalletSigninInputs.signature(body.get("signature"))));
    }
}
