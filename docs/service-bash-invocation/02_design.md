# 詳細設計: 起動済み Spring Boot プロセスの Service を bash から実行する

本書は `01_method-study.md` で採用した方式（Spring MVC の `@RestController` で Service メソッドを
公開し、bash から `curl` で呼び出す）に基づく詳細設計である。

## 0. 前提条件（レビュー指摘への対応）

`01_method-study.md` のレビューで指摘された「呼び出し元の前提」「無認可運用のリスク」、および
`02_design.md` 初版レビューで指摘された「アクセス制御がアプリ全体に副作用を及ぼす点」を明確化する。

### 0.1 呼び出し元の前提

- **bash の実行元は、Spring Boot プロセスと同一ホスト上であることを前提とする。**
  リモートホストからの呼び出しは本設計の対象外とする。
- この前提により、`/internal/tasks/execute` へのアクセスをリクエスト元IPが `127.0.0.1`/`::1`
  （ローカルホスト）である場合のみに限定する（詳細は「5. アクセス制御方針」参照）。
  本プロジェクトは `spring-boot-starter-thymeleaf` 等を用いた既存・将来の業務用画面・APIを
  含むため、アクセス制御は **HTTPサーバー全体ではなく `/internal/**` パスに限定**して適用する
  （初版レビュー指摘への対応。詳細は「5.1」参照）。
- 将来的にリモートホストからの呼び出しが必要になった場合は、本設計の前提が崩れるため、
  Spring Security 等による認証・認可の追加実装を別途行う必要がある（本設計のスコープ外）。

### 0.2 暫定運用におけるリスクの明記

- 本設計では認可（authorization）の実装を行わない。アクセス制御は `/internal/**` パスに対する
  リクエスト元IPの限定（localhost only）のみに依存する。
- そのため、**同一ホスト上で実行される他のプロセス・ユーザーからは無制限にエンドポイントを
  呼び出せる**状態になる。これは意図的な暫定措置であり、リスクとして以下を認識しておく。
  - 同一ホストに複数ユーザーがログイン可能な環境（共有サーバー等）では、他ユーザーからも
    Service メソッドが実行できてしまう。
  - 将来 Service の処理内容が機密性の高い操作（データ削除、外部送信等）を含むようになった
    場合、現状の設計のままでは不十分である。
  - 本設計は「デモ用途・開発者本人による手元操作」を想定したものであり、本番相当の
    マルチユーザー環境にそのまま適用することは推奨しない。
  - 認可が必要になった場合の拡張方針は「5.4 将来の拡張方針」に記載する。

## 1. 既存コードベースの構成調査結果

| 項目 | 内容 |
| :-- | :-- |
| ビルドツール | Gradle（`build.gradle`） |
| Spring Boot バージョン | 4.0.7 |
| Java バージョン | 25（toolchain指定） |
| ルートパッケージ | `com.example.demo` |
| 既存クラス | `com.example.demo.DemoApplication`（`@SpringBootApplication`）のみ |
| Web フレームワーク | `spring-boot-starter-webmvc`（Spring MVC、`@RestController` 利用可） |
| その他依存 | `spring-boot-starter-thymeleaf`（画面表示用。本機能では利用しないが、後述のアクセス制御方針に影響する既存依存として把握） |
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
    ├── config/
    │   └── InternalApiWebConfig.java           # /internal/** へのInterceptor登録（WebMvcConfigurer）
    ├── interceptor/
    │   └── LocalhostOnlyInterceptor.java        # /internal/** に対するリクエスト元IP制限（HandlerInterceptor）
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
| `LocalhostOnlyInterceptor` | `/internal/**` 配下へのリクエストについて、リクエスト元IPアドレスが `127.0.0.1` または `::1`（IPv6ループバック）であるかを判定する `HandlerInterceptor`。条件を満たさない場合は `403 Forbidden` を返し、Controller の呼び出しをブロックする。**業務用画面・API（Thymeleafベースの既存・将来のエンドポイント）には適用しない**。 |
| `InternalApiWebConfig` | `WebMvcConfigurer#addInterceptors` で `LocalhostOnlyInterceptor` を `/internal/**` パスのみに登録する設定クラス。 |
| `TaskExecutionRequest` | リクエストボディ（JSON）に対応する DTO。`taskName`（必須）、`parameters`（任意の `Map<String, String>`）を保持。Bean Validation アノテーション（`@NotBlank` 等）を付与。 |
| `TaskExecutionResponse` | レスポンスボディ（JSON）に対応する DTO。実行結果（`status`, `message`, `executedAt` 等）を保持。 |
| `TaskExecutionException` | Service 内で業務的に処理が失敗したことを表すカスタム例外（実行時例外）。発生原因に応じた `errorCode` を保持する（詳細は「6.2」参照）。 |
| `GlobalExceptionHandler` | `@RestControllerAdvice` により、`TaskExecutionException`・バリデーション例外・予期しない例外を捕捉し、統一的なエラーレスポンス（後述）に変換する。 |

