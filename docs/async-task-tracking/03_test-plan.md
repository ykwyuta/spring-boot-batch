# テスト計画: タスク実行の非同期化と処理状況のトラッキング

本書は `02_design.md`（承認済み、コミット 4cceba5）の「9. 異常系の分岐一覧（C1網羅のための整理）」
に記載された分岐表（A0a〜A15、B1〜B6、C0a〜C8、D1〜D3）をもとに、C1（分岐網羅: すべての分岐の
真偽・分岐先を最低1回ずつ実行する）の観点でテストケースを設計する。書式は既存の同期API機能の
テスト計画（`docs/service-bash-invocation/03_test-plan.md`）に準拠する。

設計書「2. パッケージ構成・クラス構成」のクラス構成に基づき、テストレイヤーを以下のように分割する。

| レイヤー | 対象クラス | 検証手段 |
| :-- | :-- | :-- |
| Controller単体テスト | `AsyncTaskExecutionController` | `@WebMvcTest` + `MockMvc`（`AsyncTaskExecutionService` はモック化） |
| Service単体テスト（実行受付・入力検証） | `AsyncTaskExecutionService`（`acceptAsyncExecution`） | 通常の単体テスト（Spring MVCコンテキスト不要、`AsyncTaskExecutionStateStore`は実インスタンスまたはモック） |
| Service単体テスト（非同期実行本体） | `AsyncTaskExecutionService`（`executeAsync`） | 通常の単体テスト。`@Async`によるスレッドプール経由ではなく、メソッドを直接同期的に呼び出して状態ストアへの反映を検証する（既存`TaskExecutionService`をそのまま利用し、デモ用タスク名で各分岐を再現） |
| StateStore単体テスト | `AsyncTaskExecutionStateStore` | 通常の単体テスト（`save`/`find`/`update`/`removeIfOlderThan`を直接呼び出す） |
| 例外ハンドラ単体テスト | `GlobalExceptionHandler`（新規追加分） | `@WebMvcTest` 経由、または直接メソッド呼び出しによる単体テスト |
| Scheduler単体テスト | `AsyncTaskRetentionScheduler` | 通常の単体テスト（`AsyncTaskExecutionStateStore`をモック化し、削除対象判定の境界を検証） |
| 結合シナリオ（参考） | 上記複数層 | `@SpringBootTest` + `MockMvc`フルコンテキスト（C1網羅の必須要件ではないが、同期API/非同期APIの挙動差異の確認に用いる） |

設計書「9」の分岐番号（A0a〜A15、B1〜B6、C0a〜C8、D1〜D3）と対応付けて記載する。なお、設計書
「9」末尾に記載の通り、A0b, A1〜A3, A5, A6, A10, A11, A13, B2〜B4, C0b, C2, C4 が異常系、それ以外
（A0a, A4, A7〜A9, A12, A14, A15, B1, B5, B6, C0a, C1, C3, C5〜C8, D1〜D3）が正常系またはニュート
ラルな分岐である。
