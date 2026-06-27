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

## 4. 非同期実行方式の確定

### 4.1 採用方式: `@Async` + `@EnableAsync` + 専用 `ThreadPoolTaskExecutor`

方式検討では「`@Async`」と「明示的な `ExecutorService`」のどちらでも実現可能とされていたが、本設計では
**`@Async` + 専用 `ThreadPoolTaskExecutor`** を採用する。

**採用理由**

1. Spring宣言的な書き方であり、`AsyncTaskExecutionService` のメソッドに `@Async` を付与するだけで
   スレッドプールへのディスパッチが行われる。明示的な `ExecutorService.submit(...)` 呼び出しを
   Service内に書く必要がなく、業務ロジック（既存 `TaskExecutionService.execute(...)` の呼び出しと
   状態更新）に専念できる。
2. 専用の `ThreadPoolTaskExecutor` をBean定義することで、スレッドプールサイズ・キュー長・拒否時の
   挙動（`RejectedExecutionHandler`）を一箇所（`AsyncTaskExecutorConfig`）に集約できる。
   `Executors.newFixedThreadPool(...)` を直接使う場合と比べ、Spring Bootの標準的な設定パターン
   （`ThreadPoolTaskExecutor` の `setCorePoolSize`/`setMaxPoolSize`/`setQueueCapacity`）に従える。
3. 例外伝播の扱いが明確になる。`@Async` メソッドの戻り値を `void` にする場合、メソッド内で発生した
   例外はSpring既定では握りつぶされてログ出力のみとなる（`AsyncUncaughtExceptionHandler`）。
   本設計では「メソッド内で必ず `try-catch` し、状態ストアへの `FAILED` 反映を行ってから握る」方式を
   採用する（詳細は「4.4」）ため、戻り値型は `void` で問題ない（`CompletableFuture` を使い呼び出し元で
   `.exceptionally(...)` を扱う必要がない）。

### 4.2 `AsyncTaskExecutorConfig`

```java
@Configuration
@EnableAsync
public class AsyncTaskExecutorConfig {

    @Bean(name = "asyncTaskExecutor")
    public Executor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
```

| 設定項目 | 値 | 理由 |
| :-- | :-- | :-- |
| `corePoolSize` | 2 | デモ用途・単一インスタンス・開発者本人による手元操作という前提（方式検討の制約条件1・2）の下では、同時に多数のタスクが実行されることは想定しない。2並列を確保しつつスレッド数を絞る。 |
| `maxPoolSize` | 4 | 一時的な同時実行数の上振れに対応するための上限。コア数を超える分はキュー投入後にあふれた場合のみ追加生成される（`ThreadPoolTaskExecutor` の既定動作）。 |
| `queueCapacity` | 50 | 同時に50件までは即座に受理しキューイングできるようにする。デモ用途として十分な余裕を持たせる値とした。 |
| `RejectedExecutionHandler` | `AbortPolicy`（既定） | キュー（50件）も使い切った場合は `RejectedExecutionException` を呼び出し元（Controllerを呼び出したスレッド）にスローさせ、明示的にエラーレスポンスへ変換する（「4.3」参照。タスクを静かに捨てる `DiscardPolicy` 等は状態不整合（`PENDING` のまま実行されないタスクが残る）を招くため不採用）。 |
| `threadNamePrefix` | `async-task-` | ログ・スレッドダンプ上で非同期タスク用スレッドを識別しやすくする。 |

`@Async("asyncTaskExecutor")` のように明示的にExecutor名を指定して `AsyncTaskExecutionService` の
メソッドへ付与する（Bean名を明示することで、将来 `@EnableAsync` 対象が増えた場合にデフォルト
Executorとの混同を避ける）。

### 4.3 スレッドプール飽和時（`RejectedExecutionException`）の扱い

