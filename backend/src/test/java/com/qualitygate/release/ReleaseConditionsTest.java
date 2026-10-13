package com.qualitygate.release;

import com.qualitygate.domain.model.JudgementScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseConditionsTest {

    @Test
    void 範囲を狭める設定だけを判定の条件として出す() {
        List<ReleaseReportResponse.ReleaseCondition> conditions = ReleaseConditions.of(new JudgementScope(
                List.of(), List.of("**/generated/**"), List.of("backend"), List.of(), List.of()));

        assertThat(conditions).extracting(ReleaseReportResponse.ReleaseCondition::key)
                .containsExactly("exclusions", "mutationComponents", "perfScenarios", "accessibilityPages");
        assertThat(conditions.get(2).value()).isEqualTo("指定なし");
        assertThat(conditions.get(2).note()).startsWith("シナリオ（計測する操作）を限定せずに判定しています");
        assertThat(conditions.get(3).setting()).isEqualTo("QG_ACCESSIBILITY_PAGES");
    }

    @Test
    void シナリオや画面を指定して確かめる範囲を広げた設定は出さない() {
        List<ReleaseReportResponse.ReleaseCondition> conditions = ReleaseConditions.of(new JudgementScope(
                List.of(), List.of(), List.of(), List.of("list"), List.of("/")));

        assertThat(conditions).isEmpty();
    }

    @Test
    void 判定しなかった指標の条件は出さない() {
        // 除外が効く指標をすべて外していれば、除外パターンも判定に効いていない
        List<ReleaseReportResponse.ReleaseCondition> conditions = ReleaseConditions.of(new JudgementScope(
                List.of("M-01", "M-02", "M-03", "M-05", "M-06", "M-08", "M-11", "M-12"),
                List.of("**/generated/**"), List.of("backend"), List.of(), List.of()));

        assertThat(conditions).isEmpty();
    }

    @Test
    void 除外の説明には判定した指標の名前だけを並べる() {
        List<ReleaseReportResponse.ReleaseCondition> conditions = ReleaseConditions.of(new JudgementScope(
                List.of("M-02", "M-05", "M-06", "M-12"), List.of("config.py", "**/*.d.ts"), List.of(), List.of("list"),
                List.of("/")));

        assertThat(conditions).singleElement().satisfies(condition -> {
            assertThat(condition.value()).isEqualTo("config.py, **/*.d.ts");
            assertThat(condition.note()).contains("ブランチカバレッジ・シークレット検出件数 の計測に含めていません");
        });
    }
}
