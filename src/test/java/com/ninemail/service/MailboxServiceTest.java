package com.ninemail.service;

import com.ninemail.config.ServerProperties;
import com.ninemail.domain.MailMailbox;
import com.ninemail.mapper.MailMailboxMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MailboxService unit tests
 */
@ExtendWith(MockitoExtension.class)
class MailboxServiceTest {

    @Mock
    private MailMailboxMapper mailboxMapper;

    @Mock
    private ServerProperties properties;

    @InjectMocks
    private MailboxService mailboxService;

    @Test
    @DisplayName("Create default mailboxes (INBOX, Sent, Drafts, Trash, Junk)")
    void testCreateDefaultMailboxes() {
        ServerProperties.Storage storage = new ServerProperties.Storage();
        when(properties.getStorage()).thenReturn(storage);
        when(mailboxMapper.findByEmailAndPath(anyString(), anyString())).thenReturn(null);

        mailboxService.createDefaultMailboxes("user@localhost");

        verify(mailboxMapper, times(5)).insert(any(MailMailbox.class));
    }

    @Test
    @DisplayName("Create mailbox checks duplicates")
    void testCreateMailbox_Duplicate() {
        MailMailbox existing = MailMailbox.builder()
                .mailboxId("123")
                .mailboxName("INBOX")
                .mailboxPath("INBOX")
                .build();
        when(mailboxMapper.findByEmailAndPath("user@localhost", "INBOX")).thenReturn(existing);

        MailMailbox result = mailboxService.createMailbox("user@localhost", "INBOX", "INBOX");

        assertThat(result).isEqualTo(existing);
        verify(mailboxMapper, never()).insert(any());
    }

    @Test
    @DisplayName("INBOX cannot be deleted")
    void testDeleteInbox_Rejected() {
        boolean result = mailboxService.deleteMailbox("user@localhost", "INBOX");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Get and increment nextUid")
    void testGetNextUid() {
        when(mailboxMapper.getNextUid("box1")).thenReturn(5);

        int uid = mailboxService.getNextUid("box1");

        assertThat(uid).isEqualTo(5);
        verify(mailboxMapper).updateNextUid("box1", 6);
    }
}