- `@Async` メソッドの呼び出し自体（≒ `Executor.execute(...)` の呼び出し）はリクエストを受けた
  Servletスレッド上で行われる。`ThreadPoolTaskExecutor` がコアプール・最大プール・キューのすべてを
  使い切っている状態でリクエストが来た場合、`AbortPolicy` により `RejectedExecutionException`
  （`java.util.concurrent` の非チェック例外）がそのまま `AsyncTaskExecutionService` の非同期実行
  起動メソッドの呼び出し元（＝`AsyncTaskExecutionController`）に伝播する。
- この例外は `GlobalExceptionHandler` の汎用 `handleUnexpectedException`（`Exception.class` ハンドラ）
  で捕捉され、`503 Service Unavailable` を返す専用ハンドラを追加する（「8. エラーハンドリング方針」
  参照。500ではなく503とするのは、サーバーの実装不備ではなく一時的な過負荷であることをHTTPセマンティクス
  上明確にするため）。
- このケースでは状態ストアへの登録（`PENDING`）は行わない（リクエスト自体を受理しなかったものとして
  扱う）。状態ストアに不整合なレコードを残さないよう、`AsyncTaskExecutionService` は
  「状態ストアへの `PENDING` 登録」を「`@Async` メソッドの呼び出し（ディスパッチ）」の**前**に行うか
  **後**に行うかを設計上明確にする必要がある。本設計では、**ディスパッチ呼び出しが
  `RejectedExecutionException` をスローした場合に登録済みレコードを残さないよう、状態ストアへの登録は
  `@Async` メソッド呼び出しの直前に行い、呼び出し自体が例外をスローした場合は登録したレコードを
  削除してから例外を再スローする**方式を採る（実装詳細は「6.2 処理フロー」参照）。

### 4.4 `@Async` メソッド内での例外処理方針

```java
@Async("asyncTaskExecutor")
public void executeAsync(UUID taskId, String taskName, Map<String, String> parameters,
                          List<String> inputFilePaths) {
    stateStore.update(taskId, record -> record.withRunning(Instant.now()));
    try {
        TaskExecutionResult result = taskExecutionService.execute(taskName, parameters);
        List<String> outputFilePaths = resolveOutputFilePaths(taskId, taskName);
        stateStore.update(taskId, record ->
                record.withSucceeded(outputFilePaths, result.message(), Instant.now()));
    } catch (TaskExecutionException ex) {
        stateStore.update(taskId, record ->
                record.withFailed(ex.getErrorCode().name(), ex.getMessage(), Instant.now()));
    } catch (Exception ex) {
        logger.error("unexpected error occurred while executing async task {}", taskId, ex);
        stateStore.update(taskId, record ->
                record.withFailed("INTERNAL_ERROR", "unexpected error occurred", Instant.now()));
    }
}
```

- `@Async` メソッド内で発生するすべての例外（`TaskExecutionException` か想定外の例外か）を
  必ず `catch` し、状態ストアを `FAILED` に更新してから握る（呼び出し元には何も伝播させない）。
  これにより、Spring既定の `AsyncUncaughtExceptionHandler`（ログ出力のみで状態を残さない）に
  処理を委ねず、必ず状態ストアの整合性を保つ。
- 想定外の例外（`Exception` 全般）はサーバーログに `logger.error(...)` で記録する（既存の
  `GlobalExceptionHandler.handleUnexpectedException` と同様の方針をここでも踏襲する）。

## 5. 公開インターフェース（REST API仕様）

### 5.1 エンドポイント構成の決定

3つの操作（非同期実行・ステータス確認・結果取得）に対し、**ステータス確認と結果取得を1本の
エンドポイント（`GET /internal/tasks/{taskId}`）に統合する**。方式検討で「設計フェーズに委ねる」と
された論点（01_method-study.mdの「ポーリング用APIの形」）への決定とその理由を以下に示す。

**決定: 統合する（2エンドポイント構成: 実行受付 + 状態/結果取得）**

| メソッド | パス | 概要 |
| :-- | :-- | :-- |
| `POST` | `/internal/tasks/execute-async` | 非同期実行をリクエストし、トラッキングキー（`taskId`）を即時返却する。 |
| `GET` | `/internal/tasks/{taskId}` | `taskId` に対応するタスクの現在の状態を返す。完了済み（`SUCCEEDED`/`FAILED`）の場合は出力ファイルパス等の結果も同じレスポンスに含む。 |

