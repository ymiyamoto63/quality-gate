package com.qualitygate.domain.metric;

import java.math.BigDecimal;

/**
 * 判定の理由や合格ラインの表示に出す数値の書き方。
 *
 * <p>判定（evaluate）と表示（release）で書き方が食い違わないよう、ここにだけ置く。
 */
public final class Decimals {

    private Decimals() {
    }

    /** 末尾の 0 と指数表記を除いた文字列（75.00 → 75、1E+2 → 100）。 */
    public static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
