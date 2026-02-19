package com.ninemail.smtp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SMTP session unit tests
 */
class SmtpSessionTest {

    private SmtpSession session;

    @BeforeEach
    void setUp() {
        session = new SmtpSession();
    }

    @Test
    @DisplayName("Initial state: CONNECTED")
    void testInitialState() {
        assertThat(session.getState()).isEqualTo(SmtpState.CONNECTED);
        assertThat(session.isAuthenticated()).isFalse();
        assertThat(session.getRecipients()).isEmpty();
    }

    @Test
    @DisplayName("RSET: reset transaction")
    void testResetTransaction() {
        session.setState(SmtpState.RCPT_TO);
        session.setMailFrom("sender@test.com");
        session.addRecipient("rcpt@test.com");
        session.appendData("test data");

        session.resetTransaction();

        assertThat(session.getMailFrom()).isNull();
        assertThat(session.getRecipients()).isEmpty();
        assertThat(session.getState()).isEqualTo(SmtpState.GREETED);
    }

    @Test
    @DisplayName("Reset session after STARTTLS")
    void testResetAfterTls() {
        session.setState(SmtpState.GREETED);
        session.setAuthenticated(true);
        session.setAuthenticatedUser("user@test.com");

        session.resetAfterTls();

        assertThat(session.getState()).isEqualTo(SmtpState.CONNECTED);
        assertThat(session.isAuthenticated()).isFalse();
        assertThat(session.isTlsActive()).isTrue();
    }

    @Test
    @DisplayName("Add recipients")
    void testAddRecipient() {
        session.addRecipient("user1@test.com");
        session.addRecipient("user2@test.com");

        assertThat(session.getRecipients()).hasSize(2);
        assertThat(session.getRecipients()).contains("user1@test.com", "user2@test.com");
    }

    @Test
    @DisplayName("DATA buffer handling")
    void testDataBuffer() {
        session.appendData("Subject: Test");
        session.appendData("");
        session.appendData("Hello World");

        byte[] data = session.getDataBytes();
        String content = new String(data);

        assertThat(content).contains("Subject: Test");
        assertThat(content).contains("Hello World");
    }
}
