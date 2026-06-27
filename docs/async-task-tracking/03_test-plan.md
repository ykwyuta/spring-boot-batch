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

## 1. `AsyncTaskExecutionController` のテストケース（非同期実行リクエストAPI、分岐 A1〜A6, A12〜A15）

`@WebMvcTest(AsyncTaskExecutionController.class)` を用い、`AsyncTaskExecutionService` をモック化する。
`LocalhostOnlyInterceptor`（A0a/A0b）は既存同期APIのテスト（`LocalhostOnlyInterceptorTest`、
`docs/service-bash-invocation/03_test-plan.md` IT-01〜IT-03）で既にC1網羅済みであり、`/internal/**`
への適用方式自体（`InternalApiWebConfig`）に変更がないため、本セクションでは新規の単体テストを
設けない（結合シナリオ「7」で疎通のみ確認する）。

| ケースID | 対象分岐 | 条件式・分岐先 | 入力条件 | 期待結果 |
| :-- | :-- | :-- | :-- | :-- |
| AC-01 | A2（taskName null/空白） | `taskName`がnullの場合、`MethodArgumentNotValidException`を捕捉する | 本文 `{}`（`taskName`フィールドなし）を `POST /internal/tasks/execute-async` に送信 | HTTPステータス `400 Bad Request`。`errorCode="VALIDATION_ERROR"`。`AsyncTaskExecutionService`は呼び出されない |
| AC-02 | A2（taskName 空白のみ） | `taskName`が空白文字のみの場合も同様にバリデーションエラー | 本文 `{"taskName": "   ", "inputFilePaths": []}` を送信 | HTTPステータス `400 Bad Request`。`errorCode="VALIDATION_ERROR"` |
| AC-03 | A3（101文字以上） | `taskName`が101文字以上の場合、バリデーションエラー | 本文 `{"taskName": "<101文字>", "inputFilePaths": []}` を送信 | HTTPステータス `400 Bad Request`。`errorCode="VALIDATION_ERROR"` |
| AC-04 | A4（妥当な値・境界値:1文字） | `taskName`が1文字（下限）の場合、バリデーションを通過しService呼び出しに進む | 本文 `{"taskName": "a", "inputFilePaths": []}` を送信。モックの`acceptAsyncExecution`が`UUID`を返すよう設定 | HTTPステータス `202 Accepted`。`acceptAsyncExecution`が呼び出される |
| AC-05 | A4（妥当な値・境界値:100文字） | `taskName`が100文字（上限）の場合もバリデーションを通過する | 本文 `{"taskName": "<100文字>", "inputFilePaths": []}` を送信 | HTTPステータス `202 Accepted` |
| AC-06 | A5（inputFilePaths要素が空文字・空白のみ） | `inputFilePaths`の要素に空白のみの文字列を含む場合、バリデーションエラー | 本文 `{"taskName": "sample-task", "inputFilePaths": ["   "]}` を送信 | HTTPステータス `400 Bad Request`。`errorCode="VALIDATION_ERROR"`。Serviceは呼び出されない |
| AC-07 | A6（inputFilePaths要素数101件以上） | `inputFilePaths`が101件以上の場合、バリデーションエラー | 本文の`inputFilePaths`に101件の文字列（例: `"/tmp/f0"`〜`"/tmp/f100"`）を設定して送信 | HTTPステータス `400 Bad Request`。`errorCode="VALIDATION_ERROR"` |
| AC-08 | 正常系レスポンス組み立て（A12と連動） | Service呼び出し成功時、Controllerが`taskId`を`AsyncTaskExecutionAcceptedResponse`に変換する | モックの`acceptAsyncExecution`が固定の`UUID`を返すよう設定し、`{"taskName": "sample-task", "inputFilePaths": []}` を送信 | HTTPステータス `202 Accepted`。レスポンスボディの`taskId`がモックの返却値と一致、`status="PENDING"`、`acceptedAt`がISO-8601形式 |
| AC-09 | A14（parameters省略） | リクエストに`parameters`が無い場合、空の`Map`として扱われ処理続行する | 本文 `{"taskName": "sample-task", "inputFilePaths": []}`（`parameters`キーなし）を送信 | HTTPステータス `202 Accepted`。`acceptAsyncExecution`が`parameters`として空の`Map`で呼び出される |
| AC-10 | A15（parameters指定あり） | `parameters`が指定されている場合、その値をそのままServiceに渡す | 本文 `{"taskName": "sample-task", "parameters": {"key1": "value1"}, "inputFilePaths": []}` を送信 | HTTPステータス `202 Accepted`。`acceptAsyncExecution`が`parameters={"key1":"value1"}`で呼び出される |
| AC-11 | A13（スレッドプール飽和、Controller視点） | `acceptAsyncExecution`が`RejectedExecutionException`をスローした場合、`GlobalExceptionHandler`の専用ハンドラが捕捉する | モックの`acceptAsyncExecution`が`RejectedExecutionException`をスローするよう設定し、`{"taskName": "sample-task", "inputFilePaths": []}` を送信 | HTTPステータス `503 Service Unavailable`。`errorCode="ASYNC_EXECUTOR_BUSY"` |
| AC-12 | A10/A11（入力ファイル不存在、Controller視点） | `acceptAsyncExecution`が`AsyncInputFileNotFoundException`をスローした場合、専用ハンドラが捕捉する | モックの`acceptAsyncExecution`が`AsyncInputFileNotFoundException`をスローするよう設定し、`{"taskName": "sample-task", "inputFilePaths": ["/no/such/file"]}` を送信 | HTTPステータス `404 Not Found`。`errorCode="INPUT_FILE_NOT_FOUND"` |

