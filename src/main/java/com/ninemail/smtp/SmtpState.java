package com.ninemail.smtp;

/**
 * SMTP session state machine
 */
public enum SmtpState {
    /** Immediately after connect - waiting for EHLO/HELO */
    CONNECTED,
    /** EHLO/HELO completed - MAIL FROM allowed */
    GREETED,
    /** MAIL FROM completed - RCPT TO allowed */
    MAIL_FROM,
    /** RCPT TO completed - DATA allowed */
    RCPT_TO,
    /** Receiving DATA */
    DATA,
    /** AUTH in progress - LOGIN username */
    AUTH_LOGIN_USERNAME,
    /** AUTH in progress - LOGIN password */
    AUTH_LOGIN_PASSWORD,
    /** AUTH in progress - PLAIN input (continuation) */
    AUTH_PLAIN_INPUT
}
