package com.ninemail.mapper;

import com.ninemail.domain.MailUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MailUserMapper {

    void insert(MailUser user);

    MailUser findByEmail(@Param("email") String email);

    MailUser findByUsername(@Param("username") String username);

    List<MailUser> findAll();

    void updatePassword(@Param("email") String email, @Param("password") String password);

    void deleteByEmail(@Param("email") String email);

    int countByEmail(@Param("email") String email);
}