注記: AC-11/AC-12はController層での例外伝播・ハンドラディスパッチの確認に主眼を置く（モックで
例外を発生させる）。実際にファイル不存在・スレッドプール飽和を引き起こす条件そのものの検証は
セクション2（Service単体テスト）で行う（A10/A11/A13本体の検証）。

## 2. `AsyncTaskExecutionService.acceptAsyncExecution` のテストケース（分岐 A7〜A13）

`AsyncTaskExecutionService`を直接インスタンス化し、Spring MVCコンテキストを起動せずに単体テスト
する。`AsyncTaskExecutionStateStore`は実インスタンス（`new AsyncTaskExecutionStateStore()`）を
用いて状態ストアへの登録・削除を実際に確認する。`@Async`によるスレッドプールへのディスパッチ自体
（実際の別スレッド実行）はA12/A13の検証観点では「ディスパッチ呼び出しが例外をスローするか否か」
のみが重要なため、テスト用に実行を即座に拒否する`Executor`（`RejectedExecutionException`を常に
スローするスタブ）、および即時実行する`Executor`（`Runnable::run`、同期的に実行するスタブ）を
注入してテストする（設計書「4.1」「4.3」のディスパッチ呼び出し自体の例外伝播を検証する。実際の
非同期実行本体の状態遷移はセクション3で別途検証する）。

