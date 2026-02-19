package com.ninemail.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Received mail entry entity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailMail {

    private Long id;
    private String messageId;
    private String mailboxId;
    private int uid;
    private String receiveDt;
    private String receiveTime;
    private int isRead;
    private int isFlagged;
    private int isAnswered;
    private int isDeleted;
    private int isDraft;
    private long size;
}
