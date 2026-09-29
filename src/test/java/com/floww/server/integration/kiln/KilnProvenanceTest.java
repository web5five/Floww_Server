package com.floww.server.integration.kiln;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KilnProvenanceTest {
    @Test void onlyExactOfficialEndpointCanClaimKiln() {
        ObjectMapper json = new ObjectMapper();
        assertEquals("kiln", new KilnClient(json, "https://api.bricksum.com/v1", "test-key")
                .modelEvidenceMode());
        assertEquals("local_model_fixture", new KilnClient(json, "http://127.0.0.1:18082/v1", "test-key")
                .modelEvidenceMode());
        assertEquals("unknown_model_provider", new KilnClient(json, "https://other.example/v1", "test-key")
                .modelEvidenceMode());
        assertEquals("unknown_model_provider", new KilnClient(json, "https://api.bricksum.com.evil/v1", "test-key")
                .modelEvidenceMode());
        assertEquals("unknown_model_provider", new KilnClient(json, "https://api.bricksum.com/other", "test-key")
                .modelEvidenceMode());
    }
}
