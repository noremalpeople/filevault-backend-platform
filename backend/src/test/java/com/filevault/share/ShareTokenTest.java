package com.filevault.share;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShareTokenTest {
    @Test
    void createsUrlSafeTokensFromThirtyTwoRandomBytes() {
        String first = ShareToken.newToken();
        assertTrue(ShareToken.validFormat(first));
        assertEquals(32, Base64.getUrlDecoder().decode(first).length);
        assertNotEquals(first, ShareToken.newToken());
        assertFalse(ShareToken.validFormat("not-a-token"));
    }

    @Test
    void hashesToFixedHexWithoutRetainingRawToken() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ShareToken.digest("abc"));
        assertEquals(64, ShareToken.digest(ShareToken.newToken()).length());
    }
}
