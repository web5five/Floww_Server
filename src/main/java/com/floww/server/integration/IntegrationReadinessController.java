package com.floww.server.integration;

import com.floww.server.integration.kiln.KilnClient;
import com.floww.server.integration.merchant.MerchantGateway;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrationReadinessController {
    private final KilnClient kiln;
    private final MerchantGateway merchant;
    public IntegrationReadinessController(KilnClient kiln, MerchantGateway merchant) {
        this.kiln = kiln; this.merchant = merchant;
    }
    @GetMapping("/api/integrations/readiness")
    public Map<String, Object> readiness() {
        return Map.of("kilnConfigured", kiln.available(), "merchantConfigured", merchant.available(),
                "merchantMode", merchant.available() ? merchant.evidenceMode() : "unavailable",
                "paymentConfigured", false, "processHealthIsIntegrationProof", false);
    }
}
