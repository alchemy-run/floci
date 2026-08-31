package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.core.common.AwsException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Symmetric / DUKPT / PIN / CVV / HMAC primitives used by the Payment
 * Cryptography emulator. Operations are internally consistent so encrypt then
 * decrypt (and generate then verify) round-trip; they are not a PCI HSM.
 */
final class PaymentCryptographyCrypto {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final byte[] DUKPT_KEY_REGISTER_XOR = hex("C0C0C0C000000000C0C0C0C000000000");
    private static final byte[] DUKPT_DATA_VARIANT = hex("0000000000FF00000000000000FF0000");

    private PaymentCryptographyCrypto() {
    }

    static byte[] randomBytes(SecureRandom random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    static int keyMaterialLength(String algorithm) {
        return switch (algorithm == null ? "" : algorithm) {
            case "AES_128" -> 16;
            case "AES_192" -> 24;
            case "AES_256" -> 32;
            case "TDES_2KEY" -> 16;
            case "TDES_3KEY" -> 24;
            case "HMAC_SHA224" -> 28;
            case "HMAC_SHA256" -> 32;
            case "HMAC_SHA384" -> 48;
            case "HMAC_SHA512" -> 64;
            default -> 16;
        };
    }

    static boolean isTdes(String algorithm) {
        return algorithm != null && algorithm.startsWith("TDES");
    }

    static boolean isAes(String algorithm) {
        return algorithm != null && algorithm.startsWith("AES");
    }

    static boolean isHmac(String algorithm) {
        return algorithm != null && algorithm.startsWith("HMAC");
    }

    static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    static byte[] hex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        String cleaned = hex.replace(" ", "");
        if ((cleaned.length() & 1) != 0) {
            throw new AwsException("ValidationException", "Hex value must have even length.", 400);
        }
        try {
            return HexFormat.of().parseHex(cleaned);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Value is not valid hex.", 400);
        }
    }

