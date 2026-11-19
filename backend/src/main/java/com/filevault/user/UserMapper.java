package com.filevault.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    record Account(long id, String username, String passwordHash) { }

    @Select("SELECT id, username, password_hash AS passwordHash FROM users WHERE username = #{username}")
    Account find(String username);

    @Insert("INSERT INTO users(username, password_hash) VALUES (#{username}, #{hash})")
    void insert(String username, String hash);

    @Select("SELECT id FROM users WHERE id = #{id} FOR UPDATE")
    Long lock(long id);
}
