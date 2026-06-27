# テスト計画: 起動済み Spring Boot プロセスの Service を bash から実行する

本書は `02_design.md`（承認済み、コミット cc5b902）の「4.2 異常系の分岐（条件分岐の洗い出し）」
に記載された分岐表をもとに、C1（分岐網羅: すべての分岐の真偽を最低1回ずつ実行する）の観点で
テストケースを設計する。

設計書の方針に従い、テストレイヤーを以下のように分割する。

| レイヤー | 対象クラス | 検証手段 |
| :-- | :-- | :-- |
| Interceptor単体テスト | `LocalhostOnlyInterceptor` | `MockHttpServletRequest` / `MockHttpServletResponse` を用いた単体テスト |
| Controller単体テスト | `TaskExecutionController` | `@WebMvcTest` + `MockMvc`（`TaskExecutionService` はモック化） |
| Service単体テスト | `TaskExecutionService` | 通常の単体テスト（Spring MVCコンテキスト不要） |
| 例外ハンドラ単体テスト | `GlobalExceptionHandler` | `@WebMvcTest` 経由、または直接メソッド呼び出しによる単体テスト |

設計書「4.2」の分岐番号（#0a, #0b, #1〜#11）と対応付けて記載する。

## 1. `LocalhostOnlyInterceptor` のテストケース（分岐 #0a, #0b）

| ケースID | 対象分岐 | 条件式・分岐先 | 入力条件 | 期待結果 |
| :-- | :-- | :-- | :-- | :-- |
| IT-01 | #0a（true分岐） | `request.getRemoteAddr()` が `127.0.0.1` の場合、`preHandle` は処理続行のため `true` を返す | `MockHttpServletRequest` の `remoteAddr` に `127.0.0.1` を設定し、対象パスを `/internal/tasks/execute` として `preHandle` を呼び出す | `preHandle` の戻り値が `true`。レスポンスへの書き込みは行われない（`response.getStatus()` が初期値のまま、もしくは未変更） |
| IT-02 | #0a（true分岐・IPv6） | `request.getRemoteAddr()` が `::1`（IPv6ループバック）の場合も処理続行 | `remoteAddr` に `::1` を設定して `preHandle` を呼び出す | `preHandle` の戻り値が `true` |
| IT-03 | #0b（false分岐） | `request.getRemoteAddr()` がローカルホスト以外の場合、403を書き込み `false` を返す | `remoteAddr` に `192.168.1.10` 等の非ローカルホストIPを設定して `preHandle` を呼び出す | `preHandle` の戻り値が `false`。レスポンスステータスが `403`。レスポンスボディが「4.3」の共通エラーフォーマット（`status="ERROR"`, `errorCode="FORBIDDEN"`）でJSONとして書き込まれている |

## 2. `TaskExecutionController` のテストケース（分岐 #1〜#4、#10〜#11）

`@WebMvcTest(TaskExecutionController.class)` を用い、`TaskExecutionService` をモック化する。
（`LocalhostOnlyInterceptor` の対象外、もしくはモック登録によりバイパスする想定。アクセス制御は
セクション1で別途検証済みのため、本セクションではController本来のロジックに焦点を当てる。）

| ケースID | 対象分岐 | 条件式・分岐先 | 入力条件 | 期待結果 |
| :-- | :-- | :-- | :-- | :-- |
| CT-01 | #1（パース不能） | リクエストボディがJSONとして不正な形式の場合、`HttpMessageNotReadableException` を `GlobalExceptionHandler` が捕捉する | `POST /internal/tasks/execute` に `Content-Type: application/json` で本文 `{invalid json` を送信 | HTTPステータス `400 Bad Request`。レスポンスボディが共通エラーフォーマット（`errorCode="BAD_REQUEST"`） |
| CT-02 | #2（taskName null/空白） | `taskName` が `null` の場合、`MethodArgumentNotValidException` を捕捉する | 本文 `{}`（`taskName` フィールドなし）を送信 | HTTPステータス `400 Bad Request`。`errorCode="VALIDATION_ERROR"` |
| CT-03 | #2（taskName 空白のみ） | `taskName` が空白文字のみの場合も同様にバリデーションエラー | 本文 `{"taskName": "   "}` を送信 | HTTPステータス `400 Bad Request`。`errorCode="VALIDATION_ERROR"` |
| CT-04 | #3（101文字以上） | `taskName` が101文字以上の場合、`MethodArgumentNotValidException` を捕捉する | 本文 `{"taskName": "<101文字の文字列>"}` を送信 | HTTPステータス `400 Bad Request`。`errorCode="VALIDATION_ERROR"` |
| CT-05 | #4（妥当な値・境界値:1文字） | `taskName` が1文字（下限）の場合、バリデーションを通過しService呼び出しに進む | 本文 `{"taskName": "a"}` を送信。モックの `TaskExecutionService.execute` が正常結果を返すよう設定 | HTTPステータス `200 OK`。`TaskExecutionService.execute` が呼び出される |
| CT-06 | #4（妥当な値・境界値:100文字） | `taskName` が100文字（上限）の場合もバリデーションを通過する | 本文 `{"taskName": "<100文字の文字列>"}` を送信 | HTTPステータス `200 OK` |
| CT-07 | #10（parameters省略） | リクエストに `parameters` が無い場合、空の `Map` として扱われ処理続行する | 本文 `{"taskName": "sample-task"}`（`parameters` キーなし）を送信 | HTTPステータス `200 OK`。`TaskExecutionService.execute` が `parameters` として空の `Map`（`Collections.emptyMap()` 等）で呼び出される |
| CT-08 | #11（parameters指定あり） | `parameters` が指定されている場合、その値をそのまま `Service` に渡す | 本文 `{"taskName": "sample-task", "parameters": {"key1": "value1"}}` を送信 | HTTPステータス `200 OK`。`TaskExecutionService.execute` が `parameters={"key1":"value1"}` で呼び出される |
| CT-09 | 正常系レスポンス組み立て | Service呼び出し成功時、Controllerが `TaskExecutionResult` を `TaskExecutionResponse` に変換する | モックの `TaskExecutionService.execute` が `TaskExecutionResult("task executed successfully")` を返すよう設定し、`{"taskName": "sample-task"}` を送信 | HTTPステータス `200 OK`。レスポンスボディの `status="SUCCESS"`, `taskName="sample-task"`, `message="task executed successfully"`, `executedAt` がISO-8601形式で含まれる |

## 3. `TaskExecutionService` のテストケース（分岐 #5〜#9）

`TaskExecutionService` を直接インスタンス化し、Spring MVCコンテキストを起動せずに単体テストする。

| ケースID | 対象分岐 | 条件式・分岐先 | 入力条件 | 期待結果 |
| :-- | :-- | :-- | :-- | :-- |
| SV-01 | #5（true分岐） | `taskName` に対応する処理が定義されている場合、処理を実行し `TaskExecutionResult` を返す | 既知のタスク名（例: `sample-task`）と任意の `parameters`（空Map）を渡して `execute` を呼び出す | 例外をスローせず `TaskExecutionResult` が返る。`message` が想定通りの値を持つ |
| SV-02 | #6（false分岐） | `taskName` に対応する処理が未定義の場合、`TaskExecutionException`（`errorCode=TASK_NOT_FOUND`）をスローする | 未知のタスク名（例: `unknown-task`）を渡して `execute` を呼び出す | `TaskExecutionException` がスローされる。`getErrorCode()` が `TASK_NOT_FOUND`。メッセージに `unknown-task` を含む |
| SV-03 | #7（正常終了） | 処理実行中に例外が発生しない場合、`TaskExecutionResult` を返す | 正常に完了するタスク名・パラメータを渡して `execute` を呼び出す | 例外なく `TaskExecutionResult` が返る |
| SV-04 | #8（業務的な失敗） | 処理実行中に業務的な前提条件不成立等が発生した場合、`TaskExecutionException`（`errorCode=TASK_EXECUTION_FAILED`）をスローする | 業務的に失敗する条件（前提条件不成立）を再現するタスク名・パラメータを渡して `execute` を呼び出す | `TaskExecutionException` がスローされる。`getErrorCode()` が `TASK_EXECUTION_FAILED` |
| SV-05 | #9（想定外の例外） | 処理実行中に想定外の実行時例外（`RuntimeException`/`NullPointerException` 等）が発生した場合、`TaskExecutionException` でラップせずそのまま伝播する | 想定外の実行時例外が発生する条件を再現するタスク名・パラメータを渡して `execute` を呼び出す | スローされる例外が `TaskExecutionException` ではなく、元の `RuntimeException`（または `NullPointerException` 等）である |

注記: SV-04・SV-05 を実機のタスク実装で再現する手段が実装フェーズで判明していない場合、
実装フェーズ（テストコード実装）でテスト用のダミータスク（テスト専用の `taskName` 分岐）を
`TaskExecutionService` 内、またはテスト用のサブクラス/モックで用意することを検討する。
これは設計書「6.3」の方針（業務的な失敗と想定外例外は別経路で扱う）の範囲内である。

