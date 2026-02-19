package com.ninemail.mapper;

import com.ninemail.domain.MailRecipient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MailRecipientMapper {

    void insert(MailRecipient recipient);

    List<MailRecipient> findByMessageId(@Param("messageId") String messageId);

    List<MailRecipient> findByEmail(@Param("email") String email);

    void deleteByMessageId(@Param("messageId") String messageId);

    int countByMessageIdAndEmail(@Param("messageId") String messageId, @Param("email") String email);
}
