package org.example.kbsystemproject.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserToken {
    private String refreshToken;
    private String accessToken;
}
