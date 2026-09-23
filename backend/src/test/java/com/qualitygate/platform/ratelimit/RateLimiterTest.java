package com.qualitygate.platform.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RateLimiter limiter = new RateLimiter(new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    });

    @Test
    void 上限まで通しそれを超えると待つ秒数を返す() {
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryAcquire("ingest:a", 60).allowed()).isTrue();
        }

        RateLimiter.Decision rejected = limiter.tryAcquire("ingest:a", 60);

        assertThat(rejected.allowed()).isFalse();
        // 60 回 / 分なら 1 秒で 1 回分たまる
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void 時間が経つと上限の速さで回復する() {
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("query:u", 6);
        }
        assertThat(limiter.tryAcquire("query:u", 6).allowed()).isFalse();

        now.addAndGet(10_000);   // 6 回 / 分 = 10 秒に 1 回
        assertThat(limiter.tryAcquire("query:u", 6).allowed()).isTrue();
        assertThat(limiter.tryAcquire("query:u", 6).allowed()).isFalse();

        now.addAndGet(3_600_000);  // 長く空いても上限以上にはたまらない
        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire("query:u", 6).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("query:u", 6).allowed()).isFalse();
    }

    @Test
    void キーごとに別の枠を持つ() {
        assertThat(limiter.tryAcquire("ingest:a", 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("ingest:a", 1).allowed()).isFalse();

        assertThat(limiter.tryAcquire("ingest:b", 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("upload:a", 1).allowed()).isTrue();
    }

    @Test
    void 待つ秒数は切り上げる() {
        limiter.tryAcquire("badge:ip", 1);

        // 1 回 / 分なら 60 秒待つ
        assertThat(limiter.tryAcquire("badge:ip", 1).retryAfterSeconds()).isEqualTo(60);
        now.addAndGet(59_500);
        assertThat(limiter.tryAcquire("badge:ip", 1).retryAfterSeconds()).isEqualTo(1);
    }
}
