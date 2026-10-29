package com.example.rlssample.tenant;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT（Bearer トークン）で認証し、その後ろで {@link TenantFilter} がテナントとユーザーを確定させる。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CurrentUserResolver currentUserResolver) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Cookie を使わない Bearer トークンの API なので CSRF 対策は不要
                .csrf(csrf -> csrf.disable())
                // 認証・認可が済んだリクエストだけがテナント確定に進む
                .addFilterAfter(new TenantFilter(currentUserResolver), AuthorizationFilter.class)
                .build();
    }

    /**
     * サンプル用に HS256 の共有鍵で検証する。署名・有効期限（exp）はここで検証される。
     * 本番では IdP の公開鍵で検証するので、この Bean を消して
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} を設定するだけでよい。
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
