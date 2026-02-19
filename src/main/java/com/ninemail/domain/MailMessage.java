package com.ninemail.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message primary entity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailMessage {

    private String messageId;
    private String subject;
    private String sender;
    private String sendDt;
    private String recipient;   // First recipient
    private String filename;    // EML file relative path
}
