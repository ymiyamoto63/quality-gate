package com.qualitygate.domain.model;

/** Run 全体の判定結果。すべての指標が合格なら PASS、1 つでも不合格・計測エラーがあれば FAIL。 */
public enum Verdict {
    PASS,
    FAIL
}
