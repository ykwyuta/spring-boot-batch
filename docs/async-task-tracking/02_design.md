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

## 2. パッケージ構成・クラス構成

既存の `com.example.demo.task` パッケージ配下に、非同期実行・状態管理のための新規クラスを追加する。
既存クラス（`TaskExecutionController` 等）は変更しない。

```
src/main/java/com/example/demo/
└── task/
    ├── TaskExecutionController.java                  (既存、変更なし)
    ├── TaskExecutionService.java                       (既存、変更なし)
    ├── TaskExecutionResult.java                         (既存、変更なし)
    ├── async/
    │   ├── AsyncTaskExecutionController.java           # 新規。非同期実行・ステータス確認・結果取得を公開（Web層）
    │   ├── AsyncTaskExecutionService.java               # 新規。非同期実行のラッパー（既存TaskExecutionServiceを呼び出す）
    │   ├── AsyncTaskExecutionStateStore.java            # 新規。ConcurrentHashMapベースの状態ストア（Bean）
    │   ├── AsyncTaskRecord.java                         # 新規。状態ストアが保持する1タスク分のレコード
    │   ├── AsyncTaskStatus.java                         # 新規。状態遷移を表すenum
    │   └── config/
    │       └── AsyncTaskExecutorConfig.java             # 新規。@EnableAsync + 専用ThreadPoolTaskExecutorのBean定義
    ├── config/
    │   └── InternalApiWebConfig.java                   (既存、変更なし)
    ├── interceptor/
    │   └── LocalhostOnlyInterceptor.java                (既存、変更なし)
    ├── dto/
    │   ├── TaskExecutionRequest.java                    (既存、変更なし)
    │   ├── TaskExecutionResponse.java                   (既存、変更なし)
    │   ├── AsyncTaskExecutionRequest.java               # 新規。非同期実行リクエストDTO
    │   ├── AsyncTaskExecutionAcceptedResponse.java      # 新規。非同期実行受付時レスポンスDTO（taskId即時返却）
    │   └── AsyncTaskStatusResponse.java                 # 新規。ステータス確認・結果取得共通のレスポンスDTO
    └── exception/
        ├── TaskExecutionException.java                  (既存、変更なし)
        ├── TaskExecutionErrorCode.java                   (既存、変更なし)
        ├── GlobalExceptionHandler.java                    (既存。新規ExceptionHandlerメソッドのみ追加、既存メソッドは変更なし)
        ├── ErrorResponse.java                             (既存、変更なし)
        ├── AsyncTaskNotFoundException.java               # 新規。不正・未知のtaskId指定時の例外
        └── AsyncTaskNotCompletedException.java           # 新規。未完了タスクへの結果取得時の例外
```

新規クラスは既存の命名規約（`TaskExecutionXxx`）と区別できるよう `AsyncTaskExecutionXxx`/`AsyncTaskXxx`
というプレフィックスで統一する（既存同期APIと完全に同名衝突しない範囲で、機能の対応関係が分かる
命名とする）。`async` という新規サブパッケージを切ることで、既存の `task` パッケージ直下の既存クラスと
混在せず、責務の境界を明確にする（既存の `config`/`dto`/`exception`/`interceptor` という横断的サブ
パッケージの構成は流用し、新規クラスをそこに追記する形にする）。

### 2.1 各クラスの責務

