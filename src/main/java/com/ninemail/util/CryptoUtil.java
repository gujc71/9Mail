package com.ninemail.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * SHA-256 crypto and authentication utilities
 */
public final class CryptoUtil {

    private CryptoUtil() {}

    /**
     * Create SHA-256 hash
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Verify SHA-256 hash
     */
    public static boolean verifySha256(String plainText, String hash) {
        return sha256(plainText).equalsIgnoreCase(hash);
    }

    /**
     * Strip angle brackets (<>) from an email address
     */
    public static String stripAngleBrackets(String email) {
        if (email == null) return null;
        String stripped = email.trim();
        if (stripped.startsWith("<")) stripped = stripped.substring(1);
        if (stripped.endsWith(">")) stripped = stripped.substring(0, stripped.length() - 1);
        return stripped.trim();
    }

    /**
     * Base64 decode
     */
    public static String decodeBase64(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /**
     * Base64 encode
     */
    public static String encodeBase64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode AUTH PLAIN: \0username\0password
     */
    public static String[] decodeAuthPlain(String encoded) {
        String decoded = decodeBase64(encoded);
        String[] parts = decoded.split("\0");
        if (parts.length == 3) {
            return new String[]{parts[1], parts[2]}; // username, password
        } else if (parts.length == 2) {
            return new String[]{parts[0], parts[1]};
        }
        return null;
    }

    /**
     * Extract domain from an email address
     */
    public static String extractDomain(String email) {
        if (email == null || !email.contains("@")) return null;
        return email.substring(email.lastIndexOf('@') + 1).toLowerCase();
    }

    /**
     * Extract local part from an email address
     */
    public static String extractLocalPart(String email) {
        if (email == null || !email.contains("@")) return email;
        return email.substring(0, email.lastIndexOf('@'));
    }
}
