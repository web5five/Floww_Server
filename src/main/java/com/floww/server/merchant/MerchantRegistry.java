package com.floww.server.merchant;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Floww가 신뢰하는 판매자 목록 — Issue #33 (#17).
 *
 * <p>정책과 결정 3장: AI는 merchantId/quoteId만 제안하고, 수취인 주소는 이 레지스트리가 매핑한다.
 * 모델·클라이언트·판매자 응답이 recipient를 덮어쓸 수 없다. 판매자 견적의 payTo가 여기 값과 다르면
 * 정책 검사에서 {@code RECIPIENT_NOT_ALLOWED}로 차단한다.
 *
 * <p>기본 주소는 누구도 키를 갖지 않은 데모용 placeholder다. 테스트넷 지급 전에 env로 실제 주소를 넣는다.
 */
@Component
public class MerchantRegistry {
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-f]{40}");

    public record Merchant(String merchantId, String name, String recipientAddress, boolean active) { }

    private final Map<String, Merchant> merchants;

    public MerchantRegistry(
            @Value("${floww.merchant.pharmacy-a.recipient:0x00000000000000000000000000000000f10aa001}") String a,
            @Value("${floww.merchant.pharmacy-b.recipient:0x00000000000000000000000000000000f10aa002}") String b,
            @Value("${floww.merchant.pharmacy-c.recipient:0x00000000000000000000000000000000f10aa003}") String c) {
        List<Merchant> list = List.of(
                new Merchant(PharmacySimulator.PHARMACY_A, "Floww Demo Pharmacy A", address(a), true),
                new Merchant(PharmacySimulator.PHARMACY_B, "Floww Demo Pharmacy B", address(b), true),
                new Merchant(PharmacySimulator.PHARMACY_C, "Floww Demo Pharmacy C", address(c), true));
        Set<String> unique = new HashSet<>();
        Map<String, Merchant> byId = new LinkedHashMap<>();
        for (Merchant merchant : list) {
            if (!unique.add(merchant.recipientAddress())) {
                throw new IllegalStateException("Merchant recipient addresses must be unique");
            }
            byId.put(merchant.merchantId(), merchant);
        }
        this.merchants = Map.copyOf(byId);
    }

    public Optional<Merchant> find(String merchantId) {
        return Optional.ofNullable(merchants.get(merchantId));
    }

    /** 활성 판매자, merchantId 순. */
    public List<Merchant> active() {
        return merchants.values().stream().filter(Merchant::active)
                .sorted((x, y) -> x.merchantId().compareTo(y.merchantId())).toList();
    }

    /** 소문자 0x 주소로 정규화한다. 형식이 틀리면 기동을 멈춘다. */
    static String address(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!ADDRESS.matcher(value).matches()) {
            throw new IllegalStateException("Merchant recipient must be a 0x-prefixed 20-byte address");
        }
        return value;
    }
}