| ケースID | 対象分岐 | 条件式・分岐先 | 入力条件 | 期待結果 |
| :-- | :-- | :-- | :-- | :-- |
| AS-01 | A7（inputFilePaths省略） | `inputFilePaths`がnull（省略）の場合、空リストとして扱い検証をスキップして処理続行する | `acceptAsyncExecution("sample-task", Map.of(), null)`を呼び出す（DTOバリデーションを経由しない直接呼び出しのため、Service側でのnull安全な扱いを確認） | 例外をスローせず`UUID`を返す。状態ストアに`PENDING`レコードが`inputFilePaths=[]`で登録される |
| AS-02 | A8（inputFilePathsが空配列） | `inputFilePaths`が空リスト（`List.of()`）の場合、検証対象が存在しないため常に検証を通過する | `acceptAsyncExecution("sample-task", Map.of(), List.of())`を呼び出す | 例外をスローせず`UUID`を返す。状態ストアに`PENDING`レコードが`inputFilePaths=[]`で登録される（AS-01と処理結果は同一だが、入力経路（null→空リスト変換 / 空リストそのまま）が異なることを確認する） |
| AS-03 | A9（すべて存在する通常ファイル） | `inputFilePaths`の各要素がすべて存在する通常ファイルの場合、検証を通過しPENDING登録へ進む | テスト用に一時ファイルを2つ作成（`@TempDir`）し、両方のパスを`inputFilePaths`に指定して`acceptAsyncExecution`を呼び出す | 例外をスローせず`UUID`を返す。状態ストアに`PENDING`レコードが当該`inputFilePaths`で登録される |
| AS-04 | A10（いずれか1つ以上が存在しない） | `inputFilePaths`のいずれかのパスがファイルシステム上に存在しない場合 | 存在しないパス（例: `/no/such/file-<random>`）を含む`inputFilePaths`を指定して`acceptAsyncExecution`を呼び出す | `AsyncInputFileNotFoundException`がスローされる。状態ストアにレコードは残らない（PENDING登録自体が行われない） |
| AS-05 | A11（いずれか1つ以上がディレクトリ） | `inputFilePaths`のいずれかのパスがディレクトリ（通常ファイルでない）の場合 | `@TempDir`が提供するディレクトリそのもののパスを`inputFilePaths`に指定して`acceptAsyncExecution`を呼び出す | `AsyncInputFileNotFoundException`がスローされる。状態ストアにレコードは残らない |
| AS-06 | A12（ディスパッチ成功） | スレッドプール・キューに空きがある場合、ディスパッチ成功しPENDINGレコードを保持したまま`taskId`を返す | ディスパッチを即時実行する（または何もしない）スタブ`Executor`を注入し、存在検証を通過する条件で`acceptAsyncExecution`を呼び出す | 例外をスローせず`UUID`を返す。状態ストアに当該`taskId`の`PENDING`（または以後の遷移後の状態）レコードが存在する |
| AS-07 | A13（ディスパッチ飽和） | スレッドプール・キューが飽和している場合、`RejectedExecutionException`が発生し登録済みPENDINGレコードを削除してから例外を再スローする | `Executor.execute(...)`が常に`RejectedExecutionException`をスローするスタブを注入し、存在検証を通過する条件で`acceptAsyncExecution`を呼び出す | `RejectedExecutionException`がスローされる。スロー後、当該`taskId`で状態ストアを検索すると`Optional.empty()`（登録したPENDINGレコードが削除されている） |

注記: AS-01/AS-02はいずれも最終的に「検証スキップで処理続行」という同一の処理結果に収束するが、
設計レビュー（`reviews/02_design-review.md`指摘事項2）を踏まえ、`null`を渡した場合の防御的な
デフォルト値生成ロジック（A7）と、空リストが明示された場合にバリデーション・存在検証ループが
0回実行されて素通りする経路（A8）を区別して検証する。

## 3. `AsyncTaskExecutionService.executeAsync`（非同期実行本体）のテストケース（分岐 B1〜B6）

`executeAsync(taskId, taskName, parameters, inputFilePaths)`を、`@Async`によるスレッドプール経由
ではなく**同期的に直接呼び出す**ことで、状態ストアへの反映を検証する（設計書「4.4」のメソッド内
処理ロジック自体はディスパッチ方式と独立して検証可能なため）。事前に`AsyncTaskExecutionStateStore`
に対象`taskId`の`PENDING`レコードを`save`しておき、`executeAsync`呼び出し後の状態ストアの内容を
検証する。既存`TaskExecutionService`のデモ用タスク名（`sample-task`/`task-business-failure`/
`task-unexpected-error`）をそのまま再利用し、各分岐を再現する。

