package com.floww.server.auth;

import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public UserResponse me(UUID userId) {
        if (userId == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        return users.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}