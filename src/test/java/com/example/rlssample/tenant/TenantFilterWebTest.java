package com.example.rlssample.tenant;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「テナントは JWT のクレームで決まり、ヘッダはその中から選ぶだけ」
 * 「ユーザーIDとロールは、そのテナントの users テーブルで決まる」を HTTP 経由で確認する。
 * ユーザーは db/init/03_data.sql のもの（bob: A商事の一般、taro: A商事の一般かつ B工業の管理者）。
 * 事前に docker compose up -d で DB を起動しておくこと。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantFilterWebTest {

    @Autowired
    MockMvc mvc;

    @Value("${app.jwt.secret}")
    String secret;

    // ---- 認証 ------------------------------------------------------------

    @Test
    @DisplayName("トークンなし → 401")
    void noToken_isUnauthorized() throws Exception {
        mvc.perform(get("/api/orders").header("X-Tenant-Id", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("別の鍵で署名した（改ざんした）トークン → 401")
    void tokenSignedWithOtherKey_isUnauthorized() throws Exception {
        String forged = token("another-secret-another-secret-0123456789", "dave", 2);

        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    // ---- テナントの決定 ----------------------------------------------------

    @Test
    @DisplayName("所属1社・ヘッダなし → その1社として動く")
    void singleTenant_withoutHeader_usesClaim() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", bearer("bob", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId", is(1)))
                .andExpect(jsonPath("$.userId", is(2)));
    }

    @Test
    @DisplayName("所属1社・同じテナントをヘッダで指定 → OK")
    void singleTenant_withMatchingHeader_isOk() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", bearer("bob", 1)).header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId", is(1)));
    }

    @Test
    @DisplayName("なりすまし: A商事のユーザーが X-Tenant-Id: 2 を送る → 403")
    void headerForOtherTenant_isForbidden() throws Exception {
        mvc.perform(get("/api/orders/leaky").header("Authorization", bearer("bob", 1)).header("X-Tenant-Id", "2"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("所属2社・ヘッダなし → どちらか決められないので 400")
    void multiTenant_withoutHeader_isBadRequest() throws Exception {
        mvc.perform(get("/api/orders").header("Authorization", bearer("taro", 1, 2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("tenant_ids クレームのないトークン → 403")
    void tokenWithoutTenantClaim_isForbidden() throws Exception {
        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + token(secret, "bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("数値でない X-Tenant-Id → 400")
    void malformedHeader_isBadRequest() throws Exception {
        mvc.perform(get("/api/orders").header("Authorization", bearer("bob", 1)).header("X-Tenant-Id", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ---- ユーザーの解決 ----------------------------------------------------

    @Test
    @DisplayName("トークンの tenant_ids に入っていても、そのテナントの users に登録がなければ 403")
    void notRegisteredInTenant_isForbidden() throws Exception {
        mvc.perform(get("/api/orders").header("Authorization", bearer("ghost", 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("同じ人でもロールはテナントごと: taro は A商事では一般、B工業では管理者")
    void roleIsPerTenant() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", bearer("taro", 1, 2)).header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("GENERAL")));
        mvc.perform(get("/api/me").header("Authorization", bearer("taro", 1, 2)).header("X-Tenant-Id", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }

    // ---- ロールごとの見え方 ------------------------------------------------

    @Test
    @DisplayName("一般ユーザー（bob）は WHERE なしの一覧でも自分の受注だけ")
    void general_seesOnlyOwnOrders() throws Exception {
        mvc.perform(get("/api/orders/leaky").header("Authorization", bearer("bob", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[*].tenantId", everyItem(is(1))))
                .andExpect(jsonPath("$[*].userId", everyItem(is(2))));
    }

    @Test
    @DisplayName("B工業の管理者として動く taro は、B工業の全員の受注が見える（A商事の受注は出ない）")
    void admin_seesAllOrdersInTenant() throws Exception {
        mvc.perform(get("/api/orders/leaky").header("Authorization", bearer("taro", 1, 2)).header("X-Tenant-Id", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].tenantId", everyItem(is(2))));
    }

    @Test
    @DisplayName("一般ユーザーがユーザー一覧を取得 → 403")
    void general_cannotListUsers() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", bearer("bob", 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("一般ユーザーが同じテナントの他人の受注（id=2 はキャロルの受注）を取得 → 404")
    void general_cannotGetOthersOrder() throws Exception {
        mvc.perform(get("/api/orders/2").header("Authorization", bearer("bob", 1)))
                .andExpect(status().isNotFound());
    }

    private String bearer(String subject, long... tenantIds) {
        return "Bearer " + token(secret, subject, tenantIds);
    }

    private static String token(String secret, String subject, long... tenantIds) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (tenantIds.length > 0) {
            claims.claim(TenantFilter.TENANTS_CLAIM, Arrays.stream(tenantIds).boxed().toList());
        }
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
