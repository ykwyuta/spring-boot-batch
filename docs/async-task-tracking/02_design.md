# 詳細設計: タスク実行の非同期化と処理状況のトラッキング

本書は `01_method-study.md` で採用した方式（選択肢A: インメモリ状態管理（`ConcurrentHashMap`）＋
`@Async`/`ExecutorService` による非同期実行）に基づく詳細設計である。既存の同期API
（`POST /internal/tasks/execute`、`docs/service-bash-invocation/02_design.md` で設計・実装済み）は
**変更せず残し**、非同期版を新規エンドポイントとして追加する。

## 0. 前提条件・スコープ

- 本機能は `com.example.demo.task` パッケージ配下に新規クラスを追加する形で実現し、既存クラス
  （`TaskExecutionController` / `TaskExecutionService` / `TaskExecutionRequest` /
  `TaskExecutionResponse` / `TaskExecutionException` / `TaskExecutionErrorCode` /
  `GlobalExceptionHandler` / `ErrorResponse` / `LocalhostOnlyInterceptor` /
  `InternalApiWebConfig`）には**変更を加えない**（詳細は「9. 既存コードへの影響範囲」で確認する）。
- アクセス制御（`LocalhostOnlyInterceptor` による `/internal/**` 限定のlocalhostアクセス制御）は、
  新規エンドポイントを `/internal/**` 配下に配置することで自動的に適用される。新規の設定・実装は
  不要である。
- bash クライアントスクリプトの実装自体は本書の対象外（後続の実装工程で作成する）。本書では
  API仕様・クラス構成・状態遷移・エラーハンドリングを確定する。
- 用語の整理: 以下「非同期API」は本書で新規追加する `/internal/tasks/execute-async` 系エンドポイント、
  「既存同期API」は `POST /internal/tasks/execute` を指す。「トラッキングキー」と「タスクID」
  （`taskId`）は同義として扱う。

## 1. 既存コードベースの構成調査結果

`docs/service-bash-invocation/02_design.md`（既存同期APIの設計書）の調査結果を踏襲しつつ、本機能の
設計に必要な追加確認事項を以下に示す。

| 項目 | 内容 |
| :-- | :-- |
| ビルドツール | Gradle（`build.gradle`） |
| Spring Boot バージョン | 4.0.7 |
| Java バージョン | 25（toolchain指定） |
| ルートパッケージ | `com.example.demo` |
| Web フレームワーク | `spring-boot-starter-webmvc`（Spring MVC、Servlet系）。`@Async`/`@EnableAsync`/`ThreadPoolTaskExecutor` は `spring-context` に標準で含まれ、追加依存は不要。 |
| Bean Validation | `spring-boot-starter-validation` 導入済み（`build.gradle` に明記。既存同期APIの `@NotBlank`/`@Size` で利用中）。 |
| Lombok | `compileOnly`/`annotationProcessor` で導入済み。 |
| JSON処理 | Jackson系（`tools.jackson.databind.ObjectMapper`、`LocalhostOnlyInterceptor` で利用例あり）。Spring Boot 4.0系のJackson3ベースのパッケージ名 `tools.jackson.*` を踏襲する。 |
| MyBatis/PostgreSQL/Spring Batch | 方式検討の結論通り、本機能では使用しない（選択肢Aのため）。 |
| 既存パッケージ構成 | `com.example.demo.task`（ルート）/ `config` / `dto` / `exception` / `interceptor` の4サブパッケージ。 |

既存の `com.example.demo.task` パッケージの構成・命名規約は以下の通り（実装ファイルを確認済み）。

| クラス | パッケージ | 役割 |
| :-- | :-- | :-- |
| `TaskExecutionController` | `task` | `POST /internal/tasks/execute` を公開。Service呼び出し・DTO変換のみ。 |
| `TaskExecutionService` | `task` | `execute(String taskName, Map<String,String> parameters): TaskExecutionResult` を提供する業務処理本体。`sample-task`/`task-business-failure`/`task-unexpected-error` の3パターンをハードコードでデモ実装。 |
| `TaskExecutionResult` | `task` | Service戻り値のレコード（`message` のみ）。 |
| `TaskExecutionRequest` / `TaskExecutionResponse` | `task.dto` | リクエスト/レスポンスDTO（レコード、Bean Validationアノテーション付き）。 |
| `TaskExecutionException` | `task.exception` | `TaskExecutionErrorCode`（`TASK_NOT_FOUND`/`TASK_EXECUTION_FAILED`）を保持する業務例外。 |
| `GlobalExceptionHandler` / `ErrorResponse` | `task.exception` | `@RestControllerAdvice` による統一エラーレスポンス（`status`/`errorCode`/`message`/`timestamp`）。 |
| `LocalhostOnlyInterceptor` / `InternalApiWebConfig` | `task.interceptor` / `task.config` | `/internal/**` のlocalhost限定アクセス制御。 |

- 命名規約: `TaskExecutionXxx`（クラス）、DTOは `dto` サブパッケージ、例外は `exception` サブパッケージ、
  Web設定は `config` サブパッケージ、Interceptorは `interceptor` サブパッケージに配置する規則が
  既存コードから読み取れる。本機能でもこの規則を踏襲する。
- DTOはすべて `record` で実装され、Bean Validationアノテーション（`@NotBlank`/`@Size`）をフィールドに
  直接付与する形式。Controllerは `@Valid @RequestBody` を用いる。
- `TaskExecutionController` はコンストラクタインジェクションのみ（Lombok未使用）。Service層も同様。
- 例外処理は「例外クラスを増やさず `errorCode` で種別を判別する」方式（`TaskExecutionException` 1クラスに
  `TaskExecutionErrorCode` enumを持たせる）。本機能でも同様の方式を踏襲するか、新規の例外要因
  （トラッキングキー不正等）にどう対応するかを「8. エラーハンドリング方針」で確定する。
- テストは `@WebMvcTest` + `MockitoBean`（Controller）、プレーンJUnit（Service）、`MockHttpServletRequest`
  相当（Interceptor）、`@RestControllerAdvice` の単体呼び出し（GlobalExceptionHandler）という構成。
  本書はテスト計画工程（`03_test-plan.md`）に向けて分岐を明示する（後述「7. 異常系の分岐」）。
