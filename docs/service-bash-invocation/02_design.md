# 詳細設計: 起動済み Spring Boot プロセスの Service を bash から実行する

本書は `01_method-study.md` で採用した方式（Spring MVC の `@RestController` で Service メソッドを
公開し、bash から `curl` で呼び出す）に基づく詳細設計である。

## 0. 前提条件（レビュー指摘への対応）

`01_method-study.md` のレビューで指摘された「呼び出し元の前提」「無認可運用のリスク」を明確化する。

### 0.1 呼び出し元の前提

- **bash の実行元は、Spring Boot プロセスと同一ホスト上であることを前提とする。**
  リモートホストからの呼び出しは本設計の対象外とする。
- この前提により、HTTP サーバーのバインドアドレスを `127.0.0.1`（localhost のみ）に限定する
  ことで、ネットワーク経由の外部到達性を遮断する（詳細は「5. アクセス制御方針」参照）。
- 将来的にリモートホストからの呼び出しが必要になった場合は、本設計の前提が崩れるため、
  Spring Security 等による認証・認可の追加実装を別途行う必要がある（本設計のスコープ外）。

### 0.2 暫定運用におけるリスクの明記

- 本設計では認可（authorization）の実装を行わない。アクセス制御はバインドアドレスの限定
  （localhost only）のみに依存する。
- そのため、**同一ホスト上で実行される他のプロセス・ユーザーからは無制限にエンドポイントを
  呼び出せる**状態になる。これは意図的な暫定措置であり、リスクとして以下を認識しておく。
  - 同一ホストに複数ユーザーがログイン可能な環境（共有サーバー等）では、他ユーザーからも
    Service メソッドが実行できてしまう。
  - 将来 Service の処理内容が機密性の高い操作（データ削除、外部送信等）を含むようになった
    場合、現状の設計のままでは不十分である。
  - 本設計は「デモ用途・開発者本人による手元操作」を想定したものであり、本番相当の
    マルチユーザー環境にそのまま適用することは推奨しない。
  - 認可が必要になった場合の拡張方針は「5.3 将来の拡張方針」に記載する。

## 1. 既存コードベースの構成調査結果

| 項目 | 内容 |
| :-- | :-- |
| ビルドツール | Gradle（`build.gradle`） |
| Spring Boot バージョン | 4.0.7 |
| Java バージョン | 25（toolchain指定） |
| ルートパッケージ | `com.example.demo` |
| 既存クラス | `com.example.demo.DemoApplication`（`@SpringBootApplication`）のみ |
| Web フレームワーク | `spring-boot-starter-webmvc`（Spring MVC、`@RestController` 利用可） |
| 設定ファイル | `src/main/resources/application.properties`（現状 `spring.application.name=demo` のみ） |
| テスト構成 | `spring-boot-starter-webmvc-test` 等が `testImplementation` に存在。JUnit 5（`useJUnitPlatform()`） |
| Lombok | `compileOnly` / `annotationProcessor` で導入済み |
| Actuator | 未導入（方式検討の結論通り、本設計でも導入しない） |

既存に Controller/Service クラスが存在しないため、命名規約は Spring Boot の一般的な慣習
（`XxxController`, `XxxService`, `XxxRequest`, `XxxResponse`）に従い、パッケージは機能単位で
`com.example.demo.<feature>` 配下に切り出す方針とする。

## 2. パッケージ構成・クラス構成

本機能（デモとしての「何らかの処理を実行し結果を返す Service」を bash から起動する機能）を
`com.example.demo.task` パッケージに配置する。

```
src/main/java/com/example/demo/
├── DemoApplication.java                      (既存)
└── task/
    ├── TaskExecutionController.java           # 公開HTTPエンドポイント（Web層）
    ├── TaskExecutionService.java               # 業務処理本体（Service層）
    ├── dto/
    │   ├── TaskExecutionRequest.java           # リクエストDTO
    │   └── TaskExecutionResponse.java          # レスポンスDTO
    └── exception/
        ├── TaskExecutionException.java         # 業務処理失敗を表す例外
        └── GlobalExceptionHandler.java         # @ControllerAdviceによる例外ハンドリング
```

### 2.1 各クラスの責務