## 4. `GlobalExceptionHandler` のテストケース（分岐 #1, #6, #8, #9 のハンドラ側、および #0b との関係）

セクション2・3で各例外発生条件を経由するE2E相当のテスト（`@WebMvcTest` 等）によって
`GlobalExceptionHandler` のハンドラメソッドも合わせて検証される想定だが、ハンドラ自体の
レスポンス組み立てロジック（ステータス・ボディ形式の対応）をより直接的に検証するケースを
以下に挙げる。

| ケースID | 対象分岐 | 条件式・分岐先 | 入力条件 | 期待結果 |
| :-- | :-- | :-- | :-- | :-- |
| EH-01 | `handleValidationException` | `MethodArgumentNotValidException` を捕捉した場合のレスポンス組み立て | CT-02／CT-03／CT-04のいずれかの条件で発生した `MethodArgumentNotValidException` をハンドラに渡す（または `@WebMvcTest` 経由） | HTTPステータス `400`。ボディが `status="ERROR"`, `errorCode="VALIDATION_ERROR"` を含む |
| EH-02 | `handleTaskExecutionException`（`TASK_NOT_FOUND`分岐） | `errorCode=TASK_NOT_FOUND` の場合、`HttpStatus.NOT_FOUND`（404）を組み立てる | `errorCode=TASK_NOT_FOUND` の `TaskExecutionException` をハンドラに渡す | HTTPステータス `404`。ボディの `errorCode="TASK_NOT_FOUND"` |
| EH-03 | `handleTaskExecutionException`（`TASK_EXECUTION_FAILED`分岐） | `errorCode=TASK_EXECUTION_FAILED` の場合、`HttpStatus.UNPROCESSABLE_ENTITY`（422）を組み立てる | `errorCode=TASK_EXECUTION_FAILED` の `TaskExecutionException` をハンドラに渡す | HTTPステータス `422`。ボディの `errorCode="TASK_EXECUTION_FAILED"` |
| EH-04 | `handleUnexpectedException` | 想定外の `Exception`（`TaskExecutionException` 以外）を捕捉した場合 | 任意の `RuntimeException`（例: `new RuntimeException("unexpected")`）をハンドラに渡す | HTTPステータス `500`。ボディの `errorCode="INTERNAL_ERROR"`。メッセージにスタックトレースや内部実装詳細を含まない（設計書「6.1」の方針） |
| EH-05 | JSONパース不能時のハンドラ（#1再掲） | `HttpMessageNotReadableException` を捕捉した場合のレスポンス組み立て | CT-01の条件で発生した `HttpMessageNotReadableException` をハンドラに渡す（または `@WebMvcTest` 経由） | HTTPステータス `400`。ボディの `errorCode="BAD_REQUEST"` |

## 5. 結合シナリオ（参考・任意）

`@SpringBootTest` + `TestRestTemplate`（または `MockMvc` フルコンテキスト）を用いた結合テストは
本テスト計画のC1網羅の必須要件には含めないが、実装フェーズで時間が許す場合、設計書「8. bash
からの呼び出し例」に記載された代表シナリオ（正常系パラメータなし／正常系パラメータあり／
未知タスク名404／taskName未指定400）をE2E的に1〜2件確認しておくと、レイヤー間の結合不備の
早期検知に有効である。なお `LocalhostOnlyInterceptor` を経由する結合テストではテスト実行環境の
`remoteAddr` が環境依存になりうるため、必須ケースとしては扱わない（セクション1の単体テストで
C1網羅は充足している）。

## 6. ケース数まとめ

| セクション | ケース数 |
| :-- | :-- |
| 1. `LocalhostOnlyInterceptor` | 3（IT-01〜IT-03） |
| 2. `TaskExecutionController` | 9（CT-01〜CT-09） |
| 3. `TaskExecutionService` | 5（SV-01〜SV-05） |
| 4. `GlobalExceptionHandler` | 5（EH-01〜EH-05） |
| 合計 | 22 |

設計書「4.2」の分岐表に挙げられた全分岐（#0a, #0b, #1〜#11）について、真偽（または各分岐先）
を最低1回ずつ実行するケースが上記表に含まれていることを確認済み（IT-01/IT-02が#0a、IT-03が
#0b、CT-01が#1、CT-02/CT-03が#2、CT-04が#3、CT-05/CT-06が#4、SV-01が#5の真分岐、SV-02が#5の
偽分岐相当（#6）、SV-03が#7、SV-04が#8、SV-05が#9、CT-07が#10、CT-08が#11に対応）。
