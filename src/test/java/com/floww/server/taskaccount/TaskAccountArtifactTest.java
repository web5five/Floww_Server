package com.floww.server.taskaccount;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class TaskAccountArtifactTest {
    @Test void pinnedRuntimeRejectsChangedCodeAndConstructorUsesExactBytes() throws Exception {
        ObjectMapper json=new ObjectMapper();
        var root=json.readTree(getClass().getResourceAsStream("/taskaccount/FlowwTaskAccount.json"));
        var artifact=new TaskAccountArtifact(json);
        String runtime="0x"+root.path("artifact").path("evm").path("deployedBytecode").path("object").asText();
        assertTrue(artifact.matchesRuntime(runtime));
        assertFalse(artifact.matchesRuntime("0x00"+runtime.substring(4)));
        assertFalse(artifact.matchesRuntime("0x"));
        String creation="0x"+root.path("artifact").path("evm").path("bytecode").path("object").asText();
        String data=artifact.deployment("0x00000000000000000000000000000000fac10002",
                "0x"+"11".repeat(32),"0x"+"22".repeat(32),
                "0x00000000000000000000000000000000fac10005",
                "0x00000000000000000000000000000000fac10006",
                "0x00000000000000000000000000000000fac10003",
                "0x00000000000000000000000000000000fac10004",BigInteger.valueOf(23_500_000),1790790000);
        assertTrue(data.startsWith(creation));
        assertEquals(9*64,data.length()-creation.length());
    }
}
