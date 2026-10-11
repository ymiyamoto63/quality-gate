package com.qualitygate.domain.metric;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 判定に使った合格ライン。判定時に measurements.threshold へ JSON で焼き付け、リリース判定の表示で読み戻す。
 *
 * <p>JSON の形（キー {@code operator} / {@code value} / {@code basis} と指標固有の補足）は変えない。
 * 保存済みの Run の行もこの形で読むため、形を変えると過去の判定の合格ラインが表示できなくなる。
 *
 * @param operator 比較の向き（{@link #AT_LEAST} / {@link #AT_MOST}）。合格ラインを持たない結果では null
 * @param value    合格ラインの値。合格ラインを持たない結果では null
 * @param basis    何と比べるか。{@link #INCREASE} なら比較対象 Run からの増加で判定する。値そのものなら null
 * @param extras   指標固有の補足（M-06 の関数ごとの上限 {@code maxComplexity} など）。判定の根拠として残す。
 *                 既定の合格ラインより緩いときは、その印（{@code relaxed} / {@code default}）もここに入る（{@link #relaxedFrom(Threshold)}）
 */
public record Threshold(String operator, BigDecimal value, String basis, Map<String, Object> extras) {

    public static final String AT_LEAST = ">=";
    public static final String AT_MOST = "<=";
    /** M-10 は件数そのものではなく、比較対象 Run からの増加で判定する。 */
    public static final String INCREASE = "increase";

    private static final String OPERATOR = "operator";
    private static final String VALUE = "value";
    private static final String BASIS = "basis";
    /** 既定の合格ラインより緩いとき true（{@link #relaxedFrom(Threshold)}）。 */
    private static final String RELAXED = "relaxed";
    /** 緩いときの、既定の合格ライン（{@link #toMap()} の形）。 */
    private static final String DEFAULT = "default";

    /**
     * 合格ラインの一部をなす補足（上限・下限の値）と、その向き。緩さの比較に使う。
     * {@link #AT_MOST} は値が大きいほど緩く、{@link #AT_LEAST} は小さいほど緩い。
     * ここに無い補足（M-08 の {@code standard} など）は比べない。
     */
    private static final Map<String, Limit> LIMIT_EXTRAS = Map.of(
            "maxComplexity", new Limit(AT_MOST, "関数ごとの循環的複雑度の上限"),
            "maxCritical", new Limit(AT_MOST, "Critical の上限"),
            "maxHigh", new Limit(AT_MOST, "High の上限"),
            "minTestCount", new Limit(AT_LEAST, "最小実行件数"),
            "arrivalRateRps", new Limit(AT_LEAST, "負荷条件（到達率 req/s）"));

    /**
     * 補足の上限・下限。
     *
     * @param operator 比較の向き
     * @param label    画面に出す名称（既定値の説明に使う）
     */
    public record Limit(String operator, String label) {
    }

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private static final Threshold NONE = new Threshold(null, null, null, Map.of());

    public Threshold {
        extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /** 合格ラインを持たない結果（計測エラー・対象外）。JSON では空のオブジェクトになる。 */
    public static Threshold none() {
        return NONE;
    }

    /** 値が {@code value} 以上なら合格。 */
    public static Threshold atLeast(BigDecimal value) {
        return new Threshold(AT_LEAST, value, null, Map.of());
    }

    /** 値が {@code value} 以下なら合格。 */
    public static Threshold atMost(BigDecimal value) {
        return new Threshold(AT_MOST, value, null, Map.of());
    }

    /** 件数が {@code count} 以下なら合格。 */
    public static Threshold atMost(long count) {
        return atMost(BigDecimal.valueOf(count));
    }

    /** 比較対象 Run からの増加が {@code count} 以下なら合格。 */
    public static Threshold increaseAtMost(long count) {
        return new Threshold(AT_MOST, BigDecimal.valueOf(count), INCREASE, Map.of());
    }

    /** 指標固有の補足を足したものを返す。 */
    public Threshold with(String key, Object extra) {
        Map<String, Object> next = new LinkedHashMap<>(extras);
        next.put(key, extra);
        return new Threshold(operator, value, basis, next);
    }

    /** 比較対象 Run からの増加で判定するか。 */
    public boolean isIncrease() {
        return INCREASE.equals(basis);
    }

    /**
     * 既定の合格ライン {@code defaults} より緩ければ、その印（{@code relaxed: true} と既定の合格ライン {@code default}）を
     * 補足に足したものを返す。緩くなければ自身をそのまま返す。
     *
     * <p>指標ごとの知識は持たない。比較の向き（{@link #AT_LEAST} は小さいほど緩い、{@link #AT_MOST} は大きいほど緩い）と、
     * 上限・下限を表す補足（{@code maxComplexity} など）だけで決める。指標の判定の規則が変わっても、
     * 合格ラインの形（向きと値）さえ保てばそのまま使える。
     */
    public Threshold relaxedFrom(Threshold defaults) {
        if (!isValueLooserThan(defaults) && looserLimitsThan(defaults).isEmpty()) {
            return this;
        }
        return with(RELAXED, true).with(DEFAULT, defaults.toMap());
    }

    /** 合格ラインの値そのものが {@code other} より緩いか。向きか比べ方（{@code basis}）が違えば比べない（false）。 */
    public boolean isValueLooserThan(Threshold other) {
        return comparable(other) && looser(operator, value, other.value);
    }

    /** {@code other} より緩い上限・下限の補足のキー（{@link #limitOf(String)} で名称を引ける）。 */
    public List<String> looserLimitsThan(Threshold other) {
        if (!comparable(other)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        LIMIT_EXTRAS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> looser(entry.getValue().operator(),
                        decimalOf(extras.get(entry.getKey())), decimalOf(other.extras.get(entry.getKey()))))
                .forEach(entry -> keys.add(entry.getKey()));
        return keys;
    }

    /** 上限・下限を表す補足の向きと名称。上限・下限でない補足なら空。 */
    public static Optional<Limit> limitOf(String key) {
        return Optional.ofNullable(LIMIT_EXTRAS.get(key));
    }

    /** 補足の値を数として読む。無いか数でなければ null。 */
    public BigDecimal extraValue(String key) {
        return decimalOf(extras.get(key));
    }

    /** 判定時に、既定の合格ラインより緩いと記録されたか（{@link #relaxedFrom(Threshold)}）。 */
    public boolean isRelaxed() {
        return Boolean.TRUE.equals(extras.get(RELAXED));
    }

    /** 緩いと記録された合格ラインの、既定の合格ライン。記録が無ければ空。 */
    public Optional<Threshold> defaults() {
        if (extras.get(DEFAULT) instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, extra) -> copy.put(String.valueOf(key), extra));
            return Optional.of(fromMap(copy));
        }
        return Optional.empty();
    }

    private boolean comparable(Threshold other) {
        return operator != null && operator.equals(other.operator) && Objects.equals(basis, other.basis);
    }

    private static boolean looser(String operator, BigDecimal mine, BigDecimal reference) {
        if (mine == null || reference == null) {
            return false;
        }
        int compared = mine.compareTo(reference);
        return switch (operator) {
            case AT_LEAST -> compared < 0;
            case AT_MOST -> compared > 0;
            default -> false;
        };
    }

    /** measurements.threshold に保存する形。null の項目はキーごと出さない。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (operator != null) {
            map.put(OPERATOR, operator);
        }
        if (value != null) {
            map.put(VALUE, value);
        }
        if (basis != null) {
            map.put(BASIS, basis);
        }
        map.putAll(extras);
        return map;
    }

    /**
     * measurements.threshold の JSON を読む。空なら {@link #none()}。
     *
     * <p>型の合わない項目は無いものとして扱い、例外にしない。読めない合格ラインが 1 件あるだけで
     * リリース判定が開けなくなるのを避ける。
     */
    public static Threshold parse(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return NONE;
        }
        return fromMap(objectMapper.readValue(json, JSON_OBJECT));
    }

    static Threshold fromMap(Map<String, Object> map) {
        Map<String, Object> extras = new LinkedHashMap<>(map);
        Object operator = extras.remove(OPERATOR);
        Object value = extras.remove(VALUE);
        Object basis = extras.remove(BASIS);
        return new Threshold(
                operator instanceof String text ? text : null,
                decimalOf(value),
                basis instanceof String text ? text : null,
                extras);
    }

    private static BigDecimal decimalOf(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
