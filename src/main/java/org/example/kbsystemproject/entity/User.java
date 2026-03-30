package org.example.kbsystemproject.entity;


import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false)
@Table("users")
public class User implements Persistable<String> {
    @Id
    private String uuid;
    private String username;
    @Column(value = "password_hash")
    private String passwordHashHex;
    private String email;
    private String avatarUrl;
    private LocalDateTime createdTime;
    private LocalDateTime lastLoginTime;
    private Boolean isActive;

    @Transient
    private Boolean newUser = Boolean.TRUE;

    @Override
    public @Nullable String getId() {
        return uuid;
    }

    @Transient
    @Override
    public boolean isNew() {
        return newUser || uuid == null;
    }
}
