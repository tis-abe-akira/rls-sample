package com.example.rlssample.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * リクエストの入口で「どのテナントの、誰として動くか」を確定させるフィルタ。
 * <p>
 * <b>1. テナントを決める</b><br>
 * テナントの根拠は <b>署名検証済みの JWT のクレーム {@code tenant_ids}</b>（そのユーザーが所属するテナントの一覧）だけ。
 * ヘッダ {@code X-Tenant-Id} は「所属テナントの中からどれを使うか」を選ぶためのもので、それ自体は信用しない。
 * <ul>
 *   <li>ヘッダがクレームに含まれないテナント → 403（なりすましの試み）</li>
 *   <li>ヘッダなし・所属が1社 → その1社</li>
 *   <li>ヘッダなし・所属が複数社 → 400（どれか決められない）</li>
 * </ul>
 * <b>2. テナントの中の誰かを決める</b><br>
 * JWT の {@code sub} で、そのテナントの users テーブルを引き、ユーザーIDとロールを解決する。
 * そのテナントに登録されていなければ 403。ロールを JWT ではなく DB から取るので、ロールの変更はすぐに反映される。
 * <p>
 * Spring Security のフィルタチェーンで、認証・認可（AuthorizationFilter）の後に置く（{@code SecurityConfig}）。
 */
public class TenantFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Tenant-Id";
    static final String TENANTS_CLAIM = "tenant_ids";

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

    private final CurrentUserResolver currentUserResolver;

    public TenantFilter(CurrentUserResolver currentUserResolver) {
        this.currentUserResolver = currentUserResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            reject(response, HttpStatus.UNAUTHORIZED, "authentication is required");
            return;
        }

        TenantId tenantId;
        CurrentUser user;
        try {
            tenantId = resolveTenant(jwtAuth.getToken(), request.getHeader(HEADER));
            user = resolveUser(jwtAuth.getToken(), tenantId);
        } catch (Rejection e) {
            reject(response, e.status, e.getMessage());
            return;
        }

        try {
            TenantContext.runAs(tenantId, () -> UserContext.runAs(user, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new FilterChainException(e);
                }
            }));
        } catch (FilterChainException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw (ServletException) cause;
        }
    }

    /** 1. JWT のクレーム tenant_ids とヘッダから、テナントを決める */
    private static TenantId resolveTenant(Jwt jwt, String header) {
        Set<TenantId> allowed = allowedTenants(jwt);
        if (allowed.isEmpty()) {
            throw new Rejection(HttpStatus.FORBIDDEN, "token has no " + TENANTS_CLAIM + " claim");
        }
        if (header == null) {
            if (allowed.size() != 1) {
                throw new Rejection(HttpStatus.BAD_REQUEST, HEADER + " header is required for users in multiple tenants");
            }
            return allowed.iterator().next();
        }

        TenantId requested;
        try {
            requested = TenantId.of(Long.parseLong(header));
        } catch (RuntimeException e) {
            throw new Rejection(HttpStatus.BAD_REQUEST, HEADER + " header must be a positive number");
        }
        if (!allowed.contains(requested)) {
            // 所属していないテナントの指定はなりすましの試みなので、監査用に記録しておく
            log.warn("Tenant access denied: subject={} requested={} allowed={}",
                    jwt.getSubject(), requested.value(), allowed);
            throw new Rejection(HttpStatus.FORBIDDEN, "not a member of tenant " + requested.value());
        }
        return requested;
    }

    /** 2. テナントを確定させた状態で users テーブルを引き、テナントの中の誰かを決める（RLS により自分の行だけが見える） */
    private CurrentUser resolveUser(Jwt jwt, TenantId tenantId) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new Rejection(HttpStatus.FORBIDDEN, "token has no sub claim");
        }
        Optional<CurrentUser> user = TenantContext.runAs(tenantId, () ->
                UserContext.runAs(CurrentUser.unresolved(subject), currentUserResolver::resolve));
        if (user.isEmpty()) {
            log.warn("User not registered in tenant: subject={} tenant={}", subject, tenantId.value());
            throw new Rejection(HttpStatus.FORBIDDEN, "not registered in tenant " + tenantId.value());
        }
        return user.get();
    }

    /** クレームの値は数値でも文字列でも受け付ける。解釈できない要素があればクレーム全体を無効とする。 */
    private static Set<TenantId> allowedTenants(Jwt jwt) {
        if (!(jwt.getClaim(TENANTS_CLAIM) instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<TenantId> tenants = new LinkedHashSet<>();
        try {
            for (Object value : values) {
                tenants.add(TenantId.of(Long.parseLong(String.valueOf(value))));
            }
        } catch (RuntimeException e) {
            return Set.of();
        }
        return tenants;
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getWriter().write("{\"status\":%d,\"title\":\"%s\",\"detail\":\"%s\"}"
                .formatted(status.value(), status.getReasonPhrase(), detail));
    }

    /** テナントやユーザーを決められなかったときに、返すステータスと理由を運ぶ */
    private static final class Rejection extends RuntimeException {
        private final HttpStatus status;

        Rejection(HttpStatus status, String detail) {
            super(detail);
            this.status = status;
        }
    }

    /** ラムダ内のチェック例外を外へ運ぶためだけの入れ物 */
    private static final class FilterChainException extends RuntimeException {
        FilterChainException(Exception cause) {
            super(cause);
        }
    }
}