    static String keyCheckValue(byte[] keyMaterial, String algorithm, String kcvAlgorithm) {
        try {
            if (isHmac(algorithm)) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(keyMaterial, "HmacSHA256"));
                return hex(Arrays.copyOf(mac.doFinal(new byte[16]), 3));
            }
            byte[] block = new byte[isTdes(algorithm) ? 8 : 16];
            byte[] encrypted = encryptSymmetric(keyMaterial, algorithm, "ECB", block, null);
            return hex(Arrays.copyOf(encrypted, 3));
        } catch (AwsException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new AwsException("InternalServerException", "Unable to compute key check value.", 500);
        }
    }

    static byte[] encryptSymmetric(byte[] key, String algorithm, String mode, byte[] plaintext, byte[] iv) {
        return cipherSymmetric(Cipher.ENCRYPT_MODE, key, algorithm, mode, plaintext, iv);
    }

    static byte[] decryptSymmetric(byte[] key, String algorithm, String mode, byte[] ciphertext, byte[] iv) {
        return cipherSymmetric(Cipher.DECRYPT_MODE, key, algorithm, mode, ciphertext, iv);
    }

    private static byte[] cipherSymmetric(int opmode, byte[] key, String algorithm, String mode,
                                          byte[] data, byte[] iv) {
        String modeName = mode == null || mode.isBlank() ? "CBC" : mode.toUpperCase();
        int block = isTdes(algorithm) ? 8 : 16;
        if (!"ECB".equals(modeName) && data.length % block != 0) {
            throw new AwsException("ValidationException",
                    "Data length must be a multiple of the cipher block size (" + block + ").", 400);
        }
        try {
            if (isTdes(algorithm)) {
                SecretKeySpec spec = new SecretKeySpec(tdes24(key), "DESede");
                if ("ECB".equals(modeName)) {
                    Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
                    cipher.init(opmode, spec);
                    return cipher.doFinal(data);
                }
                Cipher cipher = Cipher.getInstance("DESede/" + modeName + "/NoPadding");
                byte[] effectiveIv = ivOrZeros(iv, 8);
                cipher.init(opmode, spec, new IvParameterSpec(effectiveIv));
                return cipher.doFinal(data);
            }
            SecretKeySpec spec = new SecretKeySpec(key, "AES");
            if ("ECB".equals(modeName)) {
                Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
                cipher.init(opmode, spec);
                return cipher.doFinal(data);
            }
            Cipher cipher = Cipher.getInstance("AES/" + modeName + "/NoPadding");
            byte[] effectiveIv = ivOrZeros(iv, 16);
            cipher.init(opmode, spec, new IvParameterSpec(effectiveIv));
            return cipher.doFinal(data);
        } catch (AwsException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new AwsException("ValidationException", "Symmetric cipher failed: " + e.getMessage(), 400);
        }
    }

    static byte[] dukptSessionKey(byte[] bdk, byte[] ksn) {
        byte[] ipek = deriveIpek(bdk, ksn);
        return deriveDukptKey(ipek, ksn);
    }

    static byte[] dukptDataKey(byte[] bdk, byte[] ksn) {
        return xor(dukptSessionKey(bdk, ksn), DUKPT_DATA_VARIANT);
    }

    static byte[] deriveIpek(byte[] bdk, byte[] ksn10) {
        byte[] ksn = Arrays.copyOf(ksn10, 10);
        clearCounter(ksn);
        byte[] ksn8 = Arrays.copyOf(ksn, 8);
        byte[] left = encryptSymmetric(bdk, "TDES_2KEY", "ECB", ksn8, null);
        byte[] right = encryptSymmetric(xor(bdk, DUKPT_KEY_REGISTER_XOR), "TDES_2KEY", "ECB", ksn8, null);
        return concat(left, right);
    }

    static byte[] deriveDukptKey(byte[] ipek, byte[] ksn10) {
        int counter = dukptCounter(ksn10);
        byte[] key = Arrays.copyOf(ipek, 16);
        byte[] register = Arrays.copyOf(ksn10, 10);
        clearCounter(register);
        int mask = 0x100000;
        while (mask != 0) {
            if ((counter & mask) != 0) {
                orCounter(register, mask);
                key = dukptNonReversibleKey(key, Arrays.copyOf(register, 8));
            }
            mask >>= 1;
        }
        return key;
    }

    private static byte[] dukptNonReversibleKey(byte[] key, byte[] ksn8) {
        byte[] left = Arrays.copyOfRange(key, 0, 8);
        byte[] right = Arrays.copyOfRange(key, 8, 16);
        byte[] newRight = desEncrypt(left, xor(ksn8, right));
        newRight = xor(newRight, right);
        byte[] xorLeft = xor(left, hex("C0C0C0C000000000"));
        byte[] xorRight = xor(right, hex("C0C0C0C000000000"));
        byte[] newLeft = desEncrypt(xorLeft, xor(ksn8, xorRight));
        newLeft = xor(newLeft, xorRight);
        return concat(newLeft, newRight);
    }

    private static byte[] desEncrypt(byte[] key8, byte[] block8) {
        try {
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key8, "DES"));
            return cipher.doFinal(block8);
        } catch (GeneralSecurityException e) {
            throw new AwsException("InternalServerException", "DES operation failed.", 500);
        }
    }

    static byte[] hmac(byte[] key, String algorithm, byte[] message) {
        String macAlg = switch (algorithm == null ? "HMAC_SHA256" : algorithm) {
            case "HMAC_SHA224" -> "HmacSHA224";
            case "HMAC_SHA384" -> "HmacSHA384";
            case "HMAC_SHA512" -> "HmacSHA512";
            default -> "HmacSHA256";
        };
        try {
            Mac mac = Mac.getInstance(macAlg);
            mac.init(new SecretKeySpec(key, macAlg));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new AwsException("ValidationException", "HMAC generation failed.", 400);
        }
    }

    static boolean constantEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    /**
     * Visa-style CVV2: TDES over PAN + expiry (MMYY) + service code 000, then
     * decimalize the ciphertext to three digits.
     */
    static String cvv2(byte[] cvk, String pan, String expiry) {
        String digits = digitsOnly(pan) + digitsOnly(expiry) + "000";
        while (digits.length() < 16) {
            digits += "0";
        }
        if (digits.length() > 16) {
            digits = digits.substring(0, 16);
        }
        byte[] block = packedBcd(digits);
        byte[] encrypted = encryptSymmetric(cvk, "TDES_2KEY", "ECB", block, null);
        return decimalize(hex(encrypted), 3);
    }

    static String randomPin(SecureRandom random, int length) {
        int pinLength = length <= 0 ? 4 : length;
        StringBuilder pin = new StringBuilder(pinLength);
        for (int i = 0; i < pinLength; i++) {
            pin.append(random.nextInt(10));
        }
        return pin.toString();
    }

    /**
     * Visa PVV: encrypt (12-digit PAN + PIN + PVKI) under the PVK and decimalize.
     */
    static String visaPvv(byte[] pvk, String pan, String pin, int pvki) {
        String pan12 = pan12(pan);
        String tsp = (pan12 + pin + (pvki % 10) + "FFFFFFFFFFFFFFFF");
        tsp = tsp.substring(0, 16);
        byte[] block = packedBcd(tsp);
        byte[] encrypted = encryptSymmetric(pvk, "TDES_2KEY", "ECB", block, null);
        return decimalize(hex(encrypted), 4);
    }

    static byte[] isoFormat0PinBlock(String pin, String pan) {
        int pinLength = pin.length();
        StringBuilder p1 = new StringBuilder();
        p1.append('0');
        p1.append(Integer.toHexString(pinLength).toUpperCase());
        p1.append(pin);
        while (p1.length() < 16) {
            p1.append('F');
        }
        String p2 = "0000" + pan12(pan);
        return xor(hex(p1.toString()), hex(p2));
    }

    static String pinFromIsoFormat0(byte[] clearBlock, String pan) {
        String p2 = "0000" + pan12(pan);
        byte[] p1 = xor(clearBlock, hex(p2));
        String hexP1 = hex(p1);
        int length = Character.digit(hexP1.charAt(1), 16);
        if (length < 4 || length > 12 || hexP1.length() < 2 + length) {
            throw new AwsException("ValidationException", "PIN block is not a valid ISO format 0 block.", 400);
        }
        return hexP1.substring(2, 2 + length);
    }

    static String pan12(String pan) {
        String digits = digitsOnly(pan);
        if (digits.length() < 13) {
            throw new AwsException("ValidationException", "PrimaryAccountNumber must be at least 13 digits.", 400);
        }
        // 12 digits excluding the check digit (rightmost), taken from the right.
        return digits.substring(digits.length() - 13, digits.length() - 1);
    }

    static byte[] tdes24(byte[] key) {
        if (key.length == 24) {
            return key;
        }
        if (key.length == 16) {
            byte[] out = new byte[24];
            System.arraycopy(key, 0, out, 0, 16);
            System.arraycopy(key, 0, out, 16, 8);
            return out;
        }
        throw new AwsException("ValidationException", "TDES key material must be 16 or 24 bytes.", 400);
    }

    static byte[] ivOrZeros(byte[] iv, int block) {
        if (iv == null || iv.length == 0) {
            return new byte[block];
        }
        if (iv.length != block) {
            throw new AwsException("ValidationException",
                    "InitializationVector must be " + (block * 2) + " hex characters.", 400);
        }
        return iv;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] xor(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    static String digitsOnly(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String decimalize(String hex, int length) {
        StringBuilder digits = new StringBuilder(length);
        for (int i = 0; i < hex.length() && digits.length() < length; i++) {
            char c = hex.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() < length) {
            for (int i = 0; i < hex.length() && digits.length() < length; i++) {
                char c = hex.charAt(i);
                if (c >= 'A' && c <= 'F') {
                    digits.append((char) ('0' + (c - 'A')));
                }
            }
        }
        while (digits.length() < length) {
            digits.append('0');
        }
        return digits.toString();
    }

    static byte[] packedBcd(String digits) {
        byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(digits.charAt(i * 2), 16);
            int lo = Character.digit(digits.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static void clearCounter(byte[] ksn10) {
        ksn10[7] &= (byte) 0xE0;
        ksn10[8] = 0;
        ksn10[9] = 0;
    }

    private static int dukptCounter(byte[] ksn10) {
        return ((ksn10[7] & 0x1F) << 16) | ((ksn10[8] & 0xFF) << 8) | (ksn10[9] & 0xFF);
    }

    private static void orCounter(byte[] ksn10, int mask) {
        ksn10[7] |= (byte) ((mask >> 16) & 0x1F);
        ksn10[8] |= (byte) ((mask >> 8) & 0xFF);
        ksn10[9] |= (byte) (mask & 0xFF);
    }
}
