package com.ninemail.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mailbox entity per account
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailMailbox {

    private String mailboxId;       // Random unique value (less than 10 digits)
    private String email;
    private String mailboxName;
    private String mailboxPath;     // Hierarchy joined by '.'
    private String regDt;
    private long totalSize;
    private int mailCount;
    private int nextUid;
    private int uidValidity;
}
