package com.ninemail.mapper;

import com.ninemail.domain.MailMailbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MailMailboxMapper {

    void insert(MailMailbox mailbox);

    MailMailbox findById(@Param("mailboxId") String mailboxId);

    MailMailbox findByEmailAndPath(@Param("email") String email, @Param("mailboxPath") String mailboxPath);

    List<MailMailbox> findByEmail(@Param("email") String email);

    List<MailMailbox> findByEmailAndPattern(@Param("email") String email, @Param("pattern") String pattern);

    void updateNextUid(@Param("mailboxId") String mailboxId, @Param("nextUid") int nextUid);

    void updateMailCount(@Param("mailboxId") String mailboxId, @Param("mailCount") int mailCount);

    void updateTotalSize(@Param("mailboxId") String mailboxId, @Param("totalSize") long totalSize);

    void incrementMailCount(@Param("mailboxId") String mailboxId, @Param("sizeIncrement") long sizeIncrement);

    void decrementMailCount(@Param("mailboxId") String mailboxId, @Param("sizeDelta") long sizeDelta);

    void rename(@Param("mailboxId") String mailboxId, @Param("newName") String newName, @Param("newPath") String newPath);

    void deleteById(@Param("mailboxId") String mailboxId);

    int getNextUid(@Param("mailboxId") String mailboxId);
}
