# 方式検討: タスク実行の非同期化と処理状況のトラッキング

## 背景・課題

### 要望の整理

`docs/service-bash-invocation/` で実装済みの `POST /internal/tasks/execute`（以下「既存同期API」）は、
bash から `curl` で呼び出すと `TaskExecutionService` がタスクを**同期的に**実行し、HTTPレスポンスとして
その場で結果（`TaskExecutionResponse`）を返す。

今回、タスクの処理が長時間化することを想定し、以下4点の要望に対応する**非同期実行＋状態管理**の仕組みを
追加したい。

1. 処理状況を管理する仕組み: 実行リクエストを送ったら即座に「処理をトラッキングするためのキー」
   （タスクID等）を返す。
2. ポーリングによる状況確認: bash はそのキーを使って定期的にポーリングし、処理状況（実行中・完了・
   失敗等）を確認できる。
3. 完了後の結果取得: 処理が完了したら、bash はそのキーを使って処理結果を取得できる。
4. 複数の入力ファイル・複数の出力ファイルを渡すための仕様: タスク実行時にローカルパス上の複数の
   入力ファイルパスを指定でき、処理結果として複数の出力ファイルパスを指定（または返却）できる。
   ファイルはローカルパス内に存在することを前提としてよい（リモート転送・アップロード機構は不要）。

なお、上記キーを用いて実行・ポーリング・結果取得を行う bash クライアントスクリプトの作成自体は
後続の実装工程の対象であり、本書では「bash から見てどう使うか」という運用イメージに触れる程度に留める。

### 既存実装の調査結果（制約条件）

#### 既存の同期API（`com.example.demo.task` パッケージ）の構成

| クラス | 役割 |
| :-- | :-- |
| `TaskExecutionController` | `POST /internal/tasks/execute` を公開。バリデーション済みリクエストを受け取り `TaskExecutionService.execute(...)` を**同期呼び出し**し、戻り値をそのままレスポンスに変換する。 |
| `TaskExecutionService` | 業務処理本体。`taskName` に対応する処理を `Map<String, String> parameters` を受けて実行し、`TaskExecutionResult`（`message` のみを持つレコード）を返す。既知タスク名のハードコード判定（`sample-task` / `task-business-failure` / `task-unexpected-error`）と、それに対応する正常終了・業務エラー・想定外例外の3パターンをデモ実装している。 |
| `TaskExecutionRequest` / `TaskExecutionResponse` | リクエスト/レスポンスDTO。`taskName`（`@NotBlank @Size(max=100)`）と任意の `parameters: Map<String,String>` のみを持つ。**複数ファイルパスを表現するフィールドは存在しない**。 |
| `TaskExecutionException` / `TaskExecutionErrorCode` | 業務的な失敗を表す例外。`TASK_NOT_FOUND` / `TASK_EXECUTION_FAILED` の2種別。 |
| `LocalhostOnlyInterceptor` + `InternalApiWebConfig` | `/internal/**` パスのみを対象に、リクエスト元IPが `127.0.0.1`/`::1` 以外の場合は `403 Forbidden` を返す `HandlerInterceptor`。既存・将来の業務用画面・API（Thymeleafベース）には適用されない。 |
| `GlobalExceptionHandler` + `ErrorResponse` | `@RestControllerAdvice` による例外の統一的なエラーレスポンス化。`status="ERROR"`, `errorCode`, `message`, `timestamp` の形式。 |

- 処理は **Controller → Service の呼び出しスレッド内で完結**しており、非同期実行・状態保持の仕組みは
  一切存在しない。
- アクセス制御（`/internal/**` 限定・localhost限定）の方針は本機能でも前提として維持する
  （ユーザー要望には言及がないが、既存方式を踏襲することで一貫性を保つ）。

#### ビルド構成・依存ライブラリ（`build.gradle`）

| 項目 | 内容 |
| :-- | :-- |
| Spring Boot バージョン | 4.0.7 |
| Java バージョン | 25（toolchain） |
| Web | `spring-boot-starter-webmvc`（Spring MVC、Servlet系。`@Async` 等の非同期サポートは標準で利用可能） |
| Bean Validation | `spring-boot-starter-validation` 導入済み |
| 永続化 | `mybatis-spring-boot-starter:4.0.1` が依存に**含まれている**。`runtimeOnly 'org.postgresql:postgresql'`（本番用ドライバ）、`testRuntimeOnly 'com.h2database:h2'`（テスト用、PostgreSQL互換モードで利用、`87a8f78` で設定済み）。 |
| Spring Batch | **未導入**。`spring-boot-starter-batch` 等の依存は `build.gradle` に存在せず、`JobLauncher` / `Job` / `Step` 等の実装例もリポジトリ内に皆無。プロジェクト名は `spring-boot-batch` だが、現時点ではバッチ機能は実体としてまだ存在しない。 |
| 非同期実行 | `@Async` / `@EnableAsync` / `ExecutorService` / `ThreadPoolTaskExecutor` 等の利用例はリポジトリ内に**皆無**（`grep` で非検出）。 |
| Lombok | `compileOnly`/`annotationProcessor` で導入済み。 |

MyBatisの依存は `build.gradle` に存在するものの、現時点では以下の通り**実体（マッパー・エンティティ・
マイグレーション・テーブル定義）は一切存在しない**。

- `src/main/java` 配下に `@Mapper` インタフェース、Entity/DTOクラスは存在しない。
- マイグレーションツール（Flyway/Liquibase）も未導入で、テーブル定義SQLそのものが存在しない。
- `application.properties` には `spring.datasource.*` 等の接続設定も未記載（`spring.application.name=demo`
  のみ）。

つまり「DB依存は `build.gradle` 上は用意されているが、実際にDBへ接続して何かを永続化する処理は
このリポジトリにまだ1つも実装されていない」状態である。DB永続化方式を採用する場合、テーブル設計・
マイグレーション・マッパー実装・接続設定（`application.properties` または `application-test.properties`
等）をゼロから追加する必要がある。

#### ドキュメント体系・工程上の制約

- `docs/service-bash-invocation/02_design.md` は承認済み・実装済みであり、変更不要な既存資産として
  扱う（本機能は新規ドキュメント `docs/async-task-tracking/` 配下に方式検討〜テスト計画を作成する）。
- `.claude/rules/docs-structure.md` に従い、本書では「背景・課題」「選択肢比較」「採用方式と理由」を
  記載し、詳細設計（API仕様・クラス構成・状態遷移等の具体）は次工程の `02_design.md` に委ねる。

### 制約条件のまとめ

1. **アプリプロセスは単一インスタンス・単一JVMでの稼働を前提**とする（既存の同期API同様、デモ用途・
   開発者本人による手元操作が前提であり、複数インスタンスへの負荷分散・クラスタリングは現時点の
   要件にない）。
2. bash の実行元は Spring Boot プロセスと同一ホストであるという既存の前提（
   `docs/service-bash-invocation/02_design.md` 0.1）を維持する。アクセス制御も `/internal/**` パス限定の
   localhost限定方式を踏襲する。
3. タスクの処理時間が長くなることを想定するため、**HTTPリクエストを受けたスレッドで処理をブロックして
   はならない**。リクエストを受けたら即座にレスポンス（トラッキングキー）を返す必要がある。
4. 処理状況（実行中・完了・失敗等）をキー単位で後から問い合わせ可能な形で保持する必要がある。
5. アプリケーション再起動時に実行中タスクの状態を保持する必要があるかどうかは要望に明記がないため、
   **「デモ用途・開発者本人による手元操作」という既存の前提を踏襲し、再起動時に状態が消えることを
   許容する**という前提を置く（許容できない場合は永続化方式を検討する必要があり、後述の選択肢比較で
   この前提を踏まえたトレードオフを示す）。
