package org.example.kbsystemproject.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@NoArgsConstructor
public class UserAuthentication implements UserDetails {

    @Getter
    private String userId;
    private String userEmail;
    private String passwordHex;

    public UserAuthentication(User user) {
        userId = user.getUuid();
        userEmail = user.getEmail();
        passwordHex = user.getPasswordHashHex();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHex;
    }

    @Override
    public String getUsername() {
        return userEmail;
    }
}