| クラス | 責務 |
| :-- | :-- |
| `AsyncTaskExecutionController` | 非同期実行・ステータス確認・結果取得の3エンドポイントを公開するWeb層。リクエストのバリデーション結果のハンドリング、`AsyncTaskExecutionService` の呼び出し、レスポンスDTOへの変換のみを担う（業務ロジックは持たない）。既存の `TaskExecutionController` と役割分担の方針を揃える。 |
| `AsyncTaskExecutionService` | 非同期実行の起点。入力ファイルパスの存在検証、状態ストアへの初期レコード登録（`PENDING`）、`@Async` メソッドの呼び出し（`RUNNING` への遷移と既存 `TaskExecutionService.execute(...)` の実体呼び出し、出力ファイルパスの決定、結果の状態ストアへの反映）を担う。既存の `TaskExecutionService` には依存するが変更は加えない。 |
| `AsyncTaskExecutionStateStore` | `ConcurrentHashMap<UUID, AsyncTaskRecord>` を保持するシングルトンBean。`save`/`find`/`updateStatus` 等の操作を提供し、状態の読み書きをスレッドセーフに行う。将来DB等の永続化方式に切り替える際の置き換え単位になる（方式検討「将来の拡張性」を踏まえ、Service層から直接 `ConcurrentHashMap` を触らせず本クラスに閉じ込める）。 |
| `AsyncTaskRecord` | 1タスク分の状態（`taskId`, `status`, `taskName`, `inputFilePaths`, `outputFilePaths`, `message`, `errorCode`, `createdAt`, `updatedAt`）を保持するイミュータブルなレコード（更新時は新しいインスタンスを生成して `ConcurrentHashMap` に再格納する。詳細は「3.3」）。 |
| `AsyncTaskStatus` | `PENDING`/`RUNNING`/`SUCCEEDED`/`FAILED` の4値を持つenum。状態遷移の定義は「3.2」。 |
| `AsyncTaskExecutorConfig` | `@EnableAsync` を有効化し、非同期実行専用の `ThreadPoolTaskExecutor`（Bean名 `asyncTaskExecutor`）を定義する設定クラス。スレッドプールサイズ・キュー長は「4. 非同期実行方式」で確定する。 |
| `AsyncTaskExecutionRequest` | 非同期実行リクエストDTO。`taskName`（既存と同形式）、`parameters`（既存と同形式）、`inputFilePaths: List<String>`（新規）を保持する。 |
| `AsyncTaskExecutionAcceptedResponse` | 非同期実行受付時（`202 Accepted`）のレスポンスDTO。`taskId`・`status`（`PENDING` 固定）・`acceptedAt` を保持する。 |
| `AsyncTaskStatusResponse` | ステータス確認・結果取得の両エンドポイントで共用するレスポンスDTO。`taskId`/`status`/`taskName`/`message`/`outputFilePaths`/`createdAt`/`updatedAt` を保持し、未完了時は `outputFilePaths`/`message` が `null`（または空配列）になる（詳細は「5. 公開インターフェース」）。 |
| `AsyncTaskNotFoundException` | 存在しない・不正な形式の `taskId` が指定された場合にスローする例外。`GlobalExceptionHandler` で `404 Not Found` に変換する。 |
| `AsyncTaskNotCompletedException` | 完了前（`PENDING`/`RUNNING`）のタスクに対して結果取得APIが呼ばれた場合にスローする例外。`GlobalExceptionHandler` で `409 Conflict` に変換する。 |

Controller / Service / StateStore の3層分離により、以下を実現する。

- `AsyncTaskExecutionService` の `@Async` メソッド呼び出しと状態ストアの読み書きを単体テストしやすい
  （Spring MVCコンテキストを起動せずに検証できる）。
- 状態ストアをインタフェース越しではなく具象クラスとして1つに集約することで、将来的な永続化方式への
  切り替え（方式検討で触れた拡張性）の際に変更箇所を `AsyncTaskExecutionStateStore` に閉じ込められる。

## 3. 状態ストアの設計

### 3.1 `AsyncTaskExecutionStateStore`

```java
@Component
public class AsyncTaskExecutionStateStore {

    private final ConcurrentHashMap<UUID, AsyncTaskRecord> records = new ConcurrentHashMap<>();

    public void save(AsyncTaskRecord record) { ... }

    public Optional<AsyncTaskRecord> find(UUID taskId) { ... }

    /**
     * 既存レコードに対して更新関数を適用し、結果を保存する。
     * 楽観的な単一スレッド更新（同一taskIdに対する更新は実行スレッド1つのみが行うため、
     * 厳密なCAS（compareAndSet）は必須ではないが、ConcurrentHashMap#computeを用いて
     * スレッドセーフ性を保証する。詳細は「3.4」参照。
     */
    public AsyncTaskRecord update(UUID taskId, UnaryOperator<AsyncTaskRecord> updater) { ... }

    public void removeIfOlderThan(Instant threshold) { ... } // 「6. 完了済みタスクの保持期間」で使用
}
```

- シングルトンBean（`@Component`）として1インスタンスのみ生成され、`AsyncTaskExecutionService` に
  コンストラクタインジェクションされる。
- キーは `UUID`（`TaskId` 型のラッパークラスは導入せず、`java.util.UUID` をそのまま使う。方式検討
  「トラッキングキーの生成方式」の結論を踏襲し、追加の抽象化は導入しないシンプルな方針とする）。
- `find` の戻り値を `Optional<AsyncTaskRecord>` とすることで、呼び出し側（Service層）に
  「taskIdが存在しない」分岐の処理を強制し、`null` チェック漏れを防ぐ。

### 3.2 `AsyncTaskStatus`（状態遷移）

```java
public enum AsyncTaskStatus {
    /** 受付済み、実行スレッドへのディスパッチ待ち。 */
    PENDING,
    /** 非同期実行スレッドで処理中。 */
    RUNNING,
    /** 処理が正常終了。 */
    SUCCEEDED,
    /** 処理が異常終了（業務的な失敗、または想定外の例外）。 */
    FAILED
}
```

**状態遷移図**

