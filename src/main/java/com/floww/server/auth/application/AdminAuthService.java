package com.floww.server.auth.application;

import com.floww.server.auth.config.AuthInputs;
import com.floww.server.auth.config.TokenAudience;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;
import com.floww.server.auth.domain.UserStatus;
import com.floww.server.auth.infrastructure.JwtProvider;
import com.floww.server.auth.infrastructure.PasswordHasher;
import com.floww.server.auth.infrastructure.UserRepository;
import com.floww.server.auth.presentation.dto.request.EmailSigninRequest;
import com.floww.server.auth.presentation.dto.response.SigninResponse;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthService {
    private final UserRepository users;
    private final PasswordHasher passwords;
    private final JwtProvider jwt;

    public AdminAuthService(UserRepository users, PasswordHasher passwords, JwtProvider jwt) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
    }

    public SigninResponse signin(EmailSigninRequest request) {
        String email = AuthInputs.normalizeEmail(request.email());
        Optional<User> found = users.findByEmail(email);
        User user = found.orElse(null);

        if (!passwords.matches(request.password(), user == null ? null : user.passwordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        // USER 계정은 비밀번호가 맞아도 어드민 로그인에 사용할 수 없다.
        if (user.role() != UserRole.ADMIN) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (user.status() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.USER_SUSPENDED);
        }

        JwtProvider.IssuedToken token = jwt.issue(user, TokenAudience.ADMIN);
        return SigninResponse.of(token.value(), token.expiresInSeconds(), false, user);
    }
}