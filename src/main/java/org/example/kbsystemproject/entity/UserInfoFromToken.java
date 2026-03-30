package org.example.kbsystemproject.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class UserInfoFromToken {
    private String userId;
    private String authority;
}
