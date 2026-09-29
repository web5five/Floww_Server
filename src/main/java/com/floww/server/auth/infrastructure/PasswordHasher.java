package com.floww.server.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.floww.server.auth.domain.PasswordPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
    /** BCrypt cost. 10 = 해시 1회 약 100ms 전후 (하드웨어에 따라 다름). */
    static final int STRENGTH = 10;

    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;

    public PasswordHasher() {
        this(STRENGTH);
    }

    /** 테스트에서 낮은 cost로 빠르게 돌리기 위한 생성자. */
    PasswordHasher(int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public String hash(String rawPassword) {
        if (rawPassword == null) throw new IllegalArgumentException("password is required");
        return encoder.encode(rawPassword);
    }

    /**
     * @param passwordHash 저장된 해시. 사용자가 없으면 null을 넘긴다 (그래도 BCrypt 비교는 수행한다).
     */
    public boolean matches(String rawPassword, String passwordHash) {
        String raw = rawPassword == null ? "" : rawPassword;
        boolean tooLong = raw.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES;
        boolean usable = passwordHash != null && !tooLong;
        boolean matched = encoder.matches(tooLong ? "" : raw, usable ? passwordHash : dummyHash);
        return usable && matched;
    }
}