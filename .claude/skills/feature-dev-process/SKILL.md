---
name: feature-dev-process
description: 方式検討→設計→実装→テスト（C1網羅）の工程で機能開発を進め、各工程をサブエージェントに委譲し工程別レビューを行う。ユーザーが「方式検討して」「設計から実装まで進めて」など機能開発プロセス全体の推進を依頼したときに使う。
disable-model-invocation: true
---

# feature-dev-process

機能開発を「方式検討 → 設計 → 実装 → テスト（C1網羅）」の工程で進め、各工程の完了時に
レビューを行ってから次工程に進めるスキル。各工程の作業は専用サブエージェントに委譲する。

ドキュメントの配置は `.claude/rules/docs-structure.md` に従う（`docs/<feature-name>/` 配下）。

## 進め方

1. **対象機能と `<feature-name>` の確定**
   - ユーザー依頼から機能名を決め、英語ケバブケースの `<feature-name>` を決定する（例: `spring-bash-service`）。
   - `docs/<feature-name>/` と `docs/<feature-name>/reviews/` を用意する。

2. **方式検討（method-investigator）**
   - `method-investigator` サブエージェントに委譲し、`docs/<feature-name>/01_method-study.md` を作成させる。
   - 完了後、`phase-reviewer` サブエージェントに `01_method-study.md` のレビューを依頼し、
     `docs/<feature-name>/reviews/01_method-study-review.md` に記録させる。
   - レビュー結論が「要修正」の場合は method-investigator に差戻し、再レビューする。「承認」まで次工程に進めない。

3. **設計（designer）**
   - `designer` サブエージェントに、承認済みの `01_method-study.md` を入力として委譲し、
     `docs/<feature-name>/02_design.md` を作成させる。
   - `phase-reviewer` で `02_design.md` をレビューし、`reviews/02_design-review.md` に記録。承認まで次工程に進めない。

4. **テスト計画（test-engineer、設計の直後に作成）**
   - `test-engineer` サブエージェントに、承認済み `02_design.md` を入力として委譲し、
     C1（分岐網羅）観点のテストケース一覧を `docs/<feature-name>/03_test-plan.md` に作成させる。
   - `phase-reviewer` でレビューし、`reviews/03_test-plan-review.md` に記録。承認まで次工程に進めない。
   - テストコード自体は実装工程の後に test-engineer へ再委譲して作成する。

5. **実装（implementer）**
   - `implementer` サブエージェントに、承認済みの `02_design.md` と `03_test-plan.md` を入力として委譲し、
     実装コードを作成させる。
   - 完了後、`test-engineer` サブエージェントに `03_test-plan.md` のケースを満たすテストコードを実装させ、
     実行して全てパスすることを確認させる。
   - `phase-reviewer` で実装差分（コード）をレビューし、`reviews/04_implementation-review.md` に記録。
   - 「要修正」であれば implementer に差し戻す。

6. **完了報告**
   - 各工程のドキュメント・レビュー結論・テスト実行結果（パス件数等）を要約してユーザーに報告する。
   - 各工程の進捗は `docs/<feature-name>/` の各ファイルの有無とレビュー結論で判断する。専用のタスクファイルは作らない。

## 注意

- 各サブエージェントへの委譲は Agent ツールで行い、対象の `<feature-name>` ・読むべきドキュメントのパス・
  作成すべき出力パスを明示してプロンプトに含めること。
- 工程をスキップしたり、レビュー未承認のまま次工程に進めてはいけない。
- ドキュメントの新規作成・大幅改訂はセクション単位で段階的にコミットする（CLAUDE.md 参照）。
