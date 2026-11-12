package io.floci.aws.services.iotwireless;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * LoRaWAN 1.0.x and 1.1 frames and their cryptography (LoRaWAN Specification 1.0.4 and 1.1): AES-CMAC message
 * integrity codes, join request checks, join accept encryption, session key derivation, frame payload encryption, and
 * the 32-bit frame counter a 16-bit counter on the air stands for.
 */
final class LoRaWan {

    static final int JOIN_REQUEST = 0;
    static final int JOIN_ACCEPT = 1;
    static final int UNCONFIRMED_UP = 2;
    static final int UNCONFIRMED_DOWN = 3;
    static final int CONFIRMED_UP = 4;
    static final int CONFIRMED_DOWN = 5;

    private LoRaWan() {
    }

    static byte[] hex(String text) {
        return HexFormat.of().parseHex(text.replace("-", "").replace(":", "").trim());
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static byte[] reverse(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[bytes.length - 1 - i];
        }
        return out;
    }

    // ---------------------------------------------------------------- AES

    static byte[] aesEncrypt(byte[] key, byte[] block) {
        return aes(Cipher.ENCRYPT_MODE, key, block);
    }

    static byte[] aesDecrypt(byte[] key, byte[] block) {
        return aes(Cipher.DECRYPT_MODE, key, block);
    }

