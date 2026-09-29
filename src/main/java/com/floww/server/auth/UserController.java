package com.floww.server.auth;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse me(
            @RequestAttribute(name = "owner", required = false) String owner) {
        if (owner == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        try {
            return userService.me(UUID.fromString(owner));
        } catch (IllegalArgumentException invalidOwner) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
    }
}