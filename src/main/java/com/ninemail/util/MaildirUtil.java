package com.ninemail.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Maildir-style file storage utilities
 * Path structure: basePath/YYYY/MM/DD/unixTime_random.eml
 */
@Slf4j
public final class MaildirUtil {

    private static final DateTimeFormatter YEAR_FMT = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd");

    private MaildirUtil() {}

    /**
     * Generate an EML storage directory (YYYY/MM/DD)
     */
    public static Path generateEmlPath(String basePath) {
        LocalDate now = LocalDate.now();
        return Paths.get(basePath,
                now.format(YEAR_FMT),
                now.format(MONTH_FMT),
                now.format(DAY_FMT));
    }

    /**
     * Generate a unique EML filename: unixTime_random.eml
     */
    public static String generateEmlFilename() {
        long unixTime = Instant.now().getEpochSecond();
        int random = ThreadLocalRandom.current().nextInt(100000, 999999);
        return unixTime + "_" + random + ".eml";
    }

    /**
     * Save EML file
     * @return relative path of the saved file
     */
    public static String saveEml(String basePath, byte[] emlData) throws IOException {
        Path dir = generateEmlPath(basePath);
        Files.createDirectories(dir);

        String filename = generateEmlFilename();
        Path filePath = dir.resolve(filename);

        Files.write(filePath, emlData);
        log.debug("EML saved: {}", filePath);

        // Return path relative to basePath
        return Paths.get(basePath).relativize(filePath).toString().replace('\\', '/');
    }

    /**
     * Read EML file
     */
    public static byte[] readEml(String basePath, String relativePath) throws IOException {
        Path filePath = Paths.get(basePath, relativePath);
        return Files.readAllBytes(filePath);
    }

    /**
     * Delete EML file
     */
    public static boolean deleteEml(String basePath, String relativePath) {
        try {
            Path filePath = Paths.get(basePath, relativePath);
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("Failed to delete EML: {}", relativePath, e);
            return false;
        }
    }

    /**
     * Generate a random unique mailbox id (< 10 digits)
     */
    public static String generateMailboxId() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000000, 999999999));
    }

    /**
     * Ensure Maildir directory structure exists for the mailbox
     */
    public static void ensureMaildirStructure(String basePath, String email) throws IOException {
        Files.createDirectories(Paths.get(basePath));
    }
}
