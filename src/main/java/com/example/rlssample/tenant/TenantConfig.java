package com.example.rlssample.tenant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class TenantConfig {

    /**
     * Spring Boot の自動設定は TransactionManager が未定義のときだけ作られるので、
     * ここで定義すればテナント対応版に置き換わる。MyBatis もこの TransactionManager の
     * トランザクション（同じ Connection）に参加する。
     */
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new TenantAwareTransactionManager(dataSource);
    }
}