```
            (1) 非同期実行リクエスト受理・状態ストアへ初期登録
                          │
                          ▼
                      PENDING
                          │
        (2) スレッドプールがタスクをディスパッチし実行開始
                          │
                          ▼
                      RUNNING
                ┌─────────┴─────────┐
   (3a) 正常終了 │                   │ (3b) 異常終了
                ▼                   ▼
            SUCCEEDED             FAILED
        （終端状態。以後遷移しない）（終端状態。以後遷移しない）
```

| # | 遷移元 | 遷移先 | 遷移条件 |
| :-- | :-- | :-- | :-- |
| T1 | （新規） | `PENDING` | `AsyncTaskExecutionService` が非同期実行リクエストを受理し、入力ファイル存在検証を通過した直後に状態ストアへ初期登録する。 |
| T2 | `PENDING` | `RUNNING` | `@Async` メソッドが実際にスレッドプール上で実行を開始した直後（既存 `TaskExecutionService.execute(...)` 呼び出し前）。 |
| T3 | `RUNNING` | `SUCCEEDED` | `TaskExecutionService.execute(...)` が `TaskExecutionResult` を正常に返した場合。出力ファイルパスの決定（「5.4」参照）も合わせて完了させてから遷移する。 |
| T4 | `RUNNING` | `FAILED` | `TaskExecutionService.execute(...)` が `TaskExecutionException` をスローした場合、または想定外の例外（`RuntimeException` 等）をスローした場合。 |

- `SUCCEEDED`/`FAILED` は終端状態であり、以後の遷移は発生しない（再実行する場合は新規の非同期実行
  リクエスト＝新しい `taskId` で行う。同一 `taskId` への再実行APIは設けない）。
- `PENDING` から直接 `FAILED`/`SUCCEEDED` への遷移は発生しない設計とする（スレッドプールへの
  ディスパッチ自体が失敗するケース、すなわちキューが満杯で `RejectedExecutionException` が発生する
  ケースをどう扱うかは「4.3」「7. 異常系の分岐」で個別に扱う）。

### 3.3 `AsyncTaskRecord`

```java
public record AsyncTaskRecord(
        UUID taskId,
        AsyncTaskStatus status,
        String taskName,
        List<String> inputFilePaths,
        List<String> outputFilePaths,
        String message,
        String errorCode,
        Instant createdAt,
        Instant updatedAt) {

    static AsyncTaskRecord pending(UUID taskId, String taskName, List<String> inputFilePaths, Instant now) {
        return new AsyncTaskRecord(taskId, AsyncTaskStatus.PENDING, taskName, inputFilePaths,
                List.of(), null, null, now, now);
    }

    AsyncTaskRecord withRunning(Instant now) { ... }          // RUNNINGへの遷移（T2）
    AsyncTaskRecord withSucceeded(List<String> outputFilePaths, String message, Instant now) { ... } // T3
    AsyncTaskRecord withFailed(String errorCode, String message, Instant now) { ... }                // T4
}
```

- イミュータブルな `record` として実装し、状態遷移のたびに新しいインスタンスを生成して
  `AsyncTaskExecutionStateStore#update` 経由で `ConcurrentHashMap` に再格納する（更新中の一時的な
  不整合状態を読み取られるリスクを避ける。`ConcurrentHashMap` は同一キーに対する値の置き換え自体は
  アトミックである）。
- `outputFilePaths` は `PENDING`/`RUNNING` 中は空リスト（`List.of()`）、`SUCCEEDED` 時に確定値が
  設定される。`message`/`errorCode` も同様に未完了中は `null`。

### 3.4 並行性に関する設計判断

- 1つの `taskId` に対して状態を更新するスレッドは、非同期実行を担う1スレッドのみである
  （`AsyncTaskExecutionService` の `@Async` メソッド内で `PENDING -> RUNNING -> SUCCEEDED/FAILED` の
  遷移を直列に行うため、同一 `taskId` に対する更新の競合は発生しない）。
- 一方、「更新中の `taskId` を別スレッド（HTTPリクエストを処理するスレッド）がポーリングで読み取る」
  という競合は常に発生し得るため、`AsyncTaskExecutionStateStore#find` は `ConcurrentHashMap#get` を
  そのまま使い、読み取り側で最新の状態（更新がまだ反映されていなければ更新前の状態）を一貫した
  スナップショットとして取得できることを保証する（`AsyncTaskRecord` がイミュータブルであるため、
  読み取った瞬間の値が部分的に矛盾することはない）。
- `update` メソッドは `ConcurrentHashMap#compute(taskId, (key, old) -> updater.apply(old))` を用いて
  実装し、読み取りと書き込みの間に他スレッドの介入を許さない（同一 `taskId` への同時更新が将来
  発生する設計変更があっても安全側に倒す）。
