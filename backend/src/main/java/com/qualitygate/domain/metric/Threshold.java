package com.qualitygate.domain.metric;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判定に使った合格ライン。判定時に measurements.threshold へ JSON で焼き付け、リリース判定の表示で読み戻す。
 *
 * <p>JSON の形（キー {@code operator} / {@code value} / {@code basis} と指標固有の補足）は変えない。
 * 保存済みの Run の行もこの形で読むため、形を変えると過去の判定の合格ラインが表示できなくなる。
 *
 * @param operator 比較の向き（{@link #AT_LEAST} / {@link #AT_MOST}）。合格ラインを持たない結果では null
 * @param value    合格ラインの値。合格ラインを持たない結果では null
 * @param basis    何と比べるか。{@link #INCREASE} なら比較対象 Run からの増加で判定する。値そのものなら null
 * @param extras   指標固有の補足（M-06 の関数ごとの上限 {@code maxComplexity} など）。判定の根拠として残すだけで表示には使わない
 */
public record Threshold(String operator, BigDecimal value, String basis, Map<String, Object> extras) {

    public static final String AT_LEAST = ">=";
    public static final String AT_MOST = "<=";
    /**
     * 比較対象 Run からの増加で判定したことを示す。今はどの指標も使わないが、M-10 を増加で判定していた
     * ころ（DD-25 より前）に保存した行を読み戻して表示するために残す。
     */
    public static final String INCREASE = "increase";

    private static final String OPERATOR = "operator";
    private static final String VALUE = "value";
    private static final String BASIS = "basis";

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

    /** 比較対象 Run からの増加が {@code count} 以下なら合格（DD-25 より前の M-10。保存済みの行の表示とテストで使う）。 */
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