| クラス | 責務 |
| :-- | :-- |
| `TaskExecutionController` | HTTP リクエストの受付、リクエストDTOのバリデーション結果のハンドリング、`TaskExecutionService` の呼び出し、レスポンスDTOへの変換。**業務ロジックは持たない**。 |
| `TaskExecutionService` | 実行対象の業務処理本体（デモとして「与えられたタスク名・パラメータに基づき何らかの処理を行い、結果文字列と処理時間を返す」処理を想定）。Controller には依存しない。単体テスト容易性を確保するため、HTTP/Spring MVC の型に依存しない純粋な Java の引数・戻り値を持つ。 |
| `TaskExecutionRequest` | リクエストボディ（JSON）に対応する DTO。`taskName`（必須）、`parameters`（任意の `Map<String, String>`）を保持。Bean Validation アノテーション（`@NotBlank` 等）を付与。 |
| `TaskExecutionResponse` | レスポンスボディ（JSON）に対応する DTO。実行結果（`status`, `message`, `executedAt` 等）を保持。 |
| `TaskExecutionException` | Service 内で業務的に処理が失敗したことを表すカスタム例外（実行時例外）。 |
| `GlobalExceptionHandler` | `@RestControllerAdvice` により、`TaskExecutionException`・バリデーション例外・予期しない例外を捕捉し、統一的なエラーレスポンス（後述）に変換する。 |

### 2.2 クラス図（概略）

```
TaskExecutionController
  - taskExecutionService: TaskExecutionService
  + execute(TaskExecutionRequest): ResponseEntity<TaskExecutionResponse>

TaskExecutionService
  + execute(String taskName, Map<String,String> parameters): TaskExecutionResult
    （TaskExecutionResult は Service 内部の戻り値。Controller 側で TaskExecutionResponse に変換）

GlobalExceptionHandler (@RestControllerAdvice)
  + handleValidationException(MethodArgumentNotValidException): ResponseEntity<ErrorResponse>
  + handleTaskExecutionException(TaskExecutionException): ResponseEntity<ErrorResponse>
  + handleUnexpectedException(Exception): ResponseEntity<ErrorResponse>
```

Controller と Service を分離することで、以下を実現する。

- Service 単体でのユニットテストが容易（Spring MVC のコンテキストを起動せずに検証可能）。
- 将来、HTTP 以外の経路（バッチジョブ、別のControllerなど）から同じ Service を再利用できる。

## 3. 公開インターフェース（REST API 仕様）

### 3.1 エンドポイント一覧

| メソッド | パス | 概要 |
| :-- | :-- | :-- |
| `POST` | `/internal/tasks/execute` | 指定したタスク名・パラメータで Service の処理を実行する |

- パスは方式検討ドキュメントの結論に従い、業務 API と区別できるよう `/internal/**` 配下に
  配置する。
- 本設計では1エンドポイントのみを定義する（デモ用途のため）。将来複数のタスクを追加する場合は
  同じ `/internal/tasks/**` 配下にエンドポイントを追加していく想定。

### 3.2 リクエスト仕様

```
POST /internal/tasks/execute
Content-Type: application/json
```

```json
{
  "taskName": "sample-task",
  "parameters": {
    "key1": "value1"
  }
}
```

| フィールド | 型 | 必須 | 説明・制約 |
| :-- | :-- | :-- | :-- |
| `taskName` | string | 必須 | 実行するタスクの識別子。空文字・null・空白のみは不可（`@NotBlank`）。最大長 100 文字（`@Size`）。 |
| `parameters` | object（`Map<String, String>`） | 任意 | タスクに渡す追加パラメータ。省略時は空Mapとして扱う。 |

### 3.3 レスポンス仕様（正常時）

```
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "status": "SUCCESS",
  "taskName": "sample-task",
  "message": "task executed successfully",
  "executedAt": "2026-06-27T12:34:56.789"
}
```

| フィールド | 型 | 説明 |
| :-- | :-- | :-- |
| `status` | string | `"SUCCESS"` 固定（正常応答時） |
| `taskName` | string | 実行されたタスク名（リクエストの値をそのまま返却） |
| `message` | string | Service の処理結果メッセージ |
| `executedAt` | string（ISO-8601） | 実行完了時刻 |

### 3.4 公開メソッドシグネチャ

```java
// TaskExecutionController
@PostMapping("/internal/tasks/execute")
public ResponseEntity<TaskExecutionResponse> execute(
        @Valid @RequestBody TaskExecutionRequest request) {
    ...
}
```

```java
// TaskExecutionService
public TaskExecutionResult execute(String taskName, Map<String, String> parameters) {
    ...
}
```

```java
// TaskExecutionResult（Service内部の戻り値。レコードとして実装）
public record TaskExecutionResult(String message) {}
```

## 4. 処理フロー

### 4.1 正常系シーケンス

```
bash(curl) -> TaskExecutionController.execute()
  1. Spring MVC が application/json のリクエストボディを TaskExecutionRequest にデシリアライズ
  2. @Valid によるBean Validationを実行
       - taskName が null/空白 -> バリデーションエラー（4.2 異常系参照）
       - taskName が101文字以上 -> バリデーションエラー（4.2 異常系参照）
  3. Controller が TaskExecutionService.execute(taskName, parameters) を呼び出す
  4. Service内部処理:
       a. taskName に対応する処理が存在するかを判定
            - 対応する処理が存在する -> 処理を実行し TaskExecutionResult を返す
            - 対応する処理が存在しない -> TaskExecutionException をスロー（4.2 異常系参照）
       b. 処理実行中に予期しない例外が発生
            - 発生しない -> 正常に結果を返す
            - 発生する -> 実行時例外がそのままControllerまで伝播（4.2 異常系参照）
  5. Controller が TaskExecutionResult を TaskExecutionResponse に変換
  6. HTTP 200 OK としてJSONレスポンスを返却
```

