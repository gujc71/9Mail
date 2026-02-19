package com.ninemail.service;

import com.ninemail.config.ServerProperties;
import com.ninemail.domain.*;
import com.ninemail.mapper.*;
import com.ninemail.util.EmlParser;
import com.ninemail.util.MaildirUtil;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Message processing service
 * - Receive and persist mail
 * - Parse EML and store metadata to DB
 * - Deliver to each recipient's mailbox
 * - Store only one copy of the raw message (Maildir style)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    public record AppendResult(String messageId, int uidValidity, int uid) {
    }

    private final MailMessageMapper messageMapper;
    private final MailRecipientMapper recipientMapper;
    private final MailMailMapper mailMapper;
    private final MailUserMapper userMapper;
    private final MailboxService mailboxService;
    private final ServerProperties properties;

    /**
     * Process an incoming mail (after SMTP DATA)
     * - Store only one raw EML file
     * - Store message metadata to DB
     * - Deliver to each internal recipient's INBOX
     *
     * @param emlData    raw EML bytes
     * @param sender     sender email
     * @param recipients recipient email list
     * @return processed message id
     */
    public String processIncomingMail(byte[] emlData, String sender, List<String> recipients) {
        try {
            // 1. Parse EML
            MimeMessage mimeMessage = EmlParser.parse(emlData);
            String messageId = EmlParser.extractMessageId(mimeMessage);
            String subject = EmlParser.extractSubject(mimeMessage);
            String senderAddr = sender;
            long size = emlData.length;

            // 2. Store a single EML file
            String emlRelativePath = MaildirUtil.saveEml(
                    properties.getStorage().getEmlPath(), emlData);

            // 3. Store MAIL_MESSAGE
            if (messageMapper.countById(messageId) == 0) {
                MailMessage message = MailMessage.builder()
                        .messageId(messageId)
                        .subject(subject)
                        .sender(senderAddr)
                        .sendDt(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                        .recipient(recipients.isEmpty() ? "" : recipients.get(0))
                        .filename(emlRelativePath)
                        .build();
                messageMapper.insert(message);
            } else {
                // Best-effort: ensure filename is recorded for existing message_id
                MailMessage existing = messageMapper.findById(messageId);
                if (existing == null || existing.getFilename() == null || existing.getFilename().isBlank()) {
                    messageMapper.updateFilename(messageId, emlRelativePath);
                }
            }

            // 4. Store MAIL_RECIPIENT (deduplicated)
            Set<String> uniqueRecipients = new HashSet<>(recipients);
            for (String rcpt : uniqueRecipients) {
                if (recipientMapper.countByMessageIdAndEmail(messageId, rcpt) == 0) {
                    recipientMapper.insert(MailRecipient.builder()
                            .messageId(messageId)
                            .email(rcpt)
                            .build());
                }
            }

            // 5. Deliver to internal recipients' INBOX
            for (String rcpt : uniqueRecipients) {
                if (userMapper.countByEmail(rcpt) > 0) {
                    deliverToInbox(rcpt, messageId, size);
                }
            }

            log.info("Mail processed: messageId={}, from={}, to={}", messageId, senderAddr, recipients);
            return messageId;

        } catch (Exception e) {
            log.error("Failed to process incoming mail", e);
            throw new RuntimeException("Mail processing failed", e);
        }
    }

    /**
     * IMAP APPEND: store message into a specific mailbox for the owner.
     * Unlike inbound delivery, this must not re-deliver to INBOX.
     *
     * @param ownerEmail  mailbox owner
     * @param mailboxPath mailbox path (e.g., INBOX, Sent)
     * @param emlData     raw EML bytes
     * @param imapFlags   optional IMAP flags string (e.g., "\\Seen \\Flagged")
     * @return messageId
     */
    public AppendResult appendToMailbox(String ownerEmail, String mailboxPath, byte[] emlData, String imapFlags) {
        try {
            MimeMessage mimeMessage = EmlParser.parse(emlData);
            String messageId = EmlParser.extractMessageId(mimeMessage);
            String subject = EmlParser.extractSubject(mimeMessage);
            String senderAddr = mimeMessage.getFrom() != null && mimeMessage.getFrom().length > 0
                    ? mimeMessage.getFrom()[0].toString()
                    : ownerEmail;
            long size = emlData.length;

            // Store a single EML file
            String emlRelativePath = MaildirUtil.saveEml(properties.getStorage().getEmlPath(), emlData);

            // Store MAIL_MESSAGE (idempotent)
            if (messageMapper.countById(messageId) == 0) {
                MailMessage message = MailMessage.builder()
                        .messageId(messageId)
                        .subject(subject)
                        .sender(senderAddr)
                        .sendDt(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                        .recipient(ownerEmail)
                        .filename(emlRelativePath)
                        .build();
                messageMapper.insert(message);
            } else {
                MailMessage existing = messageMapper.findById(messageId);
                if (existing == null || existing.getFilename() == null || existing.getFilename().isBlank()) {
                    messageMapper.updateFilename(messageId, emlRelativePath);
                }
            }

            String normalizedPath = mailboxPath != null && mailboxPath.equalsIgnoreCase("INBOX") ? "INBOX"
                    : mailboxPath;
            MailMailbox mailbox = mailboxService.getMailbox(ownerEmail, normalizedPath);
            if (mailbox == null) {
                throw new IllegalArgumentException("[TRYCREATE] Mailbox does not exist");
            }

            int uid = mailboxService.getNextUid(mailbox.getMailboxId());

            int isRead = 1; // APPEND-added messages should be marked as read
            int isFlagged = 0;
            int isAnswered = 0;
            int isDeleted = 0;
            int isDraft = 0;
            if (imapFlags != null && !imapFlags.isBlank()) {
                String upper = imapFlags.toUpperCase();
                if (upper.contains("\\\\FLAGGED"))
                    isFlagged = 1;
                if (upper.contains("\\\\ANSWERED"))
                    isAnswered = 1;
                if (upper.contains("\\\\DELETED"))
                    isDeleted = 1;
                if (upper.contains("\\\\DRAFT"))
                    isDraft = 1;
            }

            MailMail mail = MailMail.builder()
                    .messageId(messageId)
                    .mailboxId(mailbox.getMailboxId())
                    .uid(uid)
                    .receiveDt(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .receiveTime(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                    .isRead(isRead)
                    .isFlagged(isFlagged)
                    .isAnswered(isAnswered)
                    .isDeleted(isDeleted)
                    .isDraft(isDraft)
                    .size(size)
                    .build();

            mailMapper.insert(mail);
            mailboxService.incrementMailCount(mailbox.getMailboxId(), size);

            log.info("Mail appended to {} of {}: uid={}, messageId={}", normalizedPath, ownerEmail, uid, messageId);
            return new AppendResult(messageId, mailbox.getUidValidity(), uid);
        } catch (Exception e) {
            log.error("Failed to append mail to mailbox {} for {}", mailboxPath, ownerEmail, e);
            throw new RuntimeException("APPEND failed", e);
        }
    }

    /**
     * Deliver mail to a user's INBOX
     */
    private void deliverToInbox(String email, String messageId, long size) {
        MailMailbox inbox = mailboxService.getMailbox(email, "INBOX");
        if (inbox == null) {
            // If INBOX is missing, create default mailboxes
            mailboxService.createDefaultMailboxes(email);
            inbox = mailboxService.getMailbox(email, "INBOX");
        }

        int uid = mailboxService.getNextUid(inbox.getMailboxId());

        MailMail mail = MailMail.builder()
                .messageId(messageId)
                .mailboxId(inbox.getMailboxId())
                .uid(uid)
                .receiveDt(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .receiveTime(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .isRead(0)
                .isFlagged(0)
                .isAnswered(0)
                .isDeleted(0)
                .isDraft(0)
                .size(size)
                .build();

        mailMapper.insert(mail);
        mailboxService.incrementMailCount(inbox.getMailboxId(), size);

        log.debug("Mail delivered to INBOX of {}: uid={}", email, uid);
    }

    /**
     * List mail entries for a mailbox
     */
    public List<MailMail> getMailsByMailbox(String mailboxId) {
        return mailMapper.findByMailboxId(mailboxId);
    }

    /**
     * Get mail entries by UID range
     */
    public List<MailMail> getMailsByUidRange(String mailboxId, int startUid, int endUid) {
        return mailMapper.findByMailboxIdWithRange(mailboxId, startUid, endUid);
    }

    /**
     * Get a mail entry by UID
     */
    public MailMail getMailByUid(String mailboxId, int uid) {
        return mailMapper.findByMailboxIdAndUid(mailboxId, uid);
    }

    /**
     * Get raw EML content
     */
    public byte[] getEmlData(String filename) {
        try {
            return MaildirUtil.readEml(properties.getStorage().getEmlPath(), filename);
        } catch (Exception e) {
            log.error("Failed to read EML: {}", filename, e);
            return null;
        }
    }

    /**
     * Get raw EML content by message id (filename is stored in MAIL_MESSAGE)
     */
    public byte[] getEmlDataByMessageId(String messageId) {
        MailMessage message = messageMapper.findById(messageId);
        if (message == null || message.getFilename() == null || message.getFilename().isBlank()) {
            return null;
        }
        return getEmlData(message.getFilename());
    }

    /**
     * Get mail count
     */
    public int getMailCount(String mailboxId) {
        return mailMapper.countByMailboxId(mailboxId);
    }

    /**
     * Get unread mail count
     */
    public int getUnreadCount(String mailboxId) {
        return mailMapper.countUnreadByMailboxId(mailboxId);
    }

    /**
     * Update flags
     */
    public void updateFlags(long mailId, int isRead, int isFlagged, int isAnswered, int isDeleted, int isDraft) {
        mailMapper.updateFlags(mailId, isRead, isFlagged, isAnswered, isDeleted, isDraft);
    }

    /**
     * Mark as read
     */
    public void markRead(long mailId, boolean read) {
        mailMapper.markRead(mailId, read ? 1 : 0);
    }

    /**
     * Mark as deleted
     */
    public void markDeleted(long mailId, boolean deleted) {
        mailMapper.markDeleted(mailId, deleted ? 1 : 0);
    }

    /**
     * EXPUNGE: permanently delete mails marked as deleted
     */
    public List<Integer> expunge(String mailboxId) {
        List<MailMail> deleted = mailMapper.findDeletedByMailboxId(mailboxId);
        List<Integer> expungedUids = new ArrayList<>();

        for (MailMail mail : deleted) {
            expungedUids.add(mail.getUid());
            mailboxService.decrementMailCount(mailboxId, mail.getSize());
            mailMapper.deleteById(mail.getId());
        }

        log.info("Expunged {} messages from mailbox {}", expungedUids.size(), mailboxId);
        return expungedUids;
    }

    /**
     * EXPUNGE selected UIDs only (UIDPLUS: UID EXPUNGE).
     * Only messages already marked with \Deleted are removed.
     */
    public List<Integer> expungeByUids(String mailboxId, Collection<Integer> uids) {
        List<Integer> expungedUids = new ArrayList<>();
        if (uids == null || uids.isEmpty()) {
            return expungedUids;
        }

        for (Integer uid : uids) {
            if (uid == null)
                continue;

            MailMail mail = mailMapper.findByMailboxIdAndUid(mailboxId, uid);
            if (mail == null)
                continue;
            if (mail.getIsDeleted() != 1)
                continue;

            mailboxService.decrementMailCount(mailboxId, mail.getSize());
            mailMapper.deleteById(mail.getId());
            expungedUids.add(mail.getUid());
        }

        log.info("Expunged {} selected messages from mailbox {}", expungedUids.size(), mailboxId);
        return expungedUids;
    }

    /**
     * Copy mail (IMAP COPY)
     */
    public void copyMail(String mailboxId, int uid, String targetMailboxId) {
        MailMail source = mailMapper.findByMailboxIdAndUid(mailboxId, uid);
        if (source == null)
            return;

        int newUid = mailboxService.getNextUid(targetMailboxId);
        MailMail copy = MailMail.builder()
                .messageId(source.getMessageId())
                .mailboxId(targetMailboxId)
                .uid(newUid)
                .receiveDt(source.getReceiveDt())
                .receiveTime(source.getReceiveTime())
                .isRead(source.getIsRead())
                .isFlagged(source.getIsFlagged())
                .isAnswered(source.getIsAnswered())
                .isDeleted(0)
                .isDraft(source.getIsDraft())
                .size(source.getSize())
                .build();
        mailMapper.insert(copy);
        mailboxService.incrementMailCount(targetMailboxId, source.getSize());
    }

    /**
     * Move mail (IMAP MOVE: COPY + mark original as deleted)
     */
    public void moveMail(String mailboxId, int uid, String targetMailboxId) {
        copyMail(mailboxId, uid, targetMailboxId);
        MailMail source = mailMapper.findByMailboxIdAndUid(mailboxId, uid);
        if (source != null) {
            mailMapper.markDeleted(source.getId(), 1);
        }
    }

    /**
     * Search by subject (IMAP SEARCH)
     */
    public List<MailMail> searchBySubject(String mailboxId, String keyword) {
        return mailMapper.searchBySubject(mailboxId, keyword);
    }

    /**
     * Search by sender (FROM)
     */
    public List<MailMail> searchByFrom(String mailboxId, String keyword) {
        return mailMapper.searchByFrom(mailboxId, keyword);
    }
}
