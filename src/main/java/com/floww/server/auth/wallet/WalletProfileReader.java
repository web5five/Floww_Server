package com.floww.server.auth.wallet;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WalletProfileReader {
    private final WalletSigninRepository repository;

    WalletProfileReader(WalletSigninRepository repository) {
        this.repository = repository;
    }

    public List<Object> wallets(UUID userId) {
        return repository.wallets(userId).stream()
                .map(wallet -> (Object) new WalletSigninService.WalletResponse(
                        wallet.walletId(), wallet.address(), wallet.walletType(), wallet.primary()))
                .toList();
    }
}
