package com.ninemail.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CryptoUtil unit tests
 */
class CryptoUtilTest {

    @Test
    @DisplayName("Create and verify SHA-256 hash")
    void testSha256() {
        String input = "password123";
        String hash = CryptoUtil.sha256(input);

        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64); // SHA-256 is 64 hex characters
        assertThat(CryptoUtil.verifySha256(input, hash)).isTrue();
        assertThat(CryptoUtil.verifySha256("wrongpassword", hash)).isFalse();
    }

    @Test
    @DisplayName("Strip angle brackets (<>)")
    void testStripAngleBrackets() {
        assertThat(CryptoUtil.stripAngleBrackets("<user@test.com>")).isEqualTo("user@test.com");
        assertThat(CryptoUtil.stripAngleBrackets("user@test.com")).isEqualTo("user@test.com");
        assertThat(CryptoUtil.stripAngleBrackets("<user@test.com")).isEqualTo("user@test.com");
        assertThat(CryptoUtil.stripAngleBrackets(null)).isNull();
    }

    @Test
    @DisplayName("Base64 encode/decode")
    void testBase64() {
        String original = "Hello, World!";
        String encoded = CryptoUtil.encodeBase64(original);
        String decoded = CryptoUtil.decodeBase64(encoded);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("Decode AUTH PLAIN: \\0username\\0password")
    void testDecodeAuthPlain() {
        String plain = "\0testuser@localhost\0mypassword";
        String encoded = CryptoUtil.encodeBase64(plain);
        String[] result = CryptoUtil.decodeAuthPlain(encoded);

        assertThat(result).isNotNull();
        assertThat(result[0]).isEqualTo("testuser@localhost");
        assertThat(result[1]).isEqualTo("mypassword");
    }

    @Test
    @DisplayName("Extract email domain")
    void testExtractDomain() {
        assertThat(CryptoUtil.extractDomain("user@example.com")).isEqualTo("example.com");
        assertThat(CryptoUtil.extractDomain("user@EXAMPLE.COM")).isEqualTo("example.com");
        assertThat(CryptoUtil.extractDomain("noatsign")).isNull();
        assertThat(CryptoUtil.extractDomain(null)).isNull();
    }

    @Test
    @DisplayName("Extract email local-part")
    void testExtractLocalPart() {
        assertThat(CryptoUtil.extractLocalPart("user@example.com")).isEqualTo("user");
        assertThat(CryptoUtil.extractLocalPart("noatsign")).isEqualTo("noatsign");
    }
}
