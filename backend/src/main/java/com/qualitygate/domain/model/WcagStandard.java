package com.qualitygate.domain.model;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * M-10 の判定基準とする WCAG の版とレベル（docs/initial/02-metrics-spec.md M-10）。
 *
 * <p>{@code tags} は axe-core のルールに付くタグのうち、その基準に含まれるもの。
 * 上位の基準は下位の達成基準をすべて含むため、累積で持つ（2.2 AA は 2.0 A を含む）。
 * axe-core には 2.2 の A レベルを表すタグが無く、2.2 で増えた A の達成基準は
 * {@code wcag22aa} ではなく既存のタグで扱われる。
 */
public enum WcagStandard implements WireValued {

    WCAG2A("wcag2a", Set.of("wcag2a")),
    WCAG2AA("wcag2aa", Set.of("wcag2a", "wcag2aa")),
    WCAG21A("wcag21a", Set.of("wcag2a", "wcag21a")),
    WCAG21AA("wcag21aa", Set.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa")),
    WCAG22AA("wcag22aa", Set.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"));

    /** 設定で省略された場合の基準（要件定義書 NFR 10.6）。 */
    public static final WcagStandard DEFAULT = WCAG22AA;

    private final String wire;
    private final Set<String> tags;

    WcagStandard(String wire, Set<String> tags) {
        this.wire = wire;
        this.tags = tags;
    }

    @Override
    public String wire() {
        return wire;
    }

    public Set<String> tags() {
        return tags;
    }

    /** ルールのタグのいずれかがこの基準に含まれるか。best-practice や AAA は含まれない。 */
    public boolean covers(Iterable<String> ruleTags) {
        for (String tag : ruleTags) {
            if (tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /** 未知の値は空を返す。受け付けるかどうかは呼び出し側が決める。 */
    public static Optional<WcagStandard> find(String wire) {
        return Arrays.stream(values()).filter(s -> s.wire.equals(wire)).findFirst();
    }
}