### 4.2 異常系の分岐（条件分岐の洗い出し）

後続のテスト計画工程（C1網羅）で利用できるよう、分岐が発生しうる箇所を明示する。

| # | 分岐箇所 | 条件 | 挙動 | HTTPステータス |
| :-- | :-- | :-- | :-- | :-- |
| 1 | リクエストボディのデシリアライズ | JSON として不正な形式（パース不能） | `HttpMessageNotReadableException` を `GlobalExceptionHandler` が捕捉 | 400 Bad Request |
| 2 | `taskName` のバリデーション | `taskName` が null または空白のみ | `MethodArgumentNotValidException` を捕捉 | 400 Bad Request |
| 3 | `taskName` のバリデーション | `taskName` が101文字以上 | `MethodArgumentNotValidException` を捕捉 | 400 Bad Request |
| 4 | `taskName` のバリデーション | `taskName` が1〜100文字の妥当な値 | バリデーション通過、Service呼び出しへ進む | - |
| 5 | Service内のタスク名解決 | `taskName` に対応する処理が定義されている | 処理を実行し結果を返す | 200 OK |
| 6 | Service内のタスク名解決 | `taskName` に対応する処理が未定義 | `TaskExecutionException`（"unknown task" 等のメッセージ）をスロー | 404 Not Found |
| 7 | Service内の処理実行 | 処理が正常終了 | `TaskExecutionResult` を返す | 200 OK |
| 8 | Service内の処理実行 | 処理中に業務的な失敗（前提条件不成立等）が発生 | `TaskExecutionException` をスロー | 422 Unprocessable Entity |
| 9 | Service内の処理実行 | 想定外の実行時例外（`RuntimeException` 等）が発生 | `GlobalExceptionHandler.handleUnexpectedException` が捕捉 | 500 Internal Server Error |
| 10 | `parameters` の有無 | リクエストに `parameters` が省略されている | 空の `Map` として扱い処理続行（エラーにしない） | - |
| 11 | `parameters` の有無 | `parameters` が指定されている | 指定された値をそのまま Service に渡す | - |

上記 #1〜#3, #6, #8, #9 が異常系、#4, #5, #7, #10, #11 が正常系の分岐である。
それぞれ独立にテストケースを作成できるよう、Service 側の分岐（#5〜#9）は
`TaskExecutionService` の単体テストで、Controller 側の分岐（#1〜#4）は
`@WebMvcTest` を用いた Controller の単体テストで網羅する想定。

### 4.3 異常時のレスポンス形式（共通エラーフォーマット）

```json
{
  "status": "ERROR",
  "errorCode": "VALIDATION_ERROR",
  "message": "taskName must not be blank",
  "timestamp": "2026-06-27T12:34:56.789"
}
```

| `errorCode` | 対応するHTTPステータス | 発生条件 |
| :-- | :-- | :-- |
| `BAD_REQUEST` | 400 | JSONパース不能 |
| `VALIDATION_ERROR` | 400 | Bean Validation失敗 |
| `TASK_NOT_FOUND` | 404 | 未知の `taskName` |
| `TASK_EXECUTION_FAILED` | 422 | Service内の業務的な処理失敗 |
| `INTERNAL_ERROR` | 500 | 想定外の例外 |

## 5. アクセス制御方針（レビュー指摘への対応）

### 5.1 バインドアドレス

- `application.properties` に `server.address=127.0.0.1` を設定し、HTTP サーバーを
  **localhost のみで待受**させる。これにより、同一ホスト外（他のネットワークセグメント等）
  からの到達を不可能にする。
- 「0. 前提条件」に記載した「bash の実行元は同一ホスト」という前提と整合する設定である。

### 5.2 認可

- 本設計では認可（ユーザー認証・権限チェック）を実装しない。
- 理由: 本機能はデモ用途・開発者本人が同一ホスト上で手元の bash から実行することを想定して
  おり、バインドアドレスの限定だけでアクセス制御として十分と判断したため。Spring Security 等の
  追加導入は本機能のスコールに対して過剰であり、`01_method-study.md` の選択肢比較でも
  実装コストの低さを採用理由としている。
- リスクは「0.2 暫定運用におけるリスクの明記」の通り。

### 5.3 将来の拡張方針

認可が必要になった場合の拡張パスを以下に示す（本設計のスコープ外、参考情報）。

- 同一ホスト内の特定ユーザーのみに制限したい場合: Unix ドメインソケットへの切り替え、または
  リバースプロキシ＋クライアント証明書等を検討する。
- リモートホストからの呼び出しを許可する場合: Spring Security を追加し、Basic認証または
  APIキー方式の認可フィルタを `/internal/**` に適用する。あわせて `server.address` の
  限定を見直す。

## 6. エラーハンドリング方針（まとめ）

- すべての例外は `GlobalExceptionHandler`（`@RestControllerAdvice`）に集約し、Controller内に
  `try-catch` を直接書かない。
- 例外種別とHTTPステータスの対応は「4.3 異常時のレスポンス形式」の表に従う。
- 想定外の例外（`INTERNAL_ERROR`）の場合、レスポンスのメッセージにはスタックトレースや
  内部実装詳細を含めない（セキュリティ上、内部情報の露出を避ける）。サーバー側ログ
  （`logger.error(...)`）にはスタックトレースを出力する。

## 7. 設定項目

`src/main/resources/application.properties` に以下を追加する。

| キー | 設定値（デフォルト） | 説明 |
| :-- | :-- | :-- |
| `server.address` | `127.0.0.1` | HTTPサーバーのバインドアドレス。localhostのみ待受。 |
| `server.port` | `8080`（既存デフォルトを明示） | HTTPサーバーのポート番号。 |

```properties
spring.application.name=demo
server.address=127.0.0.1
server.port=8080
```

環境変数による上書きが必要な場合は、Spring Boot標準の仕組み（`SERVER_ADDRESS`,
`SERVER_PORT`）でオーバーライド可能（本設計では追加のカスタム環境変数は導入しない）。

## 8. bash からの呼び出し例

```bash
# 正常系: パラメータなし
curl -X POST http://127.0.0.1:8080/internal/tasks/execute \
  -H "Content-Type: application/json" \
  -d '{"taskName": "sample-task"}'

# 正常系: パラメータあり
curl -X POST http://127.0.0.1:8080/internal/tasks/execute \
  -H "Content-Type: application/json" \
  -d '{"taskName": "sample-task", "parameters": {"key1": "value1"}}'

# 異常系: 未知のタスク名 -> 404 Not Found が返る
curl -i -X POST http://127.0.0.1:8080/internal/tasks/execute \
  -H "Content-Type: application/json" \
  -d '{"taskName": "unknown-task"}'

# 異常系: taskName未指定 -> 400 Bad Request が返る
curl -i -X POST http://127.0.0.1:8080/internal/tasks/execute \
  -H "Content-Type: application/json" \
  -d '{}'
```

`curl -i` を付与するとレスポンスヘッダ（HTTPステータス含む）も確認できるため、bash側の
シェルスクリプトで終了コード判定を行う場合は `curl -s -o /dev/null -w "%{http_code}"` 等で
ステータスコードのみを取得し、`if [ "$STATUS" -ne 200 ]; then ... fi` のような分岐を
組むことを想定する。

## 9. 既存コードへの影響範囲

| 対象 | 影響内容 |
| :-- | :-- |
| `DemoApplication.java` | 変更不要。`@SpringBootApplication` のコンポーネントスキャンが `com.example.demo` 配下を対象とするため、`com.example.demo.task` パッケージは自動的にスキャン対象になる。 |
| `build.gradle` | 変更不要。`spring-boot-starter-webmvc` が既に依存に含まれており、Bean Validation（`spring-boot-starter-validation`）が必要な場合のみ追加検討が必要（下記「9.1」参照）。 |
| `application.properties` | `server.address`, `server.port` を追加（既存の `spring.application.name=demo` 行は変更しない）。 |
| 既存の他クラス | `DemoApplicationTests.java` を含め、既存クラスへの変更・影響なし（新規パッケージの追加のみ）。 |

### 9.1 Bean Validation 依存の確認

`@Valid` / `@NotBlank` 等を使用するためには `spring-boot-starter-validation` が必要だが、
現状の `build.gradle` には含まれていない。`spring-boot-starter-webmvc` に同梱されているかは
バージョンに依存するため、実装フェーズで実際に依存解決を確認し、不足していれば
`build.gradle` に `implementation 'org.springframework.boot:spring-boot-starter-validation'`
を追加する。この追加は既存機能に影響を与えない（新規依存の追加のみ）。

## 10. まとめ

- 新規パッケージ `com.example.demo.task` に Controller / Service / DTO / 例外処理を追加する。
- エンドポイントは `POST /internal/tasks/execute` の1本。
- アクセス制御はバインドアドレスの localhost 限定のみとし、認可は実装しない
  （リスクは本書 0.2 に明記）。
- 異常系の分岐は本書 4.2 の表に網羅されており、後続のテスト計画（`03_test-plan.md`）で
  この表をベースに C1網羅のテストケースを設計する。
