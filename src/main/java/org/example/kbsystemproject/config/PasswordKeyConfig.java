package org.example.kbsystemproject.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@Data
@ConfigurationProperties("spring.config.password-key")
public class PasswordKeyConfig {
    private int hashIterations = 100; // 迭代次数
    private int hashKeyLength = 256;    // 密钥长度（位）
    private String keyAlgorithm = "PBKDF2WithHmacSHA256";
    private String secretCode = UUID.randomUUID().toString();
    private int saltLength = 16;
}