| ケースID | 対象分岐 | 条件式・分岐先 | 入力条件 | 期待結果 |
| :-- | :-- | :-- | :-- | :-- |
| AE-01 | T2（RUNNINGへの遷移） | `executeAsync`呼び出し直後、`TaskExecutionService.execute`呼び出し前に状態ストアが`RUNNING`に更新される | `taskName="sample-task"`で`executeAsync`を呼び出す直前・直後の状態ストアの値をタイミングをずらして確認する（または`TaskExecutionService`をモック化し、`execute`呼び出し時点でのストアの状態を検証する） | `TaskExecutionService.execute`呼び出し時点で状態ストアの`status`が`RUNNING`になっている |
| AE-02 | B1（正常終了） | `TaskExecutionService.execute(...)`が正常終了し`TaskExecutionResult`を返す場合、`SUCCEEDED`に遷移する | `taskName="sample-task"`、`parameters={}`、`inputFilePaths=[]`で`executeAsync`を呼び出す | 状態ストアの当該`taskId`のレコードが`status=SUCCEEDED`、`message="task executed successfully"`に更新される |
| AE-03 | B2（TASK_NOT_FOUND） | `TaskExecutionService.execute(...)`が`TaskExecutionException`（`errorCode=TASK_NOT_FOUND`）をスローする場合、`FAILED`に遷移する | `taskName="unknown-task"`（既存`TaskExecutionService`が未知のタスク名として扱う値）で`executeAsync`を呼び出す | 状態ストアの当該`taskId`のレコードが`status=FAILED`、`errorCode="TASK_NOT_FOUND"`に更新される。`AsyncTaskRecord.errorCode`は内部状態としてのみ保持され、対応する`AsyncTaskStatusResponse`には`errorCode`フィールド自体が存在しないこと（設計書「8.2」「5.5」の方針）も合わせて確認する |
| AE-04 | B3（TASK_EXECUTION_FAILED） | `TaskExecutionService.execute(...)`が`TaskExecutionException`（`errorCode=TASK_EXECUTION_FAILED`）をスローする場合、`FAILED`に遷移する | `taskName=TaskExecutionService.TASK_NAME_BUSINESS_FAILURE`（`task-business-failure`）で`executeAsync`を呼び出す | 状態ストアの当該`taskId`のレコードが`status=FAILED`、`errorCode="TASK_EXECUTION_FAILED"`、`message`に前提条件不成立の旨が設定される |
| AE-05 | B4（想定外の実行時例外） | `TaskExecutionService.execute(...)`が想定外の実行時例外（`NullPointerException`等）をスローする場合、ログ出力後`FAILED`に遷移する | `taskName=TaskExecutionService.TASK_NAME_UNEXPECTED_ERROR`（`task-unexpected-error`）で`executeAsync`を呼び出す | 状態ストアの当該`taskId`のレコードが`status=FAILED`、`errorCode="INTERNAL_ERROR"`に更新される。`executeAsync`の呼び出し自体は例外をスローせず正常にreturnする（呼び出し元に伝播しないことを確認） |
| AE-06 | B5（出力ファイルパスの決定・正常系） | `taskName=sample-task`の場合、`outputFilePaths`に1件のパスが設定される | AE-02と同条件で`executeAsync`を呼び出す | 状態ストアの当該`taskId`のレコードの`outputFilePaths`が1件の文字列（`/tmp/async-tasks/{taskId}/result.txt`形式）を含むリストになる |
| AE-07 | B6（出力ファイルパスの決定・異常系） | B1以外（B2〜B4、処理が失敗したケース）の場合、`outputFilePaths`は空リストのまま | AE-03〜AE-05のいずれかと同条件で`executeAsync`を呼び出す | 状態ストアの当該`taskId`のレコードの`outputFilePaths`が空リストのままである |
| AE-08（重要） | B3との対比: 同期API・非同期APIの挙動差異 | 既存同期API（`POST /internal/tasks/execute`）は業務的な失敗時に`422 TASK_EXECUTION_FAILED`を返すが、非同期APIは同じ業務的失敗（`task-business-failure`）が発生しても**HTTPレスポンスとしては`200 OK`＋`status=FAILED`を返し、422にはならない**（設計書「8.3」で明示された重要な差異） | （結合シナリオとして）同一の`taskName=task-business-failure`を(a)同期API`POST /internal/tasks/execute`、(b)非同期API`POST /internal/tasks/execute-async`に対して順に実行し、(b)は完了まで`GET /internal/tasks/{taskId}`をポーリングする | (a)はHTTPステータス`422`、`errorCode="TASK_EXECUTION_FAILED"`を返す。(b)は`POST`時点で`202`、ポーリング完了後の`GET`で`200 OK`、レスポンスボディが`status="FAILED"`、`message`に前提条件不成立の旨を含む（**422は一度も返らない**）。このケースは本テスト計画でC1網羅とは独立に設計レビュー指摘事項（02_design-review.md）への対応として必須で設けるケースである |

注記: AE-08は単体テストの粒度を超えるため、セクション7「結合シナリオ」に実体を置き、本セクションで
番号を予約する形にする（対象分岐B3・設計書8.3節の両方に対応付ける）。AE-01は厳密な競合条件の検証が
難しいため、実装フェーズでは`TaskExecutionService`をモック化し`execute`呼び出し時にコールバックで
状態ストアの値を確認する手法、または`Mockito`の`doAnswer`で検証する手法を用いることを想定する。
