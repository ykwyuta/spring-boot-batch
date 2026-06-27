# スキル・サブエージェントの廃止ルール

## 基本方針

スキルやサブエージェントを廃止する場合、ファイルを削除してはならない。`.claude/deprecated/` 以下に移動して保管する。

## 手順

1. 廃止対象のディレクトリ／ファイルをそのまま `.claude/deprecated/` 配下に移動する。
   - スキルの例: `.claude/skills/old-skill/` → `.claude/deprecated/skills/old-skill/`
   - エージェントの例: `.claude/agents/old-agent.md` → `.claude/deprecated/agents/old-agent.md`
2. 移動後、元の場所から参照されなくなったことを確認する（CLAUDE.md やルールファイルからの参照を更新）。
3. `.claude/rules/logging.md` に従い、`.claude/logs/skills-YYYYMMDD-HHMMSS.log` に「廃止」として記録する。

## 復元

廃止したスキル・サブエージェントを再度有効化する場合は、`.claude/deprecated/` から元の場所に戻し、同様にログを記録する。
