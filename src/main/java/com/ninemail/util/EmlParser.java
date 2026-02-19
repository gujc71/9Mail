package com.ninemail.util;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * EML parsing utilities based on Jakarta Mail
 */
@Slf4j
public final class EmlParser {

    private static final Session SESSION;

    static {
        Properties props = new Properties();
        props.setProperty("mail.mime.charset", "UTF-8");
        props.setProperty("mail.mime.decodetext.strict", "false");
        SESSION = Session.getDefaultInstance(props);
    }

    private EmlParser() {}

    /**
     * Parse a MimeMessage from bytes
     */
    public static MimeMessage parse(byte[] emlData) throws Exception {
        try (InputStream is = new ByteArrayInputStream(emlData)) {
            return new MimeMessage(SESSION, is);
        }
    }

    /**
     * Serialize a MimeMessage to bytes
     */
    public static byte[] toBytes(MimeMessage message) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        message.writeTo(outputStream);
        return outputStream.toByteArray();
    }

    /**
     * Extract Message-ID from a MimeMessage
     */
    public static String extractMessageId(MimeMessage message) throws Exception {
        String messageId = message.getMessageID();
        if (messageId == null) {
            messageId = "<" + System.currentTimeMillis() + "@" + "ninemail" + ">";
        }
        return messageId;
    }

    /**
     * Extract Subject from a MimeMessage
     */
    public static String extractSubject(MimeMessage message) throws Exception {
        String subject = message.getSubject();
        return subject != null ? subject : "(No Subject)";
    }

    /**
     * Extract sender from a MimeMessage
     */
    public static String extractSender(MimeMessage message) throws Exception {
        if (message.getFrom() != null && message.getFrom().length > 0) {
            return message.getFrom()[0].toString();
        }
        return "unknown@unknown";
    }

    /**
     * Return the mail Session
     */
    public static Session getSession() {
        return SESSION;
    }
}