**統合する理由**

1. `AsyncTaskRecord` が保持する情報（`status`/`outputFilePaths`/`message` 等）は元々1つのレコードで
   あり、「ステータスのみ」と「結果を含む全体」を別レスポンスとして分ける必然性がない。未完了時は
   `outputFilePaths` が空配列、`message` が `null` になるだけで、レスポンスの **形（JSON構造）は
   完了前後で同一**にできる。bash側の実装（`jq` でのフィールド抽出）もエンドポイントを1つ覗くだけで
   完結し、ポーリングと結果取得を同じURLに対して行えるため单純になる。
2. RESTfulな観点からも、`taskId` は1つのリソース（非同期タスクの実行記録）を表しており、
   `GET /internal/tasks/{taskId}` という単一リソース表現に対して時間経過に応じて内容（ステータス・
   結果）が変化していくと捉える方が自然である（`/status` と `/result` のように同一リソースを
   人為的に2つのサブパスへ分割する必要性が薄い）。
3. 実装コストの観点でも、Controllerメソッド・Service呼び出し・例外ハンドリングを1本に集約できる
   （2本に分けた場合、「未完了時に `/result` を呼んだら409を返す」という分岐をどちらのエンドポイントに
   持たせるかという設計上の重複が生じる。1本に統合すれば「レスポンスの `status` フィールドで
   未完了/完了済みを判別する」という単純な方針に一本化できる）。
4. 完了前に「結果」を期待して呼び出した場合の挙動（方式検討の論点5）も、エンドポイントを分けず
   `200 OK` ＋ `status: "RUNNING"`（結果フィールドはnull/空）で返す方針（「5.5」参照）にすることで、
   ポーリングのたびに同じURLを叩き続けるだけで完了を待てるという単純な運用になる。

   （結果取得を独立した `409 Conflict` で表現する選択肢も検討したが、「5.5」で述べる理由により
   本設計では不採用とし、`200 OK` ＋ステータスフィールドでの表現を採用する。）

### 5.2 非同期実行リクエストAPI

```
POST /internal/tasks/execute-async
Content-Type: application/json
```

```json
{
  "taskName": "sample-task",
  "parameters": {
    "key1": "value1"
  },
  "inputFilePaths": ["/data/in1.csv", "/data/in2.csv"]
}
```

| フィールド | 型 | 必須 | 説明・制約 |
| :-- | :-- | :-- | :-- |
| `taskName` | string | 必須 | 実行するタスクの識別子。既存同期APIと同じ制約（`@NotBlank`、最大100文字）。 |
| `parameters` | object（`Map<String, String>`） | 任意 | タスクに渡す追加パラメータ。省略時は空Mapとして扱う（既存同期APIと同様）。 |
| `inputFilePaths` | string配列（`List<String>`） | 任意（省略時は空リスト） | ローカルファイルシステム上の入力ファイルパス（絶対パス）。各要素は空文字・空白のみ不可（`@NotBlank` を要素に適用）。最大要素数は100件（`@Size(max=100)`、デモ用途として過大な配列を防ぐ）。 |

**正常時レスポンス**

```
HTTP/1.1 202 Accepted
Content-Type: application/json
```

```json
{
  "taskId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "PENDING",
  "acceptedAt": "2026-06-27T12:34:56.789Z"
}
```

| フィールド | 型 | 説明 |
| :-- | :-- | :-- |
| `taskId` | string（UUID） | トラッキングキー。以後のステータス確認・結果取得APIで使用する。 |
| `status` | string | `"PENDING"` 固定（受付直後の状態）。 |
| `acceptedAt` | string（ISO-8601, Instant） | リクエスト受理時刻。 |

- HTTPステータスは `202 Accepted` を採用する（既存同期APIの `200 OK` とは異なる。処理が完了して
  いないことをHTTPセマンティクス上明示するため）。
