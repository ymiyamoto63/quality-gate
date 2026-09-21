package com.qualitygate;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 結合テストの共通設定。
 *
 * <p>実際の PostgreSQL に対してテストする。H2 などの代替 DB を使うと、
 * jsonb・部分一意インデックス・CHECK 制約といった本番で効いている仕組みを
 * 検証できないためである。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ActiveProfiles("test")
@Import(AbstractIntegrationTest.Containers.class)
public @interface AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }
}