### 2.2 クラス図（概略）

```
LocalhostOnlyInterceptor (HandlerInterceptor)
  + preHandle(HttpServletRequest, HttpServletResponse, Object): boolean
    （/internal/** にのみ適用。ローカルホスト以外からのリクエストは403を返しfalseを返す）

InternalApiWebConfig (WebMvcConfigurer)
  + addInterceptors(InterceptorRegistry): void
    （registry.addInterceptor(new LocalhostOnlyInterceptor()).addPathPatterns("/internal/**")）

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

また、アクセス制御を Interceptor として `TaskExecutionController` と分離することで、以下を実現する。

- Controller 自体は「ローカルホスト限定である」という横断的関心事を意識せずに実装できる
  （単一責任の原則）。
- 将来、別の `/internal/**` エンドポイントを追加する場合も、同じ Interceptor が自動的に
  適用される（`addPathPatterns("/internal/**")` で一括管理されるため）。
- 既存・将来の業務用画面・APIのパス（`/internal/**` 以外）には一切影響を与えない。

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
// LocalhostOnlyInterceptor
public class LocalhostOnlyInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        ...
    }
}
```

```java
// InternalApiWebConfig
@Configuration
public class InternalApiWebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LocalhostOnlyInterceptor())
                .addPathPatterns("/internal/**");
    }
}
```

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
bash(curl) -> LocalhostOnlyInterceptor.preHandle() -> TaskExecutionController.execute()
  0. Interceptorがリクエスト元IPを判定
       - 127.0.0.1 または ::1 -> true を返し処理続行
       - それ以外 -> 403を書き込み false を返す（4.2 異常系参照、Controllerまで到達しない）
  1. Spring MVC が application/json のリクエストボディを TaskExecutionRequest にデシリアライズ
  2. @Valid によるBean Validationを実行
       - taskName が null/空白 -> バリデーションエラー（4.2 異常系参照）
       - taskName が101文字以上 -> バリデーションエラー（4.2 異常系参照）
  3. Controller が TaskExecutionService.execute(taskName, parameters) を呼び出す
  4. Service内部処理:
       a. taskName に対応する処理が存在するかを判定
            - 対応する処理が存在する -> 処理を実行し TaskExecutionResult を返す
            - 対応する処理が存在しない -> TaskExecutionException（errorCode=TASK_NOT_FOUND）を
              スロー（4.2 異常系参照）
       b. 処理実行中に予期しない例外が発生
            - 発生しない -> 正常に結果を返す
            - 業務的な失敗（前提条件不成立等） -> TaskExecutionException（errorCode=
              TASK_EXECUTION_FAILED）をスロー（4.2 異常系参照）
            - 想定外の実行時例外 -> そのままControllerまで伝播（4.2 異常系参照）
  5. Controller が TaskExecutionResult を TaskExecutionResponse に変換
  6. HTTP 200 OK としてJSONレスポンスを返却
```

### 4.2 異常系の分岐（条件分岐の洗い出し）

後続のテスト計画工程（C1網羅）で利用できるよう、分岐が発生しうる箇所を明示する。

| # | 分岐箇所 | 条件 | 挙動 | HTTPステータス |
| :-- | :-- | :-- | :-- | :-- |
| 0a | `LocalhostOnlyInterceptor` によるIP判定（`/internal/**`のみ） | リクエスト元IPが `127.0.0.1`/`::1` | `preHandle` が `true` を返し処理続行 | - |
| 0b | `LocalhostOnlyInterceptor` によるIP判定（`/internal/**`のみ） | リクエスト元IPがローカルホスト以外 | `preHandle` がレスポンスに403を直接書き込み `false` を返す（Controller到達なし） | 403 Forbidden |
| 1 | リクエストボディのデシリアライズ | JSON として不正な形式（パース不能） | `HttpMessageNotReadableException` を `GlobalExceptionHandler` が捕捉 | 400 Bad Request |
| 2 | `taskName` のバリデーション | `taskName` が null または空白のみ | `MethodArgumentNotValidException` を捕捉 | 400 Bad Request |
| 3 | `taskName` のバリデーション | `taskName` が101文字以上 | `MethodArgumentNotValidException` を捕捉 | 400 Bad Request |
| 4 | `taskName` のバリデーション | `taskName` が1〜100文字の妥当な値 | バリデーション通過、Service呼び出しへ進む | - |
| 5 | Service内のタスク名解決 | `taskName` に対応する処理が定義されている | 処理を実行し結果を返す | 200 OK |
| 6 | Service内のタスク名解決 | `taskName` に対応する処理が未定義 | `TaskExecutionException`（`errorCode=TASK_NOT_FOUND`）をスロー | 404 Not Found |
| 7 | Service内の処理実行 | 処理が正常終了 | `TaskExecutionResult` を返す | 200 OK |
| 8 | Service内の処理実行 | 処理中に業務的な失敗（前提条件不成立等）が発生 | `TaskExecutionException`（`errorCode=TASK_EXECUTION_FAILED`）をスロー | 422 Unprocessable Entity |
| 9 | Service内の処理実行 | 想定外の実行時例外（`RuntimeException` 等）が発生 | `GlobalExceptionHandler.handleUnexpectedException` が捕捉 | 500 Internal Server Error |
| 10 | `parameters` の有無 | リクエストに `parameters` が省略されている | 空の `Map` として扱い処理続行（エラーにしない） | - |
| 11 | `parameters` の有無 | `parameters` が指定されている | 指定された値をそのまま Service に渡す | - |

上記 #0b, #1〜#3, #6, #8, #9 が異常系、#0a, #4, #5, #7, #10, #11 が正常系の分岐である。
それぞれ独立にテストケースを作成できるよう、Interceptor側の分岐（#0a, #0b）は
`LocalhostOnlyInterceptor` の単体テスト（`MockHttpServletRequest` 等を用いる）で、
Service 側の分岐（#5〜#9）は `TaskExecutionService` の単体テストで、Controller 側の分岐
（#1〜#4）は `@WebMvcTest` を用いた Controller の単体テストで網羅する想定。

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
| `FORBIDDEN` | 403 | `/internal/**` へのアクセス元IPがローカルホスト以外（`LocalhostOnlyInterceptor` が直接レスポンスを書き込むため、厳密には `GlobalExceptionHandler` を経由しないが、レスポンスボディの形式はこの表に合わせる） |
| `BAD_REQUEST` | 400 | JSONパース不能 |
| `VALIDATION_ERROR` | 400 | Bean Validation失敗 |
| `TASK_NOT_FOUND` | 404 | 未知の `taskName` |
| `TASK_EXECUTION_FAILED` | 422 | Service内の業務的な処理失敗 |
| `INTERNAL_ERROR` | 500 | 想定外の例外 |

## 5. アクセス制御方針（レビュー指摘への対応）

### 5.1 パス単位でのIPアドレス制限（初版からの方式変更）

初版設計では `server.address=127.0.0.1` によりHTTPサーバー全体をローカルホスト限定にする
方式を採用していたが、**この設定はアプリケーション全体のHTTPコネクタに適用されるため、
`/internal/tasks/execute` だけでなく、本プロジェクトが依存する `spring-boot-starter-thymeleaf`
ベースの既存・将来の業務用画面・APIもすべてlocalhost限定になってしまう**という副作用が
設計レビューで指摘された。

業務用エンドポイントを将来外部公開する可能性を排除しない（現時点で「外部公開しない」と
確定させる根拠が本プロジェクトには存在しない）ため、レビュー指摘の対応方針 (b) を採用し、
**`server.address` によるアプリ全体の制限は行わず、`HandlerInterceptor`（
`LocalhostOnlyInterceptor`）により `/internal/**` パスのみにリクエスト元IPの制限を適用する**
方式に変更する。

- `InternalApiWebConfig`（`WebMvcConfigurer`）で、`LocalhostOnlyInterceptor` を
  `addPathPatterns("/internal/**")` として登録する。
- `LocalhostOnlyInterceptor#preHandle` は `HttpServletRequest#getRemoteAddr()` を取得し、
  `127.0.0.1` または `::1`（IPv6ループバック）のいずれでもない場合は、レスポンスステータスを
  `403 Forbidden` に設定し、エラーボディ（「4.3」の `errorCode=FORBIDDEN` 形式）を書き込んだ
  上で `false` を返す（Controller を呼び出さない）。
- これにより、`server.address` はデフォルト（全インタフェースで待受）のままとなり、
  既存・将来の業務用画面・API（Thymeleafベースのものを含む）の到達性には一切影響を与えない。
- リバースプロキシ等を経由する構成（`X-Forwarded-For` ヘッダの考慮）は本設計のスコープ外とする
  （現状デモ用途であり、リバースプロキシ経由でのアクセスは想定していないため）。将来
  リバースプロキシを導入する場合は `getRemoteAddr()` だけでは不十分になるため、別途見直しが
  必要である旨をここに明記する。

### 5.2 「0. 前提条件」との整合性

- 「0.1 呼び出し元の前提」に記載した「bash の実行元は同一ホスト」という前提は、
  `/internal/**` のみを対象とした `LocalhostOnlyInterceptor` によって実現される。
- アプリ全体（業務用画面・API）の外部到達性については、本設計では変更を加えない
  （現状の `application.properties` の設定をそのまま維持する）。本機能の追加が既存・将来の
  業務要件（外部公開の要否）に影響を及ぼさないことを保証する。

### 5.3 認可

- 本設計では認可（ユーザー認証・権限チェック）を実装しない。
- 理由: 本機能はデモ用途・開発者本人が同一ホスト上で手元の bash から実行することを想定して
  おり、`/internal/**` へのアクセス元IP限定だけでアクセス制御として十分と判断したため。
  Spring Security 等の追加導入は本機能のスコープに対して過剰であり、`01_method-study.md` の
  選択肢比較でも実装コストの低さを採用理由としている。
- リスクは「0.2 暫定運用におけるリスクの明記」の通り。

### 5.4 将来の拡張方針

認可が必要になった場合の拡張パスを以下に示す（本設計のスコープ外、参考情報）。

- 同一ホスト内の特定ユーザーのみに制限したい場合: Unix ドメインソケットへの切り替え、または
  リバースプロキシ＋クライアント証明書等を検討する。
- リモートホストからの呼び出しを許可する場合: Spring Security を追加し、Basic認証または
  APIキー方式の認可フィルタを `/internal/**` に適用する。あわせて
  `LocalhostOnlyInterceptor` の制限方針を見直す。
- リバースプロキシ経由でのアクセスに対応する場合: `getRemoteAddr()` ではなく
  `X-Forwarded-For` 等のヘッダを信頼できるプロキシ設定と組み合わせて検証する仕組みに
  拡張する。

## 6. エラーハンドリング方針（まとめ）

### 6.1 例外ハンドリングの集約

- `/internal/**` へのアクセス制限（403）は `LocalhostOnlyInterceptor` がControllerに到達する
  前に直接レスポンスを構築するため、`GlobalExceptionHandler` を経由しない。それ以外の
  すべての例外は `GlobalExceptionHandler`（`@RestControllerAdvice`）に集約し、Controller内に
  `try-catch` を直接書かない。
- 例外種別とHTTPステータスの対応は「4.3 異常時のレスポンス形式」の表に従う。
- 想定外の例外（`INTERNAL_ERROR`）の場合、レスポンスのメッセージにはスタックトレースや
  内部実装詳細を含めない（セキュリティ上、内部情報の露出を避ける）。サーバー側ログ
  （`logger.error(...)`）にはスタックトレースを出力する。

### 6.2 422レスポンスの組み立て方針

- `TaskExecutionException` には `@ResponseStatus` を付与せず、`GlobalExceptionHandler` 側で
  `errorCode` に応じてステータスを判定し `ResponseEntity.status(...)` を明示的に組み立てる
  方式を採用する。
  - 理由: 同一の例外クラス `TaskExecutionException` が `errorCode` の値（`TASK_NOT_FOUND` =
    404 / `TASK_EXECUTION_FAILED` = 422）によって異なるHTTPステータスに対応するため、
    クラスに固定の `@ResponseStatus` を付与する方式では1対1の対応にならず表現できない。
  - 実装イメージ:
    ```java
    @ExceptionHandler(TaskExecutionException.class)
    public ResponseEntity<ErrorResponse> handleTaskExecutionException(TaskExecutionException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TASK_EXECUTION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse("ERROR", ex.getErrorCode().name(), ex.getMessage(), Instant.now()));
    }
    ```

### 6.3 `TaskExecutionException` の `errorCode` 振り分け方針

- `TaskExecutionException` は `errorCode`（enum、`TaskExecutionErrorCode { TASK_NOT_FOUND,
  TASK_EXECUTION_FAILED }`）をコンストラクタ引数として保持するフィールドを持つ
  （例外クラスを分けるのではなく、同一クラス内にコード保持フィールドを持たせる方式）。
  - 理由: 例外の発生箇所は `TaskExecutionService.execute()` 内の異なる2分岐
    （タスク名解決失敗／業務的な処理失敗）だが、Controller・GlobalExceptionHandler 側から見れば
    「業務処理に関する例外」という共通の捉え方で扱いたいため、クラスを分けるよりも
    `errorCode` フィールドで判別する方式の方がハンドラの実装がシンプルになる。
- `TaskExecutionService` 側での振り分け:
  - `taskName` に対応する処理が未定義の場合 ->
    `new TaskExecutionException(TaskExecutionErrorCode.TASK_NOT_FOUND, "unknown task: " + taskName)`
    をスローする。
  - 処理実行中に業務的な前提条件不成立等が発生した場合 ->
    `new TaskExecutionException(TaskExecutionErrorCode.TASK_EXECUTION_FAILED, "<具体的な失敗理由>")`
    をスローする。
  - 想定外の実行時例外（`NullPointerException` 等、業務的に想定していないもの）は
    `TaskExecutionException` でラップせず、そのまま伝播させ
    `GlobalExceptionHandler.handleUnexpectedException` で捕捉する（`INTERNAL_ERROR` = 500）。

## 7. 設定項目

`src/main/resources/application.properties` には、本機能のための新規キーは追加しない。

- 初版設計にあった `server.address=127.0.0.1` の追加は行わない（「5.1」の通り、アプリ全体への
  副作用を避けるため、Interceptorによるパス単位の制御に変更したことに伴う）。
- アクセス制御対象IPアドレス（`127.0.0.1` / `::1`）は `LocalhostOnlyInterceptor` 内に直接
  記述する（ハードコード）。将来、許可IPの一覧を可変にする要件が出た場合は、
  `application.properties` に `internal-api.allowed-addresses` のようなキーを追加し、
  `@ConfigurationProperties` で外部化することを検討する（本設計のスコープ外）。
- `server.port` 等、既存の `application.properties`（`spring.application.name=demo` のみ）
  への変更は不要。

```properties
spring.application.name=demo
```

（変更なし。本機能追加によるキーの追加は行わない）

## 8. bash からの呼び出し例

```bash
# 正常系: パラメータなし（同一ホスト上のbashから127.0.0.1宛に実行することを前提とする）
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

# 異常系（参考）: ローカルホスト以外から到達した場合 -> 403 Forbidden が返る
# （bash側からは通常発生しないが、Interceptorの動作確認用）
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
| `application.properties` | 変更不要（「7. 設定項目」の通り、本機能のための新規キーは追加しない）。 |
| 既存・将来の業務用画面・API（`spring-boot-starter-thymeleaf` 等を用いるもの） | **影響なし。** `LocalhostOnlyInterceptor` は `/internal/**` パスのみに適用されるため、業務用エンドポイントの到達性（外部公開の可否）は本機能追加の前後で変化しない。初版設計の `server.address=127.0.0.1` 案ではアプリ全体に影響が及ぶ問題があったが、本版ではこれを回避している（「5.1」参照）。 |
| 既存の他クラス | `DemoApplicationTests.java` を含め、既存クラスへの変更・影響なし（新規パッケージの追加のみ）。 |

### 9.1 Bean Validation 依存の確認

`@Valid` / `@NotBlank` 等を使用するためには `spring-boot-starter-validation` が必要だが、
現状の `build.gradle` には含まれていない。`spring-boot-starter-webmvc` に同梱されているかは
バージョンに依存するため、実装フェーズで実際に依存解決を確認し、不足していれば
`build.gradle` に `implementation 'org.springframework.boot:spring-boot-starter-validation'`
を追加する。この追加は既存機能に影響を与えない（新規依存の追加のみ）。

## 10. まとめ

- 新規パッケージ `com.example.demo.task` に Controller / Service / Interceptor / Config /
  DTO / 例外処理を追加する。
- エンドポイントは `POST /internal/tasks/execute` の1本。
- アクセス制御は `LocalhostOnlyInterceptor` による `/internal/**` パス限定のリクエスト元IP制限
  とし、認可は実装しない（リスクは本書 0.2 に明記）。既存・将来の業務用エンドポイントには
  影響を与えない方式とした（初版設計レビュー指摘への対応、「5.1」「9」参照）。
- 422/404 等のHTTPステータスは `TaskExecutionException` の `errorCode` に基づき
  `GlobalExceptionHandler` が `ResponseEntity.status(...)` で明示的に組み立てる（「6.2」「6.3」）。
- 異常系の分岐は本書 4.2 の表に網羅されており、後続のテスト計画（`03_test-plan.md`）で
  この表をベースに C1網羅のテストケースを設計する。