- `taskName`/`parameters` のバリデーション方針・エラーレスポンス形式は既存同期API（`400 Bad Request`、
  `errorCode=VALIDATION_ERROR`）と同一にする。

### 5.3 入力ファイルパスのバリデーション

**決定: 入力ファイル存在チェックは、非同期実行リクエストAPIの受付処理内（`@Async` メソッドへの
ディスパッチ前、同期的なControllerスレッド内）で行う。**

理由:

- 「ファイルが存在しない」というエラーは利用者（bash側）にとって即座にフィードバックされるべき
  入力エラーである。非同期実行を開始してから（`RUNNING` に遷移してから）失敗が分かるのでは、
  ポーリングのオーバーヘッドが無駄になり、UXが悪化する。
- 既存同期API（`TaskExecutionService`）のバリデーション方針（Controller受付時にできるだけ早く
  エラーを返す）とも一貫する。

| 検証項目 | 検証タイミング | 不正時の挙動 |
| :-- | :-- | :-- |
| `inputFilePaths` の各要素が空文字・空白でないか | Bean Validation（`@Valid`、Controller受付時） | `400 Bad Request`（`errorCode=VALIDATION_ERROR`） |
| `inputFilePaths` の要素数が100件以下か | Bean Validation（`@Valid`、Controller受付時） | `400 Bad Request`（`errorCode=VALIDATION_ERROR`） |
| 各パスが指すファイルが実際にファイルシステム上に存在するか（`Files.exists(Path.of(path))`） | `AsyncTaskExecutionService` 内、状態ストアへの `PENDING` 登録前（`@Async` ディスパッチ前、同期処理） | `AsyncInputFileNotFoundException` をスロー → `GlobalExceptionHandler` が `404 Not Found`（`errorCode=INPUT_FILE_NOT_FOUND`）に変換 |
| 各パスが指すファイルが通常ファイルであり、ディレクトリでないか（`Files.isRegularFile(path)`） | 上記と同様（存在確認と同時に判定） | 同上（`INPUT_FILE_NOT_FOUND`。「存在しない」と「ディレクトリである」を別エラーコードに分けるのは過剰と判断し、同一コードにまとめる） |

- 複数の `inputFilePaths` のうちいずれか1つでも存在しない場合、最初に見つかった不正なパスを
  メッセージに含めてエラーとする（全件のエラーをまとめて返す必要性は薄いと判断。デモ用途として
  シンプルさを優先する）。
- `inputFilePaths` が空リスト（省略時含む）の場合は、検証対象が存在しないため常に検証を通過する
  （入力ファイルを使わないタスクも許容する。既存の `TaskExecutionService` の処理は実際には
  ファイルI/Oを行わないデモ実装のため、入力ファイルパスの有無自体は現状の業務処理に影響しない。
  あくまで「複数ファイルパスを受け渡せること」自体をデモするための仕組みとして設計する）。

### 5.4 出力ファイルパスの決定方式

**決定: 出力ファイルパスは Service 側（`AsyncTaskExecutionService`）が自律的に決定し、結果取得
レスポンスで返却する（リクエスト側での指定は不要）。** 方式検討で示された2方式
（(i) Service自律決定 / (ii) リクエスト時指定）のうち、(i) を採用する。

**採用理由**

1. (ii)（リクエスト時指定）を採用する場合、出力先ディレクトリの存在検証（書き込み権限確認含む）・
   既存ファイルの上書き可否といった追加の分岐が必要になる（方式検討レビューでも指摘された懸念）。
   現状の `TaskExecutionService.execute(...)` は実際にはファイルへの書き込みを行わないデモ実装
   であり、「出力先を受け取って書き込む」という実体がない状態でこれらの分岐を設計・実装することは
   オーバーエンジニアリングになる。
