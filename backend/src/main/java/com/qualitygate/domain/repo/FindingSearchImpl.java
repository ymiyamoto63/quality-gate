package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Finding;
import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.Severity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link FindingSearch} の実装。
 *
 * <p>並び順は「状態 → 深刻度」で固定する（docs/initial/08-screen-design.md 4.4）。
 * 列挙の宣言順や文字列としての順序はこの並びと一致しないため、
 * CASE で明示的に順位を与える。順位を列として持たないのは、順位の定義が
 * 表示の都合であり、保存された値の意味ではないためである。
 */
@Repository
class FindingSearchImpl implements FindingSearch {

    /** 状態の表示順。新規を先頭にするのは、今回の変更で増えたものが最も重要なため。 */
    private static final List<FindingState> STATE_ORDER =
            List.of(FindingState.NEW, FindingState.CONTINUING,
                    FindingState.INITIAL, FindingState.RESOLVED);

    private static final List<Severity> SEVERITY_ORDER =
            List.of(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM,
                    Severity.LOW, Severity.INFO);

    private static final String ORDER_BY = " order by "
            + rankCase("f.state", "st", STATE_ORDER.size())
            + ", " + rankCase("f.severity", "sv", SEVERITY_ORDER.size())
            // 同順位の中でも並びが毎回変わらないよう id を最後の鍵にする。
            // 安定しないとページ送りで重複・欠落が起きる。
            + ", f.id";

    private final EntityManager entityManager;

    FindingSearchImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<Finding> search(FindingCriteria criteria, int offset, int limit) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        buildWhere(criteria, conditions, parameters);

        TypedQuery<Finding> query = entityManager.createQuery(
                "select f from Finding f where " + String.join(" and ", conditions) + ORDER_BY,
                Finding.class);
        bind(query, parameters);
        bindRanks(query);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    @Override
    public long count(FindingCriteria criteria) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        buildWhere(criteria, conditions, parameters);

        TypedQuery<Long> query = entityManager.createQuery(
                "select count(f) from Finding f where " + String.join(" and ", conditions),
                Long.class);
        bind(query, parameters);
        return query.getSingleResult();
    }

    private static void buildWhere(FindingCriteria criteria, List<String> conditions,
                                   Map<String, Object> parameters) {
        conditions.add("f.runId = :runId");
        parameters.put("runId", criteria.runId());

        if (!criteria.metricIds().isEmpty()) {
            conditions.add("f.metricId in :metricIds");
            parameters.put("metricIds", criteria.metricIds());
        }
        if (!criteria.states().isEmpty()) {
            conditions.add("f.state in :states");
            parameters.put("states", criteria.states());
        }
        if (!criteria.severities().isEmpty()) {
            conditions.add("f.severity in :severities");
            parameters.put("severities", criteria.severities());
        }
        if (criteria.waived() != null) {
            conditions.add(criteria.waived() ? "f.waiverId is not null" : "f.waiverId is null");
        }
    }

    private static void bind(TypedQuery<?> query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
    }

    /**
     * 並び順の CASE に使う列挙値を束縛する。
     *
     * <p>JPQL に列挙定数をリテラルとして埋め込むと完全修飾名が必要になり、
     * 名前の変更に追従できない。パラメータとして渡せば型で守られる。
     */
    private void bindRanks(TypedQuery<?> query) {
        for (int i = 0; i < STATE_ORDER.size(); i++) {
            query.setParameter("st" + i, STATE_ORDER.get(i));
        }
        for (int i = 0; i < SEVERITY_ORDER.size(); i++) {
            query.setParameter("sv" + i, SEVERITY_ORDER.get(i));
        }
    }

    private static String rankCase(String path, String prefix, int size) {
        StringBuilder sql = new StringBuilder("case ").append(path);
        for (int i = 0; i < size; i++) {
            sql.append(" when :").append(prefix).append(i).append(" then ").append(i);
        }
        return sql.append(" else ").append(size).append(" end").toString();
    }
}
