# サブエージェント作成ルール

参照元: https://code.claude.com/docs/ja/sub-agents

## 配置場所

| 場所 | スコープ | 優先度 |
| :-- | :-- | :-- |
| 管理設定 | 組織全体 | 最高 |
| `--agents` CLI フラグ | 現在のセッションのみ | 2 |
| `.claude/agents/` | 現在のプロジェクト | 3 |
| `~/.claude/agents/` | すべてのプロジェクト | 4 |
| プラグインの `agents/` | プラグイン有効時 | 最低 |

このプロジェクトで作成するサブエージェントは原則 `.claude/agents/` に配置し、バージョン管理する。

## ファイル形式

YAML frontmatter + Markdown 本体（システムプロンプト）の `.md` ファイル。

```markdown
---
name: code-reviewer
description: コード品質とベストプラクティスをレビューする
tools: Read, Glob, Grep
model: sonnet
---

あなたはコードレビュアーです。呼び出されたら...
```

## frontmatter の主要フィールド

`name` と `description` のみ必須。

| フィールド | 説明 |
| :-- | :-- |
| `name` | 一意の識別子（小文字+ハイフン） |
| `description` | Claude がいつ委譲するかの判断材料。明確に書く |
| `tools` | 許可するツール一覧。省略時は全て継承 |
| `disallowedTools` | 拒否するツール一覧 |
| `model` | `sonnet` / `opus` / `haiku` / `fable` / `inherit`（既定） |
| `permissionMode` | `default` / `acceptEdits` / `bypassPermissions` など |
| `skills` | 起動時にプリロードするスキル |
| `isolation: worktree` | 専用の git worktree で分離実行 |
| `color` | UI 表示色 |

## 命名ルール

- `name` はツリー全体で一意にする。重複すると警告なしに一方が無視される。
- サブディレクトリ（`agents/review/` 等）に整理してもよいが、識別は `name` のみで行われる。

## 作成・改修時の注意

- 既存エージェントと `name` が重複しないか `.claude/agents/` を確認する。
- 不要なツールは `tools` で絞り込み、最小権限にする。
- 変更後は `.claude/rules/logging.md` に従ってログを残す。
