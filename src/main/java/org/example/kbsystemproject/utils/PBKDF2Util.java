package org.example.kbsystemproject.utils;

import jakarta.annotation.PostConstruct;
import org.example.kbsystemproject.config.PasswordKeyConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PBKDF2Util {
    @Autowired
    private PasswordKeyConfig passwordKeyConfig;
    private Pbkdf2PasswordEncoder encoder;

    @PostConstruct
    public void init() {
        encoder = new Pbkdf2PasswordEncoder(
                passwordKeyConfig.getSecretCode(),
                passwordKeyConfig.getSaltLength(),
                passwordKeyConfig.getHashIterations(),
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256
        );
    }

    public PasswordEncoder getPasswordEncoder() {
        return encoder;
    }

    public String hashPassword(String password) {
        return encoder.encode(password);
    }

    public Boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
