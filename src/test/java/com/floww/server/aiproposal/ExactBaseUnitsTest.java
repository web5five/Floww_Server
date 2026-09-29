package com.floww.server.aiproposal;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactBaseUnitsTest {
    @Test void canonicalUint256BoundariesAndHostileStrings() {
        String max = ExactBaseUnits.UINT256_MAX.toString();
        assertEquals(78, max.length());
        assertEquals(ExactBaseUnits.UINT256_MAX, ExactBaseUnits.positive(max));
        assertNull(ExactBaseUnits.positive(ExactBaseUnits.UINT256_MAX.add(BigInteger.ONE).toString()));
        assertNull(ExactBaseUnits.positive("9".repeat(100_000)));
        for (String invalid : new String[] {"", "0", "00", "01", " 1", "1 ", "+1", "-1",
                "1.0", "1e2", "١", "１", "1\n"}) assertNull(ExactBaseUnits.positive(invalid));
        assertEquals(BigInteger.ZERO, ExactBaseUnits.nonNegativeFee("0"));
        assertNull(ExactBaseUnits.nonNegativeFee("00"));
        assertEquals(new BigInteger("9007199254740993"), ExactBaseUnits.positive("9007199254740993"));
    }

    @Test void displayAdaptationRequiresExplicitDecimalsAndNeverRounds() {
        assertEquals("25000000", ExactBaseUnits.displayToPositiveBaseUnits("25", 6));
        assertEquals("23500000", ExactBaseUnits.displayToPositiveBaseUnits("23.5", 6));
        assertEquals("1", ExactBaseUnits.displayToPositiveBaseUnits("0.000001", 6));
        assertEquals("9007199254740993000000", ExactBaseUnits.displayToPositiveBaseUnits("9007199254740993", 6));
        assertNull(ExactBaseUnits.displayToPositiveBaseUnits("0.0000001", 6));
        assertNull(ExactBaseUnits.displayToPositiveBaseUnits("0", 6));
        assertNull(ExactBaseUnits.displayToPositiveBaseUnits("25.0", 0));
        assertNull(ExactBaseUnits.displayToPositiveBaseUnits("025", 6));
        assertNull(ExactBaseUnits.displayToPositiveBaseUnits("25e2", 6));
        assertNull(ExactBaseUnits.displayToPositiveBaseUnits("1".repeat(100_000), 6));
        assertNull(ExactBaseUnits.displayToPositiveBaseUnits("1", 256));
    }
}