6. 複数の入力/出力ファイルパスを扱うが、**ファイルの転送・アップロード機構は不要**（ローカルパス上に
   存在することを前提にできる）。

## 検討した方式の選択肢

ユーザー要望の論点に従い、(1)非同期実行方式、(2)状態管理方式、(3)複数ファイルパスの受け渡し方法、
(4)既存同期APIとの関係、(5)トラッキングキー生成方式、(6)ポーリングAPI設計の方向性を横断的に検討した
上で、**全体構成の選択肢として3案**にまとめて比較する（個別論点はそれぞれの案の説明中で扱う）。

---

### 選択肢A: インメモリ状態管理（`ConcurrentHashMap`）＋ `@Async`/`ExecutorService` による非同期実行

`TaskExecutionService` の処理を別スレッドで実行し、実行状況・結果を `ConcurrentHashMap<String, TaskRecord>`
（または同等のスレッドセーフなインメモリストア）でJVM内に保持する。トラッキングキーは `UUID.randomUUID()`
で生成する。新規エンドポイント（例: `POST /internal/tasks/execute-async`）でキーを即時返却し、
`GET /internal/tasks/{taskId}/status` でポーリング、`GET /internal/tasks/{taskId}/result` で結果取得する。

**非同期実行の実現方法**

- `@EnableAsync` + `@Async` を付与した非同期実行用メソッド（戻り値 `void` または `CompletableFuture<...>`）
  で実行し、専用の `ThreadPoolTaskExecutor`（`TaskExecutionAsyncConfig` 等で明示的にスレッドプールサイズ・
  キュー長を設定）を介して実行する。
- もしくは `@Async` を使わず、Controller/Service層で明示的に `ExecutorService`（`Executors.newFixedThreadPool`
  等をBean登録）に `Runnable`/`Callable` を `submit` し、戻り値の `Future`/`CompletableFuture` を介さず、
  実行結果は状態Mapへの書き込みで連携する方式でも実現できる。
- いずれも Spring MVC（Servlet）の標準的な非同期処理パターンであり、`spring-boot-starter-webmvc` に
  標準で同梱される機能のみで実現でき、新規ライブラリ追加は不要。

**状態管理方法**

- `TaskId(UUID) -> TaskRecord(status, inputFiles, outputFiles, message, errorCode, createdAt, updatedAt)`
  という構造を `ConcurrentHashMap` で保持するリポジトリ相当のBean（例: `TaskExecutionStateStore`）を
  シングルトンで管理する。
- ステータスは `PENDING -> RUNNING -> SUCCEEDED|FAILED` のような状態遷移を持つ enum とする。
- アプリケーション再起動時は状態が消える（インメモリのため）。「制約条件5」で許容することとした前提と
  整合する。

**複数入力/出力ファイルの受け渡し**

- リクエストJSONに `inputFilePaths: string[]` を追加する（`TaskExecutionRequest` の拡張、または専用の
  非同期リクエストDTOを新設）。各要素はローカルファイルシステム上の絶対パス文字列。
- 出力ファイルパスは2方式が考えられる。
  - (i) Service が出力先パスを自律的に決定し、結果取得時のレスポンスJSON
    （`outputFilePaths: string[]`）で返す（リクエスト側で指定不要）。
  - (ii) リクエスト側で出力先ディレクトリ・ファイル名を指定し、Serviceはそこに書き込む。
  - 本選択肢では (i) を基本としつつ (ii) も同じDTO拡張のみで対応可能であり、設計フェーズで具体的な
    JSON構造を確定する（方式自体の優劣に影響しないため、ここでは選択肢を狭めない）。
- ファイルの存在検証（入力ファイルが存在するか）は非同期実行開始前 or 実行中のいずれかで行う。

**メリット**

