package io.floci.aws.services.iotwireless;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LoRaWAN cryptography: AES-CMAC against RFC 4493, and joins and data frames as devices build and read them. */
class LoRaWanTest {

    static final byte[] KEY = LoRaWan.hex("2b7e151628aed2a6abf7158809cf4f3c");

    @Test
    void cmacMatchesRfc4493() {
        assertEquals("bb1d6929e95937287fa37d129b756746", LoRaWan.hex(LoRaWan.cmac(KEY, new byte[0])));
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c",
                LoRaWan.hex(LoRaWan.cmac(KEY, LoRaWan.hex("6bc1bee22e409f96e93d7e117393172a"))));
        assertEquals("dfa66747de9ae63030ca32611497c827", LoRaWan.hex(LoRaWan.cmac(KEY, LoRaWan.hex(
                "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411"))));
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe", LoRaWan.hex(LoRaWan.cmac(KEY, LoRaWan.hex(
                "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"
                        + "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"))));
    }

    @Test
    void joinRequestsAndAcceptsRoundTrip() {
        byte[] appKey = LoRaWan.hex("000102030405060708090a0b0c0d0e0f");
        byte[] request = LoRaWan.buildJoinRequest("70b3d57ed0000001", "0004a30b001c0530", 0x1234, appKey);
        LoRaWan.JoinRequest parsed = LoRaWan.joinRequest(request);
        assertEquals("70b3d57ed0000001", parsed.joinEui());
        assertEquals("0004a30b001c0530", parsed.devEui());
        assertEquals(0x1234, parsed.devNonce());
        assertTrue(LoRaWan.joinRequestValid(parsed, appKey));
        assertFalse(LoRaWan.joinRequestValid(parsed, KEY));

        byte[] accept = LoRaWan.joinAccept(7, 0x24, LoRaWan.hex("48aabbcc"), 0x02, 0, null, appKey, false, null,
                "70b3d57ed0000001", 0x1234);
        assertEquals(17, accept.length);
        byte[] open = LoRaWan.openJoinAccept(accept, appKey);
        byte[] mic = LoRaWan.cmac(appKey, Arrays.copyOf(open, 13));
        assertArrayEquals(Arrays.copyOf(mic, 4), Arrays.copyOfRange(open, 13, 17));
        assertEquals(7, open[1]);
        assertEquals("48aabbcc", LoRaWan.hex(LoRaWan.reverse(Arrays.copyOfRange(open, 7, 11))));
    }

    @Test
    void dataFramesAreSignedEncryptedAndCounted() {
        LoRaWan.SessionKeys keys = LoRaWan.deriveV10(LoRaWan.hex("000102030405060708090a0b0c0d0e0f"), 7, 0x24, 0x1234);
        byte[] payload = "temperature=21".getBytes(StandardCharsets.UTF_8);
        byte[] frame = LoRaWan.buildUplink(true, "48aabbcc", 70_000, 3, payload, keys, false);
        LoRaWan.DataFrame data = LoRaWan.dataFrame(frame);
        assertEquals(LoRaWan.CONFIRMED_UP, data.mType());
        assertEquals("48aabbcc", data.devAddr());
        assertEquals(70_000 & 0xFFFF, data.fCnt16());
        assertEquals(3, data.fPort());
        long full = LoRaWan.fullCounter(69_999, data.fCnt16());
        assertEquals(70_000, full);
        assertEquals(data.mic(), LoRaWan.uplinkMic(frame, "48aabbcc", full, keys, false, 0, 0, 0));
        assertArrayEquals(payload, LoRaWan.crypt(keys.appSKey(), 0, "48aabbcc", full, data.payload()));

        byte[] down = LoRaWan.buildDownlink(false, "48aabbcc", 5, true, false, new byte[] {0x02, 20, 1}, 1,
                new byte[] {9, 8, 7}, keys, false, 0);
        LoRaWan.DataFrame parsed = LoRaWan.dataFrame(down);
        assertTrue(parsed.ack());
        assertArrayEquals(new byte[] {0x02, 20, 1}, parsed.fOpts());
        assertEquals(parsed.mic(), LoRaWan.downlinkMic(down, "48aabbcc", 5, keys, false, 0));
        assertArrayEquals(new byte[] {9, 8, 7}, LoRaWan.crypt(keys.appSKey(), 1, "48aabbcc", 5, parsed.payload()));
    }

    @Test
    void v11KeysDiffer() {
        byte[] nwkKey = LoRaWan.hex("0f0e0d0c0b0a09080706050403020100");
        byte[] appKey = LoRaWan.hex("000102030405060708090a0b0c0d0e0f");
        LoRaWan.SessionKeys keys = LoRaWan.deriveV11(nwkKey, appKey, 1, "70b3d57ed0000001", 2);
        assertFalse(Arrays.equals(keys.fNwkSIntKey(), keys.sNwkSIntKey()));
        assertFalse(Arrays.equals(keys.sNwkSIntKey(), keys.nwkSEncKey()));
        byte[] frame = LoRaWan.buildUplink(false, "26011bda", 1, 1, new byte[] {1}, keys, true);
        LoRaWan.DataFrame data = LoRaWan.dataFrame(frame);
        assertEquals(data.mic(), LoRaWan.uplinkMic(frame, "26011bda", 1, keys, true, 0, 0, 0));
    }
}
