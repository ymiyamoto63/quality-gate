package com.qualitygate.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * モジュール間の依存規則（docs/spec/05-architecture.md 1 章）を機械的に検証する。
 *
 * <p>規則が文書にしか存在しないと、半年後には守られていない。
 */
@AnalyzeClasses(packages = "com.qualitygate",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleDependencyTest {

    // adapter / evaluate / normalize は Phase 1 の次ステップで実装する。
    // 先に規則を置いておくことで、実装が入った瞬間から検証が効く。
    // 対象クラスが未作成の間は allowEmptyShould で失敗させない。

    @ArchTest
    static final ArchRule パーサは判定を知らない = noClasses()
            .that().resideInAPackage("..adapter..")
            .should().dependOnClassesThat().resideInAPackage("..evaluate..")
            .because("パーサはツールの出力を読むだけの責務に留める。判定基準が変わってもパーサは変わらない")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule 判定はパーサを知らない = noClasses()
            .that().resideInAPackage("..evaluate..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")
            .because("判定は正規化モデルだけを入力とする。ツールを差し替えても判定ロジックは変わらない")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule 参照系は書き込み系を呼ばない = noClasses()
            .that().resideInAPackage("..query..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..ingest..", "..normalize..", "..evaluate..")
            .because("参照系は保存済みの判定結果を読むだけで、書き込み側の都合に引きずられない")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule 共通基盤は業務ロジックを知らない = noClasses()
            .that().resideInAPackage("..platform..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..ingest..", "..query..", "..normalize..", "..evaluate..")
            .because("共通基盤が業務ロジックを知らない状態を保つ");

    @ArchTest
    static final ArchRule 判定は設定の取得方法を知らない = noClasses()
            .that().resideInAPackage("..evaluate..")
            .should().dependOnClassesThat().resideInAPackage("..config..")
            .because("判定は解決済みの設定（domain.gate）だけを入力とする。"
                    + "取得元がファイルか API かで判定ロジックが変わらないようにする");

    @ArchTest
    static final ArchRule アダプタは設定の解決を知らない = noClasses()
            .that().resideInAPackage("..adapter..")
            .should().dependOnClassesThat().resideInAPackage("..config..")
            .because("アダプタは ParseContext で渡された情報だけを使う")
            .allowEmptyShould(true);
}
