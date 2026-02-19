package com.ninemail.smtp;

/**
 * Custom user event to signal TLS negotiation outcome on submission/implicit
 * SSL ports.
 * Fired by SmtpServerInitializer after OptionalSslHandler resolves TLS or
 * detects plain text.
 */
public enum SmtpTlsEvent {
    /** TLS handshake completed successfully (client sent TLS ClientHello) */
    TLS_ESTABLISHED,
    /** Client connected in plain text (STARTTLS available) */
    PLAINTEXT_DETECTED
}
