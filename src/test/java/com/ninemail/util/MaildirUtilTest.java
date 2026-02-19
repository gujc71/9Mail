package com.ninemail.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaildirUtil unit tests
 */
class MaildirUtilTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Save and read EML file")
    void testSaveAndReadEml() throws IOException {
        String basePath = tempDir.toString();
        byte[] emlData = "Subject: Test\r\n\r\nHello World".getBytes();

        String relativePath = MaildirUtil.saveEml(basePath, emlData);

        assertThat(relativePath).isNotNull();
        assertThat(relativePath).endsWith(".eml");

        byte[] readData = MaildirUtil.readEml(basePath, relativePath);
        assertThat(readData).isEqualTo(emlData);
    }

    @Test
    @DisplayName("EML filename format: unixTime_random.eml")
    void testGenerateEmlFilename() {
        String filename = MaildirUtil.generateEmlFilename();

        assertThat(filename).matches("\\d+_\\d+\\.eml");
    }

    @Test
    @DisplayName("Generate mailbox id: random unique value (< 10 digits)")
    void testGenerateMailboxId() {
        String id = MaildirUtil.generateMailboxId();

        assertThat(id).hasSize(9);
        assertThat(id).matches("\\d{9}");
    }

    @Test
    @DisplayName("Delete EML")
    void testDeleteEml() throws IOException {
        String basePath = tempDir.toString();
        byte[] emlData = "Subject: Delete Test\r\n\r\n".getBytes();
        String relativePath = MaildirUtil.saveEml(basePath, emlData);

        boolean deleted = MaildirUtil.deleteEml(basePath, relativePath);

        assertThat(deleted).isTrue();
        assertThat(Files.exists(Path.of(basePath, relativePath))).isFalse();
    }
}
