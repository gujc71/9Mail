package com.ninemail.mapper;

import com.ninemail.domain.MailMail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MailMailMapper {

    void insert(MailMail mail);

    MailMail findById(@Param("id") long id);

    MailMail findByMailboxIdAndUid(@Param("mailboxId") String mailboxId, @Param("uid") int uid);

    List<MailMail> findByMailboxId(@Param("mailboxId") String mailboxId);

    List<MailMail> findByMailboxIdWithRange(@Param("mailboxId") String mailboxId,
                                            @Param("startUid") int startUid,
                                            @Param("endUid") int endUid);

    List<MailMail> findByMailboxIdUnread(@Param("mailboxId") String mailboxId);

    List<MailMail> findByMessageId(@Param("messageId") String messageId);

    int countByMailboxId(@Param("mailboxId") String mailboxId);

    int countUnreadByMailboxId(@Param("mailboxId") String mailboxId);

    int countDeletedByMailboxId(@Param("mailboxId") String mailboxId);

    int getMaxUidByMailboxId(@Param("mailboxId") String mailboxId);

    void updateFlags(@Param("id") long id,
                     @Param("isRead") int isRead,
                     @Param("isFlagged") int isFlagged,
                     @Param("isAnswered") int isAnswered,
                     @Param("isDeleted") int isDeleted,
                     @Param("isDraft") int isDraft);

    void markRead(@Param("id") long id, @Param("isRead") int isRead);

    void markDeleted(@Param("id") long id, @Param("isDeleted") int isDeleted);

    void markFlagged(@Param("id") long id, @Param("isFlagged") int isFlagged);

    void deleteById(@Param("id") long id);

    void deleteByMailboxIdAndDeleted(@Param("mailboxId") String mailboxId);

    List<MailMail> findDeletedByMailboxId(@Param("mailboxId") String mailboxId);

    List<MailMail> searchBySubject(@Param("mailboxId") String mailboxId, @Param("keyword") String keyword);

    List<MailMail> searchByFrom(@Param("mailboxId") String mailboxId, @Param("keyword") String keyword);
}
