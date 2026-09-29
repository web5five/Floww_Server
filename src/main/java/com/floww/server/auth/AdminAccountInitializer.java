package com.floww.server.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminAccountInitializer implements ApplicationRunner {
    private final String adminEmail;
    private final String adminPassword;
    private final UserRepository users;
    private final PasswordHasher passwords;

    public AdminAccountInitializer(
            @Value("${ADMIN_EMAIL:}") String adminEmail,
            @Value("${ADMIN_PASSWORD:}") String adminPassword,
            UserRepository users,
            PasswordHasher passwords) {
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 둘 중 하나라도 설정되지 않았으면 생성하지 않는다.
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            return;
        }

        // 이미 ADMIN이 있으면 추가 생성하지 않는다.
        if (users.existsByRole(UserRole.ADMIN)) {
            return;
        }

        String email = AuthInputs.normalizeEmail(adminEmail);
        PasswordPolicy.check(adminPassword);

        users.save(
                email,
                passwords.hash(adminPassword),
                UserRole.ADMIN,
                null);
    }
}