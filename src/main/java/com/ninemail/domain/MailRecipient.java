package com.ninemail.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Recipient entity per message
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailRecipient {

    private Long id;
    private String messageId;
    private String email;
}
