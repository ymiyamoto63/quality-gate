package com.qualitygate.platform.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * トークンバケットによるレート制限（docs/initial/07-api-design.md 8 章）。
 *
 * <p>バケットはキー（トークン・利用者・IP と制限の種類の組）ごとに持ち、容量は 1 分あたりの上限、
 * 補充は上限 ÷ 60 秒の速さで連続的に行う。固定の 1 分窓と違い、窓の境目に上限の 2 倍が通ることがない。
 *
 * <p>単一ホストでの運用（D-2 / Q-10）を前提に、プロセス内のメモリに持つ。複数台に増やすときは
 * 共有ストアに移す。使われなくなったバケットは、数が増えたときにまとめて捨てる。
 */
@Component
public class RateLimiter {

    /** これを超えたら、満杯に戻ったバケット（しばらく使われていない）を捨てる。 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 1 回分を消費できるか。
     *
     * @param key       制限の単位（例: {@code ingest:<トークン ID>}）
     * @param perMinute 1 分あたりの上限（1 以上）
     */
    public Decision tryAcquire(String key, int perMinute) {
        long now = clock.millis();
        if (buckets.size() > CLEANUP_THRESHOLD) {
            buckets.entrySet().removeIf(entry -> entry.getValue().isFull(now));
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(perMinute, now)).tryAcquire(perMinute, now);
    }

    /**
     * @param allowed           通してよいか
     * @param retryAfterSeconds 通さない場合、次の 1 回分がたまるまでの秒数（切り上げ、1 以上）
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final class Bucket {

        private double tokens;
        private long updatedAt;
        private int capacity;

        Bucket(int capacity, long now) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.updatedAt = now;
        }

        synchronized Decision tryAcquire(int perMinute, long now) {
            capacity = perMinute;   // 設定の変更を次の呼び出しから反映する
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, 0);
            }
            double perMilli = capacity / 60_000.0;
            long waitMillis = (long) Math.ceil((1 - tokens) / perMilli);
            return new Decision(false, Math.max(1, (waitMillis + 999) / 1000));
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            long elapsed = Math.max(0, now - updatedAt);
            tokens = Math.min(capacity, tokens + elapsed * (capacity / 60_000.0));
            updatedAt = now;
        }
    }
}
