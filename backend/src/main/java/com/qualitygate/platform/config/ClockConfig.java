package com.qualitygate.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 現在時刻の取得元。
 *
 * <p>「計測が途絶えているか」のように現在時刻で変わる応答は、時計を差し替えられるようにしておく。
 * {@code Instant.now()} を直接呼ぶと、テストの結果（と、そこから書き出す画面の応答例）が実行した日で変わる。
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
