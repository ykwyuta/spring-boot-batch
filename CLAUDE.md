# CLAUDE.md

このファイルはこのリポジトリで作業するすべての Claude Code セッションに適用される基本ルールです。

## 常に適用するルール

- すべての作業（コミット前のセルフチェックを含む）はこのプロジェクトのコーディング規約・ビルド手順に従う。
- ドキュメント（このファイルや `.claude/rules/` 以下のファイルなど）を新規作成・大幅改訂する際は、内容を段階的（セクション単位など）に書き出し、都度コミットする。一度に大きな差分を作ってタイムアウトさせない。
- スキル（`.claude/skills/`）やサブエージェント（`.claude/agents/`）を新規作成・改修・削除した場合は、`.claude/logs/skills-YYYYMMDD-HHMMSS.log` に変更内容を記録する。
- 廃止したスキル・サブエージェントは削除せず、`.claude/deprecated/` 以下に移動して保管する。
- ユーザーからの指摘・依頼を受けて実施した作業内容は `logs/claude-YYYYMMDD-HHMMSS.log` に記録する。
- ルールやドキュメントは日本語で記述する。

## 必要な時だけ参照するルール一覧（`.claude/rules/`）

| ファイル | 内容 |
| :-- | :-- |
| [skills-creation.md](.claude/rules/skills-creation.md) | スキル（SKILL.md）の作成・配置・frontmatter のルール |
| [subagents-creation.md](.claude/rules/subagents-creation.md) | サブエージェント（.claude/agents/）の作成・frontmatter のルール |
| [logging.md](.claude/rules/logging.md) | スキル/エージェントの変更履歴ログ、作業履歴ログの記録ルール |
| [deprecation.md](.claude/rules/deprecation.md) | スキル・エージェントの廃止（移動）手順 |