2. (i) であれば、出力ファイルパスはデモ実装として `AsyncTaskExecutionService` が固定的な命名規則
   （例: `{システム一時ディレクトリ}/async-tasks/{taskId}/output.txt` 等の形式、または
   既存の `TaskExecutionResult.message()` をそのまま使い実ファイルは生成しない）で決定でき、
   バリデーション・エラー表現を追加する必要がない。利用者（bash側）は結果取得APIのレスポンスに
   含まれる `outputFilePaths` を読み取るだけでよく、実装・運用の両面でシンプルになる。
3. 将来、実際にファイル書き込みを行う具体的なタスクが追加された場合でも、(i) の方針（Service側が
   決定したパスを返す）は変更不要であり、`AsyncTaskExecutionService` 内の出力パス決定ロジックのみを
   拡張すればよい。

**出力ファイルパスの具体的な決定ロジック（デモ実装）**

- `AsyncTaskExecutionService` は、`taskName` が `sample-task` の場合、`outputFilePaths` として
  `List.of("/tmp/async-tasks/" + taskId + "/result.txt")` 形式の1要素のリストを生成する
  （実際のファイル書き込みは行わない。既存の `TaskExecutionService` がデモ実装のため、本機能でも
  同様にパス文字列の生成のみをデモする）。
- `taskName` が `task-business-failure`/`task-unexpected-error` の場合は、処理自体が失敗するため
  `outputFilePaths` は生成されない（空リストのまま `FAILED` に遷移する）。
- 上記はあくまでデモ用の固定ロジックであり、本書はその実装方針（Service側で決定し、固定の命名規則を
  用いる）を確定することが目的である。具体的なパス生成ロジックは実装フェーズで詳細化してよい。

### 5.5 ステータス確認・結果取得API（統合）

```
GET /internal/tasks/{taskId}
```

**正常時レスポンス（共通フォーマット、状態によりフィールドの値が変化する）**

```
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "taskId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "RUNNING",
  "taskName": "sample-task",
  "message": null,
  "outputFilePaths": [],
  "createdAt": "2026-06-27T12:34:56.789Z",
  "updatedAt": "2026-06-27T12:34:57.001Z"
}
```

完了後（`SUCCEEDED`）の例:

```json
{
  "taskId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "SUCCEEDED",
  "taskName": "sample-task",
  "message": "task executed successfully",
  "outputFilePaths": ["/tmp/async-tasks/3fa85f64-5717-4562-b3fc-2c963f66afa6/result.txt"],
  "createdAt": "2026-06-27T12:34:56.789Z",
  "updatedAt": "2026-06-27T12:35:10.222Z"
}
```

| フィールド | 型 | 説明 |
| :-- | :-- | :-- |
| `taskId` | string（UUID） | リクエストされたトラッキングキー（そのまま返す）。 |
| `status` | string | `PENDING`/`RUNNING`/`SUCCEEDED`/`FAILED` のいずれか。 |
| `taskName` | string | 実行された（実行中の）タスク名。 |
| `message` | string（nullable） | 完了時のみ値を持つ。`SUCCEEDED` 時は `TaskExecutionResult.message()`、`FAILED` 時は失敗理由。未完了時は `null`。 |
| `outputFilePaths` | string配列 | `SUCCEEDED` 時のみ値を持つ（「5.4」参照）。未完了・`FAILED` 時は空配列。 |
| `createdAt` | string（ISO-8601, Instant） | 非同期実行リクエストを受理した時刻。 |
| `updatedAt` | string（ISO-8601, Instant） | 状態が最後に更新された時刻。 |

**`status=FAILED` の場合のレスポンス例**

```json
{
  "taskId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "FAILED",
  "taskName": "task-business-failure",
  "message": "precondition not satisfied for task: task-business-failure",
  "outputFilePaths": [],
  "createdAt": "2026-06-27T12:34:56.789Z",
  "updatedAt": "2026-06-27T12:34:58.500Z"
}
```

