package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Measurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MeasurementRepository extends JpaRepository<Measurement, UUID> {

    List<Measurement> findByRunId(UUID runId);

    /** 指標の推移。横軸に並べる計測の値をまとめて読む。 */
    List<Measurement> findByRunIdIn(Collection<UUID> runIds);

    /**
     * 判定の履歴。Run ごとの判定の範囲を 1 回の集計で読む（Run ごとに結果を読み出すと、履歴の件数だけ問い合わせが増える）。
     * 結果の無い Run は返さない。
     */
    @Query(value = """
            select m.run_id as "runId",
                   count(*) filter (where m.status <> 'NOT_APPLICABLE') as "judged",
                   count(*) filter (where m.status = 'NOT_APPLICABLE') as "notApplicable",
                   coalesce(bool_or(m.threshold ->> 'relaxed' = 'true'), false) as "relaxed",
                   coalesce(bool_or(case when jsonb_typeof(m.detail -> 'excludedFiles') = 'number'
                                         then cast(m.detail ->> 'excludedFiles' as numeric) > 0
                                         else false end), false) as "filesExcluded"
            from measurements m
            where m.run_id in (:runIds)
            group by m.run_id
            """, nativeQuery = true)
    List<RunScopeSummary> summarizeScopeByRunIds(@Param("runIds") Collection<UUID> runIds);

    /** Run ごとの判定の範囲の集計（{@link #summarizeScopeByRunIds}）。 */
    interface RunScopeSummary {

        UUID getRunId();

        /** 合否に使った結果の件数（対象外を除く）。 */
        long getJudged();

        /** 対象外（{@code NOT_APPLICABLE}）の結果の件数。 */
        long getNotApplicable();

        /** 既定より緩い合格ラインで判定した結果があるか。 */
        boolean getRelaxed();

        /** {@code QG_EXCLUSIONS} で計測から外したファイルのある結果があるか。 */
        boolean getFilesExcluded();
    }
}
