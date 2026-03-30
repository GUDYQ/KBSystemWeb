package org.example.kbsystemproject.service;

import io.r2dbc.postgresql.authentication.UsernameAndPassword;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.entity.UserAuthentication;
import org.example.kbsystemproject.reposity.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class UserAndPasswordService implements ReactiveUserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public Mono<UserDetails> findByUsername(String userEmail) {
        return userRepository.findFirstByEmail(userEmail)
                .switchIfEmpty(Mono.error(new UsernameNotFoundException("Not find user")))
                .map(UserAuthentication::new);
    }
}