- `errorCode`（`TASK_EXECUTION_FAILED`/`INTERNAL_ERROR` 等）はこのレスポンスには含めない方針とする。
  理由: 本APIは「タスク自体の処理結果」を表現するレスポンスであり、HTTPレベルのエラーレスポンス
  （`ErrorResponse` 形式）とは概念が異なる。`status=FAILED` 自体がエラーを表しており、詳細は
  `message` で表現すれば十分と判断する（`errorCode` を含めたい場合は実装フェーズで `AsyncTaskRecord`
  にすでに保持しているフィールドをレスポンスDTOに追加するだけで対応できるため、設計上の制約には
  ならない）。

**完了前に呼び出した場合の挙動（方式検討の論点5への決定）**

- `status` が `PENDING`/`RUNNING` の場合も **`200 OK`** を返す（`409 Conflict` 等のエラー扱いに
  しない）。理由は「5.1」で述べた通り、本APIを「現在の状態を返す」単一の取得APIとして統合した
  ため、未完了であること自体は正常な応答（まだ終わっていないという正しい情報）であり、HTTP的な
  異常とはみなさない。bash側は `status` フィールドの値でループを継続するかを判断する
  （方式検討の「bash運用イメージ」を参照、同方針を維持する）。
- この設計判断により、「3a. 入力ファイルの扱い」「5. 完了前に結果取得APIを呼んだ場合の挙動」という
  ユーザー要望上の論点に対し、本書は「エンドポイントは1本、ステータスで状態を表現し、未完了は
  200 OKで表現する」という一貫した回答を与える。

### 5.6 公開メソッドシグネチャ

```java
// AsyncTaskExecutionController
@PostMapping("/internal/tasks/execute-async")
public ResponseEntity<AsyncTaskExecutionAcceptedResponse> executeAsync(
        @Valid @RequestBody AsyncTaskExecutionRequest request) { ... }

@GetMapping("/internal/tasks/{taskId}")
public ResponseEntity<AsyncTaskStatusResponse> getStatus(@PathVariable String taskId) { ... }
```

```java
// AsyncTaskExecutionService
public UUID acceptAsyncExecution(String taskName, Map<String, String> parameters,
                                  List<String> inputFilePaths) { ... }
// 入力ファイル存在検証、PENDING登録、@Asyncメソッドへのディスパッチを行い、taskIdを返す。

@Async("asyncTaskExecutor")
public void executeAsync(UUID taskId, String taskName, Map<String, String> parameters,
                          List<String> inputFilePaths) { ... }
// 実際の非同期実行本体（「4.4」参照）。

public AsyncTaskRecord getStatus(UUID taskId) { ... }
// 状態ストアからレコードを取得する。存在しない場合はAsyncTaskNotFoundExceptionをスローする。
```

```java
// AsyncTaskExecutionStateStore
public void save(AsyncTaskRecord record) { ... }
public Optional<AsyncTaskRecord> find(UUID taskId) { ... }
public AsyncTaskRecord update(UUID taskId, UnaryOperator<AsyncTaskRecord> updater) { ... }
public void remove(UUID taskId) { ... }
public void removeIfOlderThan(Instant threshold) { ... }
```

## 6. 処理フロー

### 6.1 非同期実行リクエストのシーケンス（正常系）