    private static byte[] aes(int mode, byte[] key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES failed", e);
        }
    }

    /** AES-CMAC (RFC 4493). */
    static byte[] cmac(byte[] key, byte[] message) {
        byte[] l = aesEncrypt(key, new byte[16]);
        byte[] k1 = shift(l);
        byte[] k2 = shift(k1);
        int blocks = Math.max(1, (message.length + 15) / 16);
        boolean complete = message.length > 0 && message.length % 16 == 0;
        byte[] last = new byte[16];
        int offset = (blocks - 1) * 16;
        if (complete) {
            for (int i = 0; i < 16; i++) {
                last[i] = (byte) (message[offset + i] ^ k1[i]);
            }
        } else {
            int remaining = message.length - offset;
            for (int i = 0; i < 16; i++) {
                byte b = i < remaining ? message[offset + i] : i == remaining ? (byte) 0x80 : 0;
                last[i] = (byte) (b ^ k2[i]);
            }
        }
        byte[] x = new byte[16];
        for (int b = 0; b < blocks - 1; b++) {
            byte[] y = new byte[16];
            for (int i = 0; i < 16; i++) {
                y[i] = (byte) (x[i] ^ message[b * 16 + i]);
            }
            x = aesEncrypt(key, y);
        }
        byte[] y = new byte[16];
        for (int i = 0; i < 16; i++) {
            y[i] = (byte) (x[i] ^ last[i]);
        }
        return aesEncrypt(key, y);
    }

    private static byte[] shift(byte[] in) {
        byte[] out = new byte[16];
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int b = in[i] & 0xFF;
            out[i] = (byte) ((b << 1) | carry);
            carry = (b >> 7) & 1;
        }
        if ((in[0] & 0x80) != 0) {
            out[15] ^= (byte) 0x87;
        }
        return out;
    }

    static int mic(byte[] cmac) {
        return (cmac[0] & 0xFF) | (cmac[1] & 0xFF) << 8 | (cmac[2] & 0xFF) << 16 | (cmac[3] & 0xFF) << 24;
    }

    static byte[] micBytes(int mic) {
        return new byte[] {(byte) mic, (byte) (mic >> 8), (byte) (mic >> 16), (byte) (mic >> 24)};
    }

    // ---------------------------------------------------------------- joins

    /** A join request: JoinEUI, DevEUI (big-endian hex) and DevNonce, as the radio carried them. */
    record JoinRequest(byte[] frame, String joinEui, String devEui, int devNonce, int mic) {
    }

    static JoinRequest joinRequest(byte[] frame) {
        if (frame.length != 23 || (frame[0] & 0xE0) != 0) {
            throw new IllegalArgumentException("A join request is 23 bytes with the MType 000.");
        }
        String joinEui = hex(reverse(Arrays.copyOfRange(frame, 1, 9)));
        String devEui = hex(reverse(Arrays.copyOfRange(frame, 9, 17)));
        int devNonce = (frame[17] & 0xFF) | (frame[18] & 0xFF) << 8;
        int mic = mic(Arrays.copyOfRange(frame, 19, 23));
        return new JoinRequest(frame, joinEui, devEui, devNonce, mic);
    }

    /** Whether a join request's MIC is the one the root key (AppKey in 1.0.x, NwkKey in 1.1) gives. */
    static boolean joinRequestValid(JoinRequest request, byte[] rootKey) {
        return mic(cmac(rootKey, Arrays.copyOfRange(request.frame(), 0, 19))) == request.mic();
    }

    /** The bytes of a join request built by a device: for tests and simulated joins. */
    static byte[] buildJoinRequest(String joinEui, String devEui, int devNonce, byte[] rootKey) {
        byte[] frame = new byte[23];
        frame[0] = 0;
        System.arraycopy(reverse(hex(joinEui)), 0, frame, 1, 8);
        System.arraycopy(reverse(hex(devEui)), 0, frame, 9, 8);
        frame[17] = (byte) devNonce;
        frame[18] = (byte) (devNonce >> 8);
        System.arraycopy(micBytes(mic(cmac(rootKey, Arrays.copyOfRange(frame, 0, 19)))), 0, frame, 19, 4);
        return frame;
    }

    /**
     * A join accept, encrypted as devices expect it: the server encrypts with an AES decrypt so the device decrypts
     * with an AES encrypt. In 1.1 (OptNeg set in DLSettings) the MIC covers JoinReqType, JoinEUI and DevNonce with
     * JSIntKey; in 1.0.x it covers the frame with AppKey.
     */
    static byte[] joinAccept(int joinNonce, int netId, byte[] devAddr, int dlSettings, int rxDelay, byte[] cfList,
                             byte[] rootKey, boolean v11, byte[] jsIntKey, String joinEui, int devNonce) {
        byte[] fields = new byte[12 + (cfList == null ? 0 : 16)];
        fields[0] = (byte) joinNonce;
        fields[1] = (byte) (joinNonce >> 8);
        fields[2] = (byte) (joinNonce >> 16);
        fields[3] = (byte) netId;
        fields[4] = (byte) (netId >> 8);
        fields[5] = (byte) (netId >> 16);
        System.arraycopy(reverse(devAddr), 0, fields, 6, 4);
        fields[10] = (byte) (dlSettings | (v11 ? 0x80 : 0));
        fields[11] = (byte) rxDelay;
        if (cfList != null) {
            System.arraycopy(cfList, 0, fields, 12, 16);
        }
        byte[] signed = new byte[1 + fields.length];
        signed[0] = 0x20;
        System.arraycopy(fields, 0, signed, 1, fields.length);
        byte[] mic;
        if (v11) {
            byte[] micInput = new byte[1 + 8 + 2 + signed.length];
            micInput[0] = (byte) 0xFF;
            System.arraycopy(reverse(hex(joinEui)), 0, micInput, 1, 8);
            micInput[9] = (byte) devNonce;
            micInput[10] = (byte) (devNonce >> 8);
            System.arraycopy(signed, 0, micInput, 11, signed.length);
            mic = Arrays.copyOf(cmac(jsIntKey, micInput), 4);
        } else {
            mic = Arrays.copyOf(cmac(rootKey, signed), 4);
        }
        byte[] plain = concat(fields, mic, 4);
        byte[] encrypted = aesDecrypt(rootKey, plain);
        byte[] frame = new byte[1 + encrypted.length];
        frame[0] = 0x20;
        System.arraycopy(encrypted, 0, frame, 1, encrypted.length);
        return frame;
    }

    /** A join accept as a device reads it: decrypted with an AES encrypt, for tests and simulated joins. */
    static byte[] openJoinAccept(byte[] frame, byte[] rootKey) {
        byte[] plain = aesEncrypt(rootKey, Arrays.copyOfRange(frame, 1, frame.length));
        byte[] out = new byte[frame.length];
        out[0] = frame[0];
        System.arraycopy(plain, 0, out, 1, plain.length);
        return out;
    }

    /** Session keys a join derives. In 1.0.x FNwkSIntKey, SNwkSIntKey and NwkSEncKey are all the NwkSKey. */
    record SessionKeys(byte[] fNwkSIntKey, byte[] sNwkSIntKey, byte[] nwkSEncKey, byte[] appSKey) {
    }

    static SessionKeys deriveV10(byte[] appKey, int joinNonce, int netId, int devNonce) {
        byte[] nwkSKey = derive(appKey, 0x01, nonce3(joinNonce), nonce3(netId), devNonce);
        byte[] appSKey = derive(appKey, 0x02, nonce3(joinNonce), nonce3(netId), devNonce);
        return new SessionKeys(nwkSKey, nwkSKey, nwkSKey, appSKey);
    }

    static SessionKeys deriveV11(byte[] nwkKey, byte[] appKey, int joinNonce, String joinEui, int devNonce) {
        byte[] eui = reverse(hex(joinEui));
        return new SessionKeys(deriveEui(nwkKey, 0x01, joinNonce, eui, devNonce),
                deriveEui(nwkKey, 0x03, joinNonce, eui, devNonce), deriveEui(nwkKey, 0x04, joinNonce, eui, devNonce),
                deriveEui(appKey, 0x02, joinNonce, eui, devNonce));
    }

    /** JSIntKey of a 1.1 device: aes128(NwkKey, 0x06 | DevEUI | pad16). */
    static byte[] jsIntKey(byte[] nwkKey, String devEui) {
        byte[] block = new byte[16];
        block[0] = 0x06;
        System.arraycopy(reverse(hex(devEui)), 0, block, 1, 8);
        return aesEncrypt(nwkKey, block);
    }

    private static byte[] nonce3(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16)};
    }

    private static byte[] derive(byte[] key, int type, byte[] joinNonce, byte[] netId, int devNonce) {
        byte[] block = new byte[16];
        block[0] = (byte) type;
        System.arraycopy(joinNonce, 0, block, 1, 3);
        System.arraycopy(netId, 0, block, 4, 3);
        block[7] = (byte) devNonce;
        block[8] = (byte) (devNonce >> 8);
        return aesEncrypt(key, block);
    }

    private static byte[] deriveEui(byte[] key, int type, int joinNonce, byte[] joinEui, int devNonce) {
        byte[] block = new byte[16];
        block[0] = (byte) type;
        System.arraycopy(nonce3(joinNonce), 0, block, 1, 3);
        System.arraycopy(joinEui, 0, block, 4, 8);
        block[12] = (byte) devNonce;
        block[13] = (byte) (devNonce >> 8);
        return aesEncrypt(key, block);
    }

    // ---------------------------------------------------------------- data frames

    /** A data frame's fields: DevAddr (big-endian hex), FCtrl, the 16-bit FCnt, FOpts, FPort (-1 if none). */
    record DataFrame(int mType, String devAddr, int fCtrl, int fCnt16, byte[] fOpts, int fPort, byte[] payload,
                     int mic, byte[] frame) {
        boolean ack() {
            return (fCtrl & 0x20) != 0;
        }

        boolean adr() {
            return (fCtrl & 0x80) != 0;
        }

        boolean confirmed() {
            return mType == CONFIRMED_UP || mType == CONFIRMED_DOWN;
        }
    }

    static DataFrame dataFrame(byte[] frame) {
        if (frame.length < 12) {
            throw new IllegalArgumentException("A data frame is at least 12 bytes.");
        }
        int mType = (frame[0] & 0xE0) >> 5;
        String devAddr = hex(reverse(Arrays.copyOfRange(frame, 1, 5)));
        int fCtrl = frame[5] & 0xFF;
        int fCnt = (frame[6] & 0xFF) | (frame[7] & 0xFF) << 8;
        int optsLength = fCtrl & 0x0F;
        if (8 + optsLength + 4 > frame.length) {
            throw new IllegalArgumentException("The frame's FOpts run past its end.");
        }
        byte[] fOpts = Arrays.copyOfRange(frame, 8, 8 + optsLength);
        int at = 8 + optsLength;
        int fPort = -1;
        byte[] payload = new byte[0];
        if (at < frame.length - 4) {
            fPort = frame[at] & 0xFF;
            payload = Arrays.copyOfRange(frame, at + 1, frame.length - 4);
        }
        int mic = mic(Arrays.copyOfRange(frame, frame.length - 4, frame.length));
        return new DataFrame(mType, devAddr, fCtrl, fCnt, fOpts, fPort, payload, mic, frame);
    }

    /** The 32-bit counter a 16-bit one stands for, given the last one seen. */
    static long fullCounter(long last, int sixteen) {
        long candidate = (last & 0xFFFF0000L) | sixteen;
        if (candidate < last) {
            candidate += 0x10000;
        }
        return candidate;
    }

    private static byte[] b0(int direction, String devAddr, long fCnt, int length, int confFCnt, int txDr, int txCh) {
        byte[] block = new byte[16];
        block[0] = 0x49;
        block[1] = (byte) confFCnt;
        block[2] = (byte) (confFCnt >> 8);
        block[3] = (byte) txDr;
        block[4] = (byte) txCh;
        block[5] = (byte) direction;
        System.arraycopy(reverse(hex(devAddr)), 0, block, 6, 4);
        block[10] = (byte) fCnt;
        block[11] = (byte) (fCnt >> 8);
        block[12] = (byte) (fCnt >> 16);
        block[13] = (byte) (fCnt >> 24);
        block[15] = (byte) length;
        return block;
    }

    private static byte[] concat(byte[] a, byte[] b, int bLength) {
        byte[] out = new byte[a.length + bLength];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, bLength);
        return out;
    }

    /** An uplink's MIC: 1.0.x with NwkSKey; 1.1 half from SNwkSIntKey (B1) and half from FNwkSIntKey (B0). */
    static int uplinkMic(byte[] frame, String devAddr, long fCnt, SessionKeys keys, boolean v11, int confFCnt,
                         int txDr, int txCh) {
        int length = frame.length - 4;
        byte[] cmacF = cmac(keys.fNwkSIntKey(), concat(b0(0, devAddr, fCnt, length, 0, 0, 0), frame, length));
        if (!v11) {
            return mic(cmacF);
        }
        byte[] b1 = b0(0, devAddr, fCnt, length, confFCnt, txDr, txCh);
        b1[0] = 0x49;
        byte[] cmacS = cmac(keys.sNwkSIntKey(), concat(b1, frame, length));
        return mic(new byte[] {cmacS[0], cmacS[1], cmacF[0], cmacF[1]});
    }

    static int downlinkMic(byte[] frame, String devAddr, long fCnt, SessionKeys keys, boolean v11, int confFCnt) {
        int length = frame.length - 4;
        byte[] block = b0(1, devAddr, fCnt, length, v11 ? confFCnt : 0, 0, 0);
        return mic(cmac(keys.sNwkSIntKey(), concat(block, frame, length)));
    }

    /** FRMPayload encryption, which is its own inverse: AppSKey for FPort > 0, NwkSEncKey for FPort 0. */
    static byte[] crypt(byte[] key, int direction, String devAddr, long fCnt, byte[] payload) {
        byte[] out = new byte[payload.length];
        byte[] address = reverse(hex(devAddr));
        for (int i = 0; i < payload.length; i += 16) {
            byte[] a = new byte[16];
            a[0] = 0x01;
            a[5] = (byte) direction;
            System.arraycopy(address, 0, a, 6, 4);
            a[10] = (byte) fCnt;
            a[11] = (byte) (fCnt >> 8);
            a[12] = (byte) (fCnt >> 16);
            a[13] = (byte) (fCnt >> 24);
            a[15] = (byte) (i / 16 + 1);
            byte[] s = aesEncrypt(key, a);
            for (int j = 0; j < 16 && i + j < payload.length; j++) {
                out[i + j] = (byte) (payload[i + j] ^ s[j]);
            }
        }
        return out;
    }

    /** A downlink data frame, encrypted and signed. */
    static byte[] buildDownlink(boolean confirmed, String devAddr, long fCnt, boolean ack, boolean fPending,
                                byte[] fOpts, int fPort, byte[] payload, SessionKeys keys, boolean v11, int confFCnt) {
        int optsLength = fOpts == null ? 0 : fOpts.length;
        int payloadLength = fPort < 0 ? 0 : 1 + payload.length;
        byte[] frame = new byte[8 + optsLength + payloadLength + 4];
        frame[0] = (byte) ((confirmed ? CONFIRMED_DOWN : UNCONFIRMED_DOWN) << 5);
        System.arraycopy(reverse(hex(devAddr)), 0, frame, 1, 4);
        frame[5] = (byte) ((ack ? 0x20 : 0) | (fPending ? 0x10 : 0) | optsLength);
        frame[6] = (byte) fCnt;
        frame[7] = (byte) (fCnt >> 8);
        if (optsLength > 0) {
            System.arraycopy(fOpts, 0, frame, 8, optsLength);
        }
        if (fPort >= 0) {
            frame[8 + optsLength] = (byte) fPort;
            byte[] key = fPort == 0 ? keys.nwkSEncKey() : keys.appSKey();
            byte[] encrypted = crypt(key, 1, devAddr, fCnt, payload);
            System.arraycopy(encrypted, 0, frame, 9 + optsLength, encrypted.length);
        }
        System.arraycopy(micBytes(downlinkMic(frame, devAddr, fCnt, keys, v11, confFCnt)), 0, frame,
                frame.length - 4, 4);
        return frame;
    }

    /** An uplink data frame as a device builds it: for tests and simulated uplinks. */
    static byte[] buildUplink(boolean confirmed, String devAddr, long fCnt, int fPort, byte[] payload,
                              SessionKeys keys, boolean v11) {
        byte[] frame = new byte[8 + 1 + payload.length + 4];
        frame[0] = (byte) ((confirmed ? CONFIRMED_UP : UNCONFIRMED_UP) << 5);
        System.arraycopy(reverse(hex(devAddr)), 0, frame, 1, 4);
        frame[5] = 0;
        frame[6] = (byte) fCnt;
        frame[7] = (byte) (fCnt >> 8);
        frame[8] = (byte) fPort;
        byte[] key = fPort == 0 ? keys.nwkSEncKey() : keys.appSKey();
        System.arraycopy(crypt(key, 0, devAddr, fCnt, payload), 0, frame, 9, payload.length);
        System.arraycopy(micBytes(uplinkMic(frame, devAddr, fCnt, keys, v11, 0, 0, 0)), 0, frame, frame.length - 4,
                4);
        return frame;
    }
}
