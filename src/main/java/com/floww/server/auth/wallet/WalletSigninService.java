package com.floww.server.auth.wallet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.floww.server.auth.infrastructure.JwtProvider;
import com.floww.server.auth.presentation.dto.response.SigninResponse;
import com.floww.server.auth.config.TokenAudience;
import com.floww.server.auth.domain.User;
import com.floww.server.auth.presentation.dto.response.UserResponse;
import com.floww.server.common.error.ApiException;
import com.floww.server.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

@Service
@ConditionalOnProperty(prefix = "floww.auth.wallet", name = "enabled", havingValue = "true")
class WalletSigninService {
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Pattern NONCE = Pattern.compile("(?m)^Nonce: ([A-Za-z0-9]{16,64})$");
    private final WalletSigninConfig config;
    private final WalletSigninRepository repository;
    private final JwtProvider jwt;
    private final Clock clock;
    private final SecureRandom random;

    record NonceResponse(String nonce, String message, Instant expiresAt) { }
    record WalletResponse(java.util.UUID walletId, String address, String walletType,
                          @JsonProperty("primary") boolean primary) { }

    @Autowired
    WalletSigninService(WalletSigninConfig config, WalletSigninRepository repository, JwtProvider jwt) {
        this(config, repository, jwt, Clock.systemUTC(), new SecureRandom());
    }

    WalletSigninService(WalletSigninConfig config, WalletSigninRepository repository, JwtProvider jwt,
                        Clock clock, SecureRandom random) {
        this.config = config;
        this.repository = repository;
        this.jwt = jwt;
        this.clock = clock;
        this.random = random;
    }

    @Transactional
    NonceResponse nonce(String address, long chainId) {
        requireChain(chainId);
        repository.lockAddress(address);
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String nonce = HexFormat.of().formatHex(bytes);
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = issued.plus(CHALLENGE_TTL);
        String checksum = Keys.toChecksumAddress(address);
        String message = config.origin + " wants you to sign in with your Ethereum account:\n"
                + checksum + "\n\nSign in to Floww\n\nURI: " + config.origin
                + "\nVersion: 1\nChain ID: " + chainId + "\nNonce: " + nonce
                + "\nIssued At: " + issued + "\nExpiration Time: " + expiry;
        repository.insertChallenge(new WalletSigninRepository.Challenge(nonce, address, chainId,
                message, expiry, false));
        return new NonceResponse(nonce, message, expiry);
    }

    @Transactional
    SigninResponse verify(String message, String signature) {
        Matcher matcher = NONCE.matcher(message);
        if (!matcher.find()) throw new ApiException(ErrorCode.NONCE_INVALID);
        String nonce = matcher.group(1);
        WalletSigninRepository.Challenge challenge = repository.challenge(nonce)
                .orElseThrow(() -> new ApiException(ErrorCode.NONCE_INVALID));
        if (challenge.consumed()) throw new ApiException(ErrorCode.NONCE_INVALID);
        if (!clock.instant().isBefore(challenge.expiresAt())) throw new ApiException(ErrorCode.NONCE_EXPIRED);
        if (!challenge.message().equals(message)) throw new ApiException(ErrorCode.MESSAGE_MISMATCH);
        requireChain(challenge.chainId());
        if (!recoverAddress(message, signature).equals(challenge.address())) {
            throw new ApiException(ErrorCode.SIGNATURE_INVALID);
        }
        repository.lockAddress(challenge.address());
        WalletSigninRepository.FoundUser found = repository.findOrCreate(challenge.address());
        User user = found.user();
        if (!user.status().canSignIn()) throw new ApiException(ErrorCode.USER_SUSPENDED);
        // The SQL predicate rechecks deadline and consumed state after identity/lock work.
        // A failed consume rolls back any newly inserted user and wallet in this transaction.
        if (!repository.consume(nonce)) throw new ApiException(ErrorCode.NONCE_INVALID);
        JwtProvider.IssuedToken token = jwt.issue(user, TokenAudience.CLIENT);
        List<Object> wallets = repository.wallets(user.id()).stream()
                .map(w -> (Object) new WalletResponse(w.walletId(), w.address(), w.walletType(), w.primary()))
                .toList();
        UserResponse responseUser = new UserResponse(user.id(), user.email(), user.displayName(),
                user.role(), List.of(user.provider()), wallets, user.createdAt());
        return new SigninResponse(token.value(), SigninResponse.BEARER, token.expiresInSeconds(),
                found.isNew(), responseUser);
    }

    private void requireChain(long chainId) {
        if (!config.chainIds.contains(chainId)) throw new ApiException(ErrorCode.CHAIN_NOT_SUPPORTED);
    }

    private static String recoverAddress(String message, String signature) {
        try {
            byte[] sig = Numeric.hexStringToByteArray(signature);
            if (sig.length != 65 || (sig[64] != 27 && sig[64] != 28)) {
                throw new ApiException(ErrorCode.SIGNATURE_INVALID);
            }
            byte[] r = java.util.Arrays.copyOfRange(sig, 0, 32);
            byte[] s = java.util.Arrays.copyOfRange(sig, 32, 64);
            Sign.SignatureData data = new Sign.SignatureData(sig[64], r, s);
            return ("0x" + Keys.getAddress(Sign.signedPrefixedMessageToKey(
                    message.getBytes(StandardCharsets.UTF_8), data))).toLowerCase(Locale.ROOT);
        } catch (SignatureException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.SIGNATURE_INVALID);
        }
    }
}
