package com.ninemail.service;

import com.ninemail.config.ServerProperties;
import com.ninemail.domain.MailMailbox;
import com.ninemail.mapper.MailMailboxMapper;
import com.ninemail.util.MaildirUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Mailbox management service
 * - Mailbox CRUD
 * - UID management
 * - Auto-create default mailboxes (INBOX, Sent, Drafts, Trash, Junk)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailboxService {

    private final MailMailboxMapper mailboxMapper;
    private final ServerProperties properties;

    private static final List<String> DEFAULT_MAILBOXES = Arrays.asList(
            "INBOX", "Sent", "Drafts", "Trash", "Junk"
    );

    /**
        * Create default mailboxes for the user
     */
    public void createDefaultMailboxes(String email) {
        for (String name : DEFAULT_MAILBOXES) {
            createMailbox(email, name, name);
        }
        log.info("Default mailboxes created for: {}", email);
    }

    /**
     * Create mailbox
     */
    public MailMailbox createMailbox(String email, String name, String path) {
        // Check if it already exists
        MailMailbox existing = mailboxMapper.findByEmailAndPath(email, path);
        if (existing != null) {
            return existing;
        }

        MailMailbox mailbox = MailMailbox.builder()
                .mailboxId(MaildirUtil.generateMailboxId())
                .email(email)
                .mailboxName(name)
                .mailboxPath(path)
                .totalSize(0)
                .mailCount(0)
                .nextUid(1)
                .uidValidity(1)
                .build();

        mailboxMapper.insert(mailbox);

        try {
            MaildirUtil.ensureMaildirStructure(properties.getStorage().getBasePath(), email);
        } catch (IOException e) {
            log.error("Failed to ensure storage directory for {}", email, e);
        }

        log.debug("Mailbox created: {} for {}", path, email);
        return mailbox;
    }

    /**
        * Get mailbox by email and path
     */
    public MailMailbox getMailbox(String email, String path) {
        return mailboxMapper.findByEmailAndPath(email, path);
    }

    /**
        * Get mailbox by mailboxId
     */
    public MailMailbox getMailboxById(String mailboxId) {
        return mailboxMapper.findById(mailboxId);
    }

    /**
        * List all mailboxes for the user
     */
    public List<MailMailbox> listMailboxes(String email) {
        return mailboxMapper.findByEmail(email);
    }

    /**
        * List mailboxes by pattern (for IMAP LIST command)
        * * -> % (SQL LIKE)
     */
    public List<MailMailbox> listMailboxes(String email, String reference, String pattern) {
        String safeReference = reference == null ? "" : reference;
        String safePattern = pattern == null ? "" : pattern;

        // Thunderbird sometimes uses reference "." for the root; our mailbox paths are not prefixed with '.'
        if (".".equals(safeReference)) {
            safeReference = "";
        }

        // RFC: INBOX is case-insensitive special name
        if (safePattern.equalsIgnoreCase("INBOX")) {
            safePattern = "INBOX";
        }

        String fullPattern = safeReference + safePattern;

        // Convert IMAP wildcards to SQL LIKE pattern
        // IMAP '*' and '%' both map well to SQL '%', given our flat mailbox namespace.
        String sqlPattern = fullPattern
                .replace("*", "%")
                .replace("%", "%");

        if (sqlPattern.isBlank()) {
            sqlPattern = "%";
        }

        return mailboxMapper.findByEmailAndPattern(email, sqlPattern);
    }

    /**
        * Rename mailbox
     */
    public boolean renameMailbox(String email, String oldPath, String newPath) {
        MailMailbox mailbox = mailboxMapper.findByEmailAndPath(email, oldPath);
        if (mailbox == null) return false;

        String newName = newPath.contains(".") ?
                newPath.substring(newPath.lastIndexOf('.') + 1) : newPath;
        mailboxMapper.rename(mailbox.getMailboxId(), newName, newPath);
        log.info("Mailbox renamed: {} -> {} for {}", oldPath, newPath, email);
        return true;
    }

    /**
        * Delete mailbox
     */
    public boolean deleteMailbox(String email, String path) {
        if ("INBOX".equalsIgnoreCase(path)) {
            log.warn("Cannot delete INBOX for {}", email);
            return false;
        }
        MailMailbox mailbox = mailboxMapper.findByEmailAndPath(email, path);
        if (mailbox == null) return false;

        mailboxMapper.deleteById(mailbox.getMailboxId());
        log.info("Mailbox deleted: {} for {}", path, email);
        return true;
    }

    /**
        * Get and increment nextUid (atomic)
     */
    public synchronized int getNextUid(String mailboxId) {
        int uid = mailboxMapper.getNextUid(mailboxId);
        mailboxMapper.updateNextUid(mailboxId, uid + 1);
        return uid;
    }

    /**
        * Update size/count on mail delivery
     */
    public void incrementMailCount(String mailboxId, long size) {
        mailboxMapper.incrementMailCount(mailboxId, size);
    }

    /**
        * Decrease size/count on mail deletion
     */
    public void decrementMailCount(String mailboxId, long size) {
        mailboxMapper.decrementMailCount(mailboxId, size);
    }
}
