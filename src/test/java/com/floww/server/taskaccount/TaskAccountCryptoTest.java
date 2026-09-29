package com.floww.server.taskaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Frozen cross-runtime ethers 6.16 vectors, independent of Web3j encoder. */
class TaskAccountCryptoTest {
    @Test void controllerIndependentVectorMatches() {
        var review=new TaskAccountCrypto.Review(UUID.fromString("11111111-2222-4333-8444-555555555555"),
                UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa"),3,
                UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),"pharmacy-a","qt_a_reference_20260930",
                BigInteger.valueOf(23_500_000),"0x1234567890123456789012345678901234567890",
                "0x1390c8745eb49069afd3b89393997e3fa14614f5",Instant.ofEpochSecond(1790712000));
        assertEquals("0xec4d825949723205fc69f22c87699d674a0bbbbb7ac3cabd813beabac584915a",
                TaskAccountCrypto.reviewDigest(review));
    }
    @Test void reviewSnapshotV1MatchesEthersAbiCoder() {
        var review=new TaskAccountCrypto.Review(UUID.fromString("11111111-2222-4333-8444-555555555555"),
                UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),2,
                UUID.fromString("99999999-8888-4777-8666-555555555555"),"pharmacy-a","qt_a_example",
                BigInteger.valueOf(23_500_000),"0x00000000000000000000000000000000f10aa001",
                "0x84b494ff145a545d286321691a9b4febe6947d6a",Instant.ofEpochSecond(1790790000));
        assertEquals("0x4c3eed53f51c8771edb4396a4b6e1351dfe91217178808ca5a5a8f30ade7d8e2",
                TaskAccountCrypto.taskId(review.taskId()));
        assertEquals(448,(TaskAccountCrypto.reviewEncoded(review).length()-2)/2);
        assertEquals("0x2556ee7edbd2f09d4eb0fceadc57f52084af74bba5f9804e4b4cb6a9638546ed",
                TaskAccountCrypto.reviewDigest(review));
    }
    @Test void mandateHashMatchesPinnedSolidityHashMandateVector() {
        assertEquals("0xd49e0fea684a687d45bf10f4be251d6c0e067be31659b32688dca889af727cd2",
                TaskAccountCrypto.mandateHash(11155111,
                        "0xab86ed8530d3e9d7fb1bae37f5d8ca3d433ba06f",
                        "0x955ebd34d21848bc686dd1b7904b4731fcf3204bd76df60968eb57277a4c7860",
                        "0x09f764fb6907993a34ab0367f8932f49361be7d1601f8d3a83fbe3b5a326c2ce",
                        "0x1390c8745eb49069afd3b89393997e3fa14614f5",
                        "0x183f66622995afd7e16479e48a98b6241858e1ea",
                        "0xe75b54381f7fea97783d9464c94153f89ad19d38",
                        "0x294ccf70e247c689f1cba6a3e4685321780fc151",
                        BigInteger.valueOf(23_500_000),1790707440));
    }
    @Test void mandateApprovalMatchesEthersTypedDataEncoder() {
        var approval=new TaskAccountCrypto.Approval("0x00000000000000000000000000000000fac10002",
                "0x00000000000000000000000000000000fac10001",
                "0x4c3eed53f51c8771edb4396a4b6e1351dfe91217178808ca5a5a8f30ade7d8e2",
                "0x2556ee7edbd2f09d4eb0fceadc57f52084af74bba5f9804e4b4cb6a9638546ed",
                "0x84b494ff145a545d286321691a9b4febe6947d6a",
                "0x00000000000000000000000000000000f10aa001",
                "0x00000000000000000000000000000000fac10003",
                "0x00000000000000000000000000000000fac10004",
                BigInteger.valueOf(23_500_000),1790790000,BigInteger.ZERO,11155111);
        assertEquals("0x44ec433c023f9d3d4aadbf52e160653cdaa0046a2eef72d2ab1e89f588884a91",
                TaskAccountCrypto.digest(TaskAccountCrypto.approvalTypedData(approval)));
    }
}
