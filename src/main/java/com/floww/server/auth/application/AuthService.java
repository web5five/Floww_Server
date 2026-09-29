package com.floww.server.auth.application;

import com.floww.server.auth.config.AuthInputs;
import com.floww.server.auth.config.TokenAudience;
import com.floww.server.auth.domain.PasswordPolicy;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.domain.UserRole;
import com.floww.server.auth.domain.UserStatus;
import com.floww.server.auth.infrastructure.JwtProvider;
import com.floww.server.auth.infrastructure.PasswordHasher;
import com.floww.server.auth.infrastructure.UserRepository;
import com.floww.server.auth.presentation.dto.request.EmailSigninRequest;
import com.floww.server.auth.presentation.dto.request.EmailSignupRequest;
import com.floww.server.auth.presentation.dto.response.SigninResponse;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordHasher passwords;
    private final JwtProvider jwt;

    public AuthService(UserRepository users, PasswordHasher passwords, JwtProvider jwt) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
    }

    public SigninResponse signup(EmailSignupRequest request) {
        String email = AuthInputs.normalizeEmail(request.email());

        // 빠른 중복 확인 후에도 save의 UNIQUE 제약으로 동시 가입을 처리한다.
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        PasswordPolicy.check(request.password());
        String passwordHash = passwords.hash(request.password());

        // 가입 요청의 role은 받지 않고 항상 USER로 저장한다.
        User user = users.save(email, passwordHash, UserRole.USER, request.displayName());
        JwtProvider.IssuedToken token = jwt.issue(user, TokenAudience.CLIENT);

        return SigninResponse.of(token.value(), token.expiresInSeconds(), true, user);
    }

    public SigninResponse signin(EmailSigninRequest request) {
        String email = AuthInputs.normalizeEmail(request.email());
        Optional<User> found = users.findByEmail(email);
        User user = found.orElse(null);

        // 계정이 없어도 BCrypt 비교를 수행해 이메일 존재 여부 노출을 줄인다.
        if (!passwords.matches(request.password(), user == null ? null : user.passwordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (user.status() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.USER_SUSPENDED);
        }

        JwtProvider.IssuedToken token = jwt.issue(user, TokenAudience.CLIENT);
        return SigninResponse.of(token.value(), token.expiresInSeconds(), false, user);
    }
}