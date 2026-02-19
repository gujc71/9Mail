package com.ninemail.smtp;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SMTP session context
 * State for a single SMTP client connection
 */
@Data
public class SmtpSession {

    private SmtpState state = SmtpState.CONNECTED;
    private String clientHostname;
    private String remoteIp;

    // Authentication
    private boolean authenticated = false;
    private String authenticatedUser;
    private int authFailureCount = 0;

    // AUTH LOGIN progress
    private String authLoginUsername;

    // Mail transaction
    private String mailFrom;
    private List<String> recipients = new ArrayList<>();
    private StringBuilder dataBuffer = new StringBuilder();

    // TLS
    private boolean tlsActive = false;

    /**
     * Reset mail transaction (RSET)
     */
    public void resetTransaction() {
        this.mailFrom = null;
        this.recipients.clear();
        this.dataBuffer = new StringBuilder();
        if (state != SmtpState.CONNECTED) {
            state = SmtpState.GREETED;
        }
    }

    /**
     * Reset session state after STARTTLS
     */
    public void resetAfterTls() {
        this.state = SmtpState.CONNECTED;
        this.authenticated = false;
        this.authenticatedUser = null;
        this.clientHostname = null;
        this.tlsActive = true;
        resetTransaction();
    }

    /**
     * Add recipient
     */
    public void addRecipient(String recipient) {
        this.recipients.add(recipient);
    }

    /**
     * Append a line to the DATA buffer
     */
    public void appendData(String line) {
        dataBuffer.append(line).append("\r\n");
    }

    /**
     * Return raw DATA bytes
     */
    public byte[] getDataBytes() {
        return dataBuffer.toString().getBytes();
    }
}
