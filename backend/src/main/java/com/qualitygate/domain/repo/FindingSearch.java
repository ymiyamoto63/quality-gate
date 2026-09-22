package com.qualitygate.domain.repo;

import com.qualitygate.domain.entity.Finding;

import java.util.List;

/** 違反一覧の検索。条件が可変なため、導出クエリではなく組み立て式にする。 */
public interface FindingSearch {

    /**
     * 条件に合う違反を、状態（新規 → 継続 → 初回 → 解消）→ 深刻度（重大 → …）の順に返す。
     *
     * <p>並び替えを DB 側で行うのは、ページングと並び順が食い違わないようにするため。
     * アプリ側で並べ替えると、1 ページ分だけ取得したあとに並べ替えることになり、
     * ページ全体を通した順序が壊れる。
     */
    List<Finding> search(FindingCriteria criteria, int offset, int limit);

    long count(FindingCriteria criteria);
}