- 追加ライブラリが不要（`spring-boot-starter-webmvc` の標準機能のみ）。`build.gradle` の変更が不要。
- 実装がシンプルで見通しが良い。既存の `TaskExecutionService` をほぼそのまま再利用し、呼び出し方法
  （同期→非同期）と結果の受け渡し方法（戻り値→状態ストアへの書き込み）だけを変更すればよい。
- レイテンシが低い（DBアクセスやSpring Batchのメタデータ管理のオーバーヘッドがない）。デモ用途・
  単一インスタンスという制約条件と合致する。
- 既存の同期API（`/internal/tasks/execute`）に変更を加える必要がなく、**非破壊で追加**できる
  （後述「既存同期APIとの関係」参照）。

**デメリット・リスク**

- アプリケーション再起動・クラッシュで実行中・完了済みタスクの状態がすべて消える。「制約条件5」で
  許容する前提を置いているが、将来「再起動後も状態を引き継ぎたい」という要件が出た場合は再度の方式
  変更（DB永続化への切り替え）が必要になる。
- 複数インスタンス構成（水平スケール）には対応できない（インスタンスごとに状態が分離するため）。
  ただし制約条件1で単一インスタンス前提としているため、現時点では問題にならない。
- 完了済みタスクの状態をいつまで保持するか（メモリリーク防止のための削除・TTL）を別途設計する必要が
  ある（小さいが実装すべき項目として残る）。

**実装コスト・既存コードへの影響**

- 低。新規パッケージ追加が中心で、既存の `TaskExecutionController`/`TaskExecutionService` は変更不要
  （新規エンドポイント・新規Serviceクラスとして追加するため）。
- `build.gradle` の変更は不要。

---

### 選択肢B: PostgreSQL（MyBatis）への永続化＋ `@Async`/`ExecutorService` による非同期実行

状態管理をインメモリではなく、本リポジトリが依存に持つ MyBatis + PostgreSQL で永続化する。非同期実行
方式自体は選択肢Aと同じ（`@Async`/`ExecutorService`）。

**状態管理方法**

- `task_execution` テーブル（`task_id UUID PRIMARY KEY`, `status`, `input_file_paths`（JSON or 関連テーブル）,
  `output_file_paths`, `message`, `error_code`, `created_at`, `updated_at` 等）を新規に設計する。
- マイグレーション機構（Flyway/Liquibase）が未導入のため、テーブル作成SQLの管理方法も新規に決める
  必要がある（`schema.sql` の自動実行、または新規にFlyway等を導入するか）。
- MyBatisの `@Mapper` インタフェース・XMLマッパー（または Mapper アノテーションベース）をゼロから
  実装する。

**メリット**

- アプリケーション再起動後も状態が保持される（永続化の恩恵）。
- 複数インスタンス構成への拡張に対しても、DBを共有することで状態の一貫性を保ちやすい
  （将来のスケールアウトを見据える場合に有利）。
- 本プロジェクトの依存（MyBatis, PostgreSQL）を活用でき、「使われていない依存を活かす」という側面は
  ある。

**デメリット・リスク**

- 現時点でDB接続設定（`spring.datasource.*`）・マイグレーション機構・マッパー実装のいずれもゼロから
  追加する必要がある。「制約条件」で確認した通り、本リポジトリには現状DBへ接続する実装が1つも
  存在しないため、本機能のためだけにDB基盤全体（接続設定、テーブル管理方式、テスト用H2設定の整合）を
  立ち上げることになり、影響範囲・実装コストが大きい。
- テスト時も `application-test.properties` 相当の設定やH2のPostgreSQL互換モードでのテーブル作成
  （`87a8f78` のコミットで導入されたテスト方式を流用するにしても、テーブルDDLの管理が新規に必要）
  の準備が必要になる。
- 「制約条件5」で述べた通り、再起動時に状態が消えてよいという前提を置いている以上、永続化の恩恵
  （再起動後も状態が残る）は今回の要件に対して**過剰な対応**である可能性が高い。
