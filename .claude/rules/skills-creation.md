# スキル作成ルール

参照元: https://code.claude.com/docs/ja/skills

## 配置場所

| 場所 | パス | 適用範囲 |
| :-- | :-- | :-- |
| Enterprise | 管理設定 | 組織内の全ユーザー |
| Personal | `~/.claude/skills/<skill-name>/SKILL.md` | すべてのプロジェクト |
| Project | `.claude/skills/<skill-name>/SKILL.md` | このプロジェクトのみ |
| Plugin | `<plugin>/skills/<skill-name>/SKILL.md` | プラグイン有効時 |

このプロジェクトで作成するスキルは原則 `.claude/skills/<skill-name>/SKILL.md` に配置し、バージョン管理する。

## ファイル構成

各スキルは `SKILL.md` をエントリポイントとするディレクトリ。

```
my-skill/
├── SKILL.md           # 必須。本体
├── reference.md        # 任意。詳細リファレンス
├── scripts/             # 任意。実行用スクリプト
```

- `SKILL.md` は 500 行以下に保ち、詳細は別ファイルに分離する。
- 本体は簡潔に。呼び出されるとターン全体でコンテキストに残り続けるため、冗長な説明を避ける。

## frontmatter の主要フィールド

すべて任意だが `description` は必須相当（Claude が自動呼び出しを判断する材料になる）。

| フィールド | 用途 |
| :-- | :-- |
| `name` | 表示名（省略時はディレクトリ名） |
| `description` | スキルの内容・使用タイミング。最重要 |
| `disable-model-invocation` | `true` にすると `/name` 手動呼び出し専用になり、Claude が自動で呼ばない |
| `user-invocable` | `false` にすると `/` メニューから隠れる（Claude のみ呼び出し可） |
| `allowed-tools` | スキル実行中に確認なしで使えるツール |
| `context: fork` | サブエージェントとして分離実行 |
| `agent` | `context: fork` 時に使うサブエージェント種別 |
| `paths` | 対象ファイルパターンに一致する時のみ自動起動 |

## 命名・呼び出し名のルール

- コマンド名（`/xxx`）はディレクトリ名から決まる。`frontmatter.name` は一覧表示用ラベルに過ぎない。
- 副作用のある操作（デプロイ、コミット、外部送信など）は `disable-model-invocation: true` を付け、ユーザーの明示呼び出しのみに限定する。

## 作成・改修時の注意

- 既存スキルと名前が重複しないか `.claude/skills/` を確認する。
- 大きな変更は段階的にコミットする（CLAUDE.md の常時ルール参照）。
- 変更後は `.claude/rules/logging.md` に従ってログを残す。