```
bash(curl) -> LocalhostOnlyInterceptor.preHandle() -> AsyncTaskExecutionController.executeAsync()
  0. Interceptorがリクエスト元IPを判定（既存の仕組みをそのまま利用、変更なし）
       - 127.0.0.1 または ::1 -> true を返し処理続行
       - それ以外 -> 403（Controller到達なし）
  1. Spring MVC が application/json のリクエストボディを AsyncTaskExecutionRequest にデシリアライズ
  2. @Valid によるBean Validationを実行
       - taskName が null/空白、101文字以上 -> バリデーションエラー（400）
       - inputFilePaths の要素に空文字・空白を含む、101件以上 -> バリデーションエラー（400）
  3. Controller が AsyncTaskExecutionService.acceptAsyncExecution(...) を呼び出す
  4. Service内処理（同期、Controllerを呼び出したスレッド内で実行）:
       a. inputFilePaths の各要素についてファイル存在確認（Files.exists/isRegularFile）
            - すべて存在する（またはinputFilePathsが空） -> 処理続行
            - いずれかが存在しない/ディレクトリである -> AsyncInputFileNotFoundExceptionをスロー（404）
       b. taskId(UUID.randomUUID())を発行し、状態ストアにPENDINGレコードをsave
       c. @Asyncメソッド（executeAsync）をディスパッチ（asyncTaskExecutor経由）
            - ディスパッチ成功 -> taskIdをControllerに返す
            - スレッドプール飽和でRejectedExecutionException -> 直前に登録したレコードをstateStoreから
              remove、例外を再スロー（503、詳細は7番）
  5. Controller が taskId を AsyncTaskExecutionAcceptedResponse に変換
  6. HTTP 202 Accepted としてJSONレスポンスを返却（taskId, status=PENDING, acceptedAt）

--- （非同期、別スレッドで実行。Controllerはすでに202を返して終わっている） ---

asyncTaskExecutor配下のスレッド -> AsyncTaskExecutionService.executeAsync(taskId, ...)
  7. 状態ストアをRUNNINGに更新（T2）
  8. 既存の TaskExecutionService.execute(taskName, parameters) を呼び出す
       a. 正常終了 -> TaskExecutionResultを取得
            -> 出力ファイルパスを決定（5.4のロジック）
            -> 状態ストアをSUCCEEDEDに更新（T3, message/outputFilePathsを設定）
       b. TaskExecutionExceptionをスロー -> 状態ストアをFAILEDに更新（T4, errorCode/messageを設定）
       c. 想定外の例外をスロー -> ログ出力 -> 状態ストアをFAILEDに更新（T4, errorCode=INTERNAL_ERROR）
```

### 6.2 ステータス確認・結果取得のシーケンス

```
bash(curl) -> LocalhostOnlyInterceptor.preHandle() -> AsyncTaskExecutionController.getStatus()
  0. Interceptorによるアクセス制御（6.1と同様、変更なし）
  1. パス変数 taskId（文字列）を受け取る
  2. taskId を UUID.fromString(...) でパース
       - 正しいUUID形式 -> 処理続行
       - 不正な形式（パース不能） -> IllegalArgumentExceptionが発生 -> AsyncTaskNotFoundExceptionに
         変換してスロー（404、詳細は7番）
  3. Controller が AsyncTaskExecutionService.getStatus(taskId) を呼び出す
  4. Service が状態ストアから find(taskId) を実行
       - レコードが存在する -> AsyncTaskRecordを返す
       - レコードが存在しない（未知のtaskId、または既にTTLで削除済み） -> AsyncTaskNotFoundExceptionを
         スロー（404、詳細は7番）
  5. Controller が AsyncTaskRecord を AsyncTaskStatusResponse に変換
       - status が PENDING/RUNNING -> message=null, outputFilePaths=[] で組み立て
       - status が SUCCEEDED -> message/outputFilePathsを設定
       - status が FAILED -> messageを設定、outputFilePaths=[]
  6. HTTP 200 OK としてJSONレスポンスを返却（状態に応じた内容、5.5参照）
```

### 6.3 トラッキングキーが存在しない・不正な場合の挙動（共通方針）

ステータス確認・結果取得は1本のAPI（`GET /internal/tasks/{taskId}`）に統合されているため、
以下は当該APIに対して共通で適用される。

| ケース | 検出箇所 | 挙動 |
| :-- | :-- | :-- |
| `taskId` がUUID形式として不正（パース不能な文字列） | `AsyncTaskExecutionController.getStatus` 内、`UUID.fromString(...)` 呼び出し時 | `IllegalArgumentException` を捕捉し `AsyncTaskNotFoundException` に変換してスロー → `404 Not Found`（`errorCode=TASK_NOT_FOUND`） |
| `taskId` がUUID形式として正しいが、状態ストアに存在しない（未発行、または発行直後に削除済み） | `AsyncTaskExecutionService.getStatus` 内、`stateStore.find(taskId)` の結果が空 | `AsyncTaskNotFoundException` をスロー → `404 Not Found`（`errorCode=TASK_NOT_FOUND`） |

