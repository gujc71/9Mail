package com.ninemail.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User account entity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailUser {

    private String username;
    private String email;
    private String password;  // SHA-256 hash
    private String createdDt;
    private int active;
}
