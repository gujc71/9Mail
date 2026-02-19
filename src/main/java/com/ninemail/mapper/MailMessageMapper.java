package com.ninemail.mapper;

import com.ninemail.domain.MailMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MailMessageMapper {

    void insert(MailMessage message);

    void updateFilename(@Param("messageId") String messageId, @Param("filename") String filename);

    MailMessage findById(@Param("messageId") String messageId);

    List<MailMessage> findBySender(@Param("sender") String sender);

    void deleteById(@Param("messageId") String messageId);

    int countById(@Param("messageId") String messageId);
}
