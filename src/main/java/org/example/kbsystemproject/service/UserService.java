package org.example.kbsystemproject.service;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.base.exception.TokenErrorException;
import org.example.kbsystemproject.base.exception.UseException;
import org.example.kbsystemproject.entity.User;
import org.example.kbsystemproject.entity.UserAuthentication;
import org.example.kbsystemproject.entity.UserToken;
import org.example.kbsystemproject.reposity.UserRepository;
import org.example.kbsystemproject.utils.PBKDF2Util;
import org.example.kbsystemproject.utils.RedisUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private PBKDF2Util pbkdf2Util;

    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private ReactiveAuthenticationManager reactiveAuthenticationManager;

    public Mono<UserToken> login(String userEmail, String password) throws UseException {
        return reactiveAuthenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(userEmail, password)
            ).onErrorMap(AuthenticationException.class, e -> new UseException(e.getMessage()))
                .flatMap(authentication -> {
                List<String> authorities = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).toList();
                String userId = authentication.getName();
                return Mono.zip(
                        tokenService.generateRefreshToken(),
                        tokenService.generateAccessToken(userId, authorities)
                ).flatMap(tuple -> {
                    String refreshToken = tuple.getT1();
                    String accessToken = tuple.getT2();
                    return redisUtils.setExpire(
                            RedisUtils.RedisKeyGenerator.userRefreshToken(userId),
                                    refreshToken,
                                    3600)
                            .thenReturn(UserToken.builder()
                                    .refreshToken(refreshToken)
                                    .accessToken(accessToken)
                                    .build()
                            );
                });
        });
    }

    public Mono<Boolean> register(String userEmail, String username, String password) {
        if (userEmail == null || userEmail.isBlank()) {
            return Mono.error(new IllegalArgumentException("UserEmail is required"));
        }
        if (username == null || username.isBlank()) {
            return Mono.error(new IllegalArgumentException("Username is required"));
        }
        if (password == null || password.length() < 8) {
            return Mono.error(new IllegalArgumentException("Password too weak"));
        }
        return Mono.fromCallable(() -> pbkdf2Util.hashPassword(password))
                .map(passwordHex -> User.builder()
                        .uuid(UUID.randomUUID().toString())
                        .email(userEmail)
                        .username(username)
                        .passwordHashHex(passwordHex)
                        .createdTime(LocalDateTime.now())
                        .isActive(Boolean.TRUE)
                        .newUser(Boolean.TRUE)
                        .build())
                .flatMap(user -> userRepository.save(user))
                .thenReturn(Boolean.TRUE);
    }

    public Mono<String> getAccessToken(String userEmail, String refreshToken) throws TokenErrorException {
        if (refreshToken == null)
            return Mono.error(new TokenErrorException("input token error"));
        return userRepository.findFirstByEmail(userEmail)
                .switchIfEmpty(Mono.error(new TokenErrorException("user unexists")))
                .map(UserAuthentication::new)
                .flatMap(auth -> {
                    String userId = auth.getUserId();
                    List<String> authorities = auth.getAuthorities().stream()
                            .map(GrantedAuthority::toString)
                            .toList();
                    return redisUtils.get(
                            RedisUtils.RedisKeyGenerator.userRefreshToken(userId)
                    ).filter(refreshToken::equals)
                            .switchIfEmpty(Mono.error(new TokenErrorException("token error")))
                            .then(tokenService.generateAccessToken(userId, authorities));
                });
    }
}
