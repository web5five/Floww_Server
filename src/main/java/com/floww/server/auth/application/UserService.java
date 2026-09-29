package com.floww.server.auth.application;

import com.floww.server.auth.infrastructure.UserRepository;
import com.floww.server.auth.domain.AuthProvider;
import com.floww.server.auth.presentation.dto.response.UserResponse;
import com.floww.server.auth.wallet.WalletProfileReader;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository users;
    private final WalletProfileReader walletProfiles;

    public UserService(UserRepository users, WalletProfileReader walletProfiles) {
        this.users = users;
        this.walletProfiles = walletProfiles;
    }

    public UserResponse me(UUID userId) {
        if (userId == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        return users.findById(userId)
                .map(user -> user.provider() == AuthProvider.WALLET
                        ? UserResponse.from(user).withWallets(walletProfiles.wallets(user.id()))
                        : UserResponse.from(user))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
