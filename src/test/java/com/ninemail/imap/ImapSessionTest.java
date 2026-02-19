package com.ninemail.imap;

import com.ninemail.domain.MailMail;
import com.ninemail.domain.MailMailbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMAP session unit tests
 */
class ImapSessionTest {

    private ImapSession session;

    @BeforeEach
    void setUp() {
        session = new ImapSession();
    }

    @Test
    @DisplayName("Initial state: NOT_AUTHENTICATED")
    void testInitialState() {
        assertThat(session.getState()).isEqualTo(ImapState.NOT_AUTHENTICATED);
        assertThat(session.isAuthenticated()).isFalse();
        assertThat(session.getSelectedMailbox()).isNull();
    }

    @Test
    @DisplayName("State transition after SELECT")
    void testSelectMailbox() {
        MailMailbox mailbox = MailMailbox.builder()
                .mailboxId("123456789")
                .email("user@localhost")
                .mailboxName("INBOX")
                .mailboxPath("INBOX")
                .nextUid(1)
                .uidValidity(1)
                .build();

        session.selectMailbox(mailbox, false);

        assertThat(session.getState()).isEqualTo(ImapState.SELECTED);
        assertThat(session.getSelectedMailbox()).isNotNull();
        assertThat(session.isReadOnly()).isFalse();
    }

    @Test
    @DisplayName("Read-only after EXAMINE")
    void testExamineMailbox() {
        MailMailbox mailbox = MailMailbox.builder()
                .mailboxId("123456789")
                .mailboxName("INBOX")
                .build();

        session.selectMailbox(mailbox, true);

        assertThat(session.getState()).isEqualTo(ImapState.SELECTED);
        assertThat(session.isReadOnly()).isTrue();
    }

    @Test
    @DisplayName("Back to AUTHENTICATED after CLOSE")
    void testCloseMailbox() {
        MailMailbox mailbox = MailMailbox.builder()
                .mailboxId("123456789")
                .mailboxName("INBOX")
                .build();
        session.selectMailbox(mailbox, false);

        session.closeMailbox();

        assertThat(session.getState()).isEqualTo(ImapState.AUTHENTICATED);
        assertThat(session.getSelectedMailbox()).isNull();
    }

    @Test
    @DisplayName("Get mail by sequence number (1-based)")
    void testGetMailBySequence() {
        MailMail mail1 = MailMail.builder().uid(1).messageId("msg1").build();
        MailMail mail2 = MailMail.builder().uid(2).messageId("msg2").build();
        MailMail mail3 = MailMail.builder().uid(5).messageId("msg3").build();

        session.setMailCache(List.of(mail1, mail2, mail3));

        assertThat(session.getMailBySequence(1)).isEqualTo(mail1);
        assertThat(session.getMailBySequence(2)).isEqualTo(mail2);
        assertThat(session.getMailBySequence(3)).isEqualTo(mail3);
        assertThat(session.getMailBySequence(0)).isNull();
        assertThat(session.getMailBySequence(4)).isNull();
    }

    @Test
    @DisplayName("Get mail by UID")
    void testGetMailByUid() {
        MailMail mail1 = MailMail.builder().uid(1).messageId("msg1").build();
        MailMail mail2 = MailMail.builder().uid(5).messageId("msg2").build();

        session.setMailCache(List.of(mail1, mail2));

        assertThat(session.getMailByUid(1)).isEqualTo(mail1);
        assertThat(session.getMailByUid(5)).isEqualTo(mail2);
        assertThat(session.getMailByUid(3)).isNull();
    }

    @Test
    @DisplayName("UID -> sequence number")
    void testGetSequenceByUid() {
        MailMail mail1 = MailMail.builder().uid(10).messageId("msg1").build();
        MailMail mail2 = MailMail.builder().uid(20).messageId("msg2").build();
        MailMail mail3 = MailMail.builder().uid(30).messageId("msg3").build();

        session.setMailCache(List.of(mail1, mail2, mail3));

        assertThat(session.getSequenceByUid(10)).isEqualTo(1);
        assertThat(session.getSequenceByUid(20)).isEqualTo(2);
        assertThat(session.getSequenceByUid(30)).isEqualTo(3);
        assertThat(session.getSequenceByUid(15)).isEqualTo(-1);
    }
}
