package org.example.kbsystemproject.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class TokenService {

    @Autowired
    private JwtEncoder jwtEncoder;

    public Mono<String> generateAccessToken(String userId, List<String> authorities) {
        Instant now = Instant.now();

        JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
                .issuer("your-app")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(userId)
                .claim("authorities", authorities)
                .build();

        return Mono.just(jwtEncoder.encode(JwtEncoderParameters.from(jwtClaimsSet)).getTokenValue());
    }

    public Mono<String> generateRefreshToken() {
        return Mono.just(UUID.randomUUID().toString());
    }
}
