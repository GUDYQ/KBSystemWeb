package org.example.kbsystemproject.config;

import org.example.kbsystemproject.base.security.vertify.UserAccessDeniedHandler;
import org.example.kbsystemproject.base.security.vertify.UserAuthenticationEntryPoint;
import org.example.kbsystemproject.utils.PBKDF2Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

import java.util.stream.Collectors;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Autowired
    private ReactiveJwtDecoder jwtDecoder;

    @Autowired
    private ReactiveUserDetailsService userDetailsService;

    @Autowired
    private PBKDF2Util pbkdf2Util;


    public UserAccessDeniedHandler userAccessDeniedHandler() {
        return new UserAccessDeniedHandler();
    }

    public UserAuthenticationEntryPoint userAuthenticationEntryPoint() {
        return new UserAuthenticationEntryPoint();
    }

    @Bean
    public ReactiveAuthenticationManager reactiveAuthenticationManager() {
        UserDetailsRepositoryReactiveAuthenticationManager authenticationManager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        authenticationManager.setPasswordEncoder(pbkdf2Util.getPasswordEncoder());
        return authenticationManager;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity httpSecurity) {
        UserAccessDeniedHandler userAccessDeniedHandler = userAccessDeniedHandler();
        return httpSecurity.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(authorize ->
                                authorize.pathMatchers("/api/user/**", "/api/test/**", "/api/ai-learning/**", "/actuator/**"
                                    ,       "/api/upload/**").permitAll()
                                        .anyExchange().authenticated())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .oauth2ResourceServer(oAuth2 -> oAuth2.jwt(
                        jwtSpec -> jwtSpec.jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .accessDeniedHandler(userAccessDeniedHandler))
                .exceptionHandling(exception -> {
                    exception.authenticationEntryPoint(userAuthenticationEntryPoint())
                            .accessDeniedHandler(userAccessDeniedHandler);
                })
                .build();
    }

    private ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            return jwt.getClaimAsStringList("authorities").stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        });
        converter.setPrincipalClaimName("userId");

        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }


}
