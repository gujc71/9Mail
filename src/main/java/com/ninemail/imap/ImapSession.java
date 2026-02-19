package com.ninemail.imap;

import com.ninemail.domain.MailMailbox;
import com.ninemail.domain.MailMail;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * IMAP session context
 * State for a single IMAP client connection
 */
@Data
public class ImapSession {

    private ImapState state = ImapState.NOT_AUTHENTICATED;
    private String remoteIp;

    // Authentication
    private String authenticatedUser; // Email
    private boolean authenticated = false;

    // AUTHENTICATE in progress (SASL)
    private boolean authMode = false;
    private String authTag;
    private String authMechanism;
    private String authLoginUsername;
    private int authLoginStep = 0; // 0=none, 1=awaiting username, 2=awaiting password

    // Selected mailbox
    private MailMailbox selectedMailbox;
    private boolean readOnly = false; // true when opened with EXAMINE

    // Mail list cache for the selected mailbox
    private List<MailMail> mailCache = new ArrayList<>();

    // TLS
    private boolean tlsActive = false;

    // IDLE mode
    private boolean idleMode = false;
    private String idleTag; // tag for IDLE command (separate from appendTag)

    // APPEND in progress
    private boolean appendMode = false;
    private String appendMailbox;
    private String appendFlags;
    private String appendDate;
    private int appendSize;
    private int appendBytesReceived = 0; // actual byte count of received data
    private StringBuilder appendBuffer = new StringBuilder();
    private String appendTag;

    /**
     * Select mailbox (SELECT/EXAMINE)
     */
    public void selectMailbox(MailMailbox mailbox, boolean readOnly) {
        this.selectedMailbox = mailbox;
        this.readOnly = readOnly;
        this.mailCache.clear(); // 이전 mailbox 캐시 초기화
        this.state = ImapState.SELECTED;
    }

    /**
     * Close mailbox
     */
    public void closeMailbox() {
        this.selectedMailbox = null;
        this.readOnly = false;
        this.mailCache.clear();
        this.state = ImapState.AUTHENTICATED;
    }

    /**
     * Get MailMail by sequence number (1-based)
     */
    public MailMail getMailBySequence(int sequence) {
        if (sequence < 1 || sequence > mailCache.size())
            return null;
        return mailCache.get(sequence - 1);
    }

    /**
     * Get MailMail by UID
     */
    public MailMail getMailByUid(int uid) {
        return mailCache.stream()
                .filter(m -> m.getUid() == uid)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get sequence number (1-based) for a UID
     */
    public int getSequenceByUid(int uid) {
        for (int i = 0; i < mailCache.size(); i++) {
            if (mailCache.get(i).getUid() == uid) {
                return i + 1;
            }
        }
        return -1;
    }
}