- ステータス更新（`PENDING -> RUNNING -> SUCCEEDED/FAILED`）の都度DB書き込みが発生し、インメモリ方式
  に比べてレイテンシ・実装量が増える。ポーリングの都度SELECTが発生する点も同様。
- ファイルパスの配列（複数入力・複数出力）をリレーショナルDBで表現する場合、JSON型カラムを使うか
  子テーブル（`task_input_file`, `task_output_file`）に正規化するかの設計判断が追加で必要になる。

**実装コスト・既存コードへの影響**

- 高。DB接続設定の新規追加、マイグレーション方式の決定、テーブル設計、MyBatisマッパー実装、
  テスト環境（H2）でのテーブル作成方法の整備が必要。既存の `application.properties` にも
  `spring.datasource.*` 等の追加が必要になり、影響範囲が`com.example.demo.task`パッケージ内に
  収まらない。

---

### 選択肢C: Spring Batch（`JobLauncher` 非同期実行）の導入

`spring-boot-starter-batch` を新規に依存追加し、タスク実行を Spring Batch の `Job`/`Step` として
モデル化する。`JobLauncher`（`TaskExecutorJobLauncher` 等）を非同期 `TaskExecutor` 付きで構成し、
`run(job, jobParameters)` の戻り値である `JobExecution` の `id`（`executionId`）をトラッキングキーとして
利用する。状況確認は `JobExplorer`/`JobRepository` 経由で `JobExecution` のステータス（`STARTING`,
`STARTED`, `COMPLETED`, `FAILED` 等のBatchStatus）を問い合わせる。

**メリット**

- 本プロジェクト名が `spring-boot-batch` であることと方向性が一致する（将来的にバッチ処理基盤として
  正式に Spring Batch を導入するのであれば、本機能はその最初の足がかりになりうる）。
- `JobRepository` による実行履歴・状態管理が標準機能として提供され、自前で状態遷移やステータス保存の
  仕組みを実装する必要がない。再起動後の状態引き継ぎも `JobRepository` の永続化方式（DBベース）次第で
  対応できる。
- 複数ファイルの入出力は `Step`/`ItemReader`/`ItemWriter` や `JobParameters` として自然にモデル化できる
  （Spring Batchの標準的な使い方の範囲内）。

**デメリット・リスク**

- `spring-boot-starter-batch` は本リポジトリに**現時点で一切導入されていない**新規依存である。
  `JobRepository` は標準でリレーショナルDBにメタデータテーブル（`BATCH_JOB_INSTANCE` 等、十数個の
  テーブル）を必要とするため、選択肢Bで述べたDB基盤整備の課題（接続設定・マイグレーション）が
  **同様に、かつより大規模に**発生する（Spring Batch標準のスキーマ初期化に依存するための設定や、
  H2/PostgreSQL双方でのスキーマ整合性確認も必要）。
- 既存の `TaskExecutionService` の処理内容（デモ用途の単純な処理）に対して、`Job`/`Step`/`ItemReader`/
  `ItemWriter`/`Chunk` 等のSpring Batchの抽象化を導入するのは、現状の処理の単純さに対して**過剰な
  抽象化**であり、学習コスト・実装コストが大きい。
- 「今回の要望」はあくまで「1つのタスクを非同期実行し状態をトラッキングする」ことであり、Spring Batch
  が本来解決する「大量データのチャンク処理・リスタート可能性・ステップ分割」等の課題には対応していない。
  オーバーエンジニアリングのリスクが高い。
- `JobLauncher` を非同期実行する設定（`TaskExecutorJobLauncher` + 非同期 `TaskExecutor` の組み合わせ）
  自体も追加の設定・検証が必要。

**実装コスト・既存コードへの影響**

- 高〜非常に高。新規依存追加、Batchメタデータテーブル用のDB基盤整備（選択肢Bの課題を内包）、
  Job/Step/Reader/Writerの実装、既存`TaskExecutionService`のロジックをBatchのモデルに合わせて
  再構成する必要がある。