- 上記2ケースを同一の例外クラス・同一のエラーコード（`TASK_NOT_FOUND`）・同一のHTTPステータス
  （404）に統合する。「不正な形式」と「形式は正しいが存在しない」を呼び出し側（bash）が区別する
  必要性は薄く、いずれも「指定したtaskIdに対応するタスクは見つからない」という共通の結果として
  扱うのが利用者にとって分かりやすいと判断する。
- 既存の `TaskExecutionErrorCode.TASK_NOT_FOUND`（既存同期APIの「未知のtaskName」用）とは
  **異なる例外クラス**（`AsyncTaskNotFoundException`）・**異なるenum**
  （`AsyncTaskErrorCode.TASK_NOT_FOUND` 等、「8. エラーハンドリング方針」で定義）を用いる。
  既存の `TaskExecutionErrorCode` を流用すると、本来関係のない既存同期API側の例外ハンドリング
  ロジック（`TaskExecutionException` を捕捉する既存の `@ExceptionHandler`）と意味的に混同し、
  既存コードへの変更（enumへの値追加）が必要になってしまうため、新規にenum・例外クラスを追加する
  方針を採る（既存コード非破壊の原則、「9. 既存コードへの影響範囲」参照）。

## 7. 完了済みタスクの保持期間・削除方針

方式検討で「小さいが実装すべき項目」として残されていたメモリリーク対策を以下の方針で確定する。

**決定: 完了済み（`SUCCEEDED`/`FAILED`）タスクのレコードは、生成から一定時間（TTL）経過後に
バックグラウンドの定期処理で自動削除する。明示的な削除APIは設けない。**

| 項目 | 内容 |
| :-- | :-- |
| TTL（保持期間） | 完了時刻（`updatedAt`）から30分間。`application.properties` の新規キー `async-task.retention-minutes`（既定値30）で設定可能にする（「10. 設定項目」参照）。 |
| 削除方式 | `@Scheduled(fixedRate = 60000)`（1分間隔）で `AsyncTaskExecutionStateStore.removeIfOlderThan(Instant.now().minus(retention))` を呼び出す専用クラス（`AsyncTaskRetentionScheduler`、`async`パッケージに新規追加）を用意する。`@EnableScheduling` を `AsyncTaskExecutorConfig` に追加する（`@EnableAsync` と同一クラスにまとめて宣言してよい。役割が異なるためBean定義は分けるが、アノテーションの付与先クラスは1つにまとめる）。 |
| 削除対象の判定 | `status` が `PENDING`/`RUNNING` のレコードは削除しない（実行中のタスクを誤って消さないため）。`SUCCEEDED`/`FAILED` のレコードのみ、`updatedAt` がTTLを超えたものを削除する。 |
| 削除後の挙動 | 削除後に該当 `taskId` でステータス確認APIを呼ぶと「6.3」の「存在しない」ケースと同じ扱い（`404 Not Found`、`errorCode=TASK_NOT_FOUND`）になる。 |
| 明示的な削除APIの要否 | 設けない。TTLによる自動削除で十分とし、API表面を増やさない（デモ用途・運用負荷の小ささを優先）。将来的に必要であれば `DELETE /internal/tasks/{taskId}` を追加することは設計上容易（状態ストアに `remove` メソッドを用意済みのため）。 |

**理由**

- 方式検討の制約条件（デモ用途・単一インスタンス・開発者本人による手元操作）の下では、長時間の
  運用継続（数千〜数万タスクの蓄積）は想定しにくい。TTLベースの自動削除で十分にメモリリークを
  防止できる。
- ポーリング間隔の想定（方式検討のbash運用イメージでは2秒間隔）に対し、30分という保持期間は
  「ポーリングし損ねても十分に再確認できる」余裕を持たせた値である。
- 削除APIを設けない方針により、API表面・エラーハンドリング（削除権限の有無等）を増やさずに済む。
