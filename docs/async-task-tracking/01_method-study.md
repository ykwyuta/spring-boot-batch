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

## 比較表

| 観点 | A: インメモリ＋`@Async`/`ExecutorService` | B: MyBatis/PostgreSQL永続化＋`@Async` | C: Spring Batch `JobLauncher` |
| :-- | :-- | :-- | :-- |
| 追加依存ライブラリ | 不要（既存の`spring-boot-starter-webmvc`のみ） | 不要（既存依存を使うが、マイグレーション機構等の周辺整備が新規に必要） | 必要（`spring-boot-starter-batch`）かつメタデータDB基盤の整備必須 |
| 再起動時の状態保持 | 保持されない（許容する前提と合致） | 保持される（今回は過剰） | 保持される（DB依存、Bの課題を内包） |
| 実装コスト | 低 | 高（DB接続設定・マイグレーション・マッパー実装をゼロから整備） | 高〜非常に高（Bの課題＋Batch特有の抽象化学習・実装） |
| 既存コードとの整合性 | 高（既存`TaskExecutionService`をほぼそのまま再利用） | 中（Service層は再利用できるが、新規にDB層を丸ごと追加） | 低（既存Serviceのロジックをジョブ/ステップに再構成） |
| 既存同期APIへの影響 | 影響なし（非破壊で追加可能） | 影響なし（同上） | 影響なし（同上）だが、追加負荷が大きい |
| 複数入出力ファイルの表現 | リクエスト/レスポンスDTOへの配列フィールド追加のみで対応可 | 同様に対応可だが、DBスキーマ設計（JSON列か子テーブルか）が追加で必要 | `JobParameters`/`ItemReader`/`ItemWriter`としてモデル化可能だが学習コストが高い |
| 単一インスタンス前提との整合性 | 整合する（制約条件1・5と一致） | 永続化の恩恵が活きるのは複数インスタンス・再起動要件がある場合 | 同上（Batchの本来の強みはチャンク処理・リスタート耐性であり、今回の規模には不釣り合い） |
| プロジェクト名（`spring-boot-batch`）との方向性 | 直接の関連はない | 直接の関連はない | 名称上は親和性があるが、現時点の要件規模には過剰 |

## 個別論点の検討結果

選択肢比較の結果（採用方式は「採用方式と理由」で確定）を踏まえ、ユーザー要望に挙げられた個別論点に
ついて、本書での結論（次工程の詳細設計で具体化する内容の方向性）を以下にまとめる。

### トラッキングキーの生成方式

- `UUID.randomUUID()`（`java.util.UUID`、追加ライブラリ不要）を採用する。連番方式（DBのシーケンスや
  `AtomicLong`）は推測されやすく、また選択肢Aのインメモリ管理では永続的な連番管理自体が不要であるため
  不採用とする。UUIDは衝突確率が無視できるほど低く、`ConcurrentHashMap` のキーとしてそのまま使える。

### ポーリング用APIの形

- 既存の `/internal/tasks/execute`（同期API）と対比できるよう、非同期版は新規パスに切り出す
  （詳細は次項「既存同期APIとの関係」）。
- ポーリング・結果取得用のエンドポイントは、トラッキングキー（タスクID）をパス変数として持つ
  RESTfulな形を基本方針とする（例: `GET /internal/tasks/{taskId}/status`、結果取得は同一リソースの
  別表現として `GET /internal/tasks/{taskId}` または `GET /internal/tasks/{taskId}/result` のいずれかに
  まとめるかは次工程の詳細設計で確定する）。状況確認と結果取得を1本のGETに統合するか分けるかは
  API設計上のスタイルの選択であり、本書の採用方式決定には影響しないため、設計フェーズに委ねる。

### 複数入力・出力ファイルパスの受け渡し方法

- リクエストJSON内にファイルパスの配列フィールド（`inputFilePaths: string[]`）を持たせる方式を
  基本方針とする。検討した代替案は以下の通りだが、いずれも本機能の前提（ファイルはローカルパス上に
  存在してよい、転送機構は不要）の下では不採用とする。
  - **multipart/form-data によるファイルアップロード**: ファイル本体を転送する仕組みであり、
    「ファイルはローカルパスに存在することを前提としてよい」という制約条件6と矛盾する
    （アップロードの必要がない）。bash側の実装も複雑になる（`curl -F` での複数ファイル送信）。
  - **入力ファイルパスをカンマ区切り文字列等でクエリパラメータに含める**: 配列をJSON配列として
    表現する方がパース・バリデーション（`@NotEmpty List<String>` 等）の両面で素直であり、
    クエリパラメータでの表現は不要に複雑化するため不採用。
  - 結論として、JSONボディの配列フィールドが最もシンプルで、既存の `TaskExecutionRequest`
    （`@Valid` によるBean Validation方式）と一貫した実装パターンになる。
- 出力ファイルパスは、処理が完了した時点で結果取得APIのレスポンスJSON内の配列フィールド
  （`outputFilePaths: string[]`）として返却する方式を基本方針とする。出力先をリクエスト時に
  クライアント側で指定させるか、サーバー側（Service）が決定するかは、タスクの性質（既存の
  `TaskExecutionService` の拡張内容）に依存するため、次工程の詳細設計で確定する。いずれの場合も
  DTOへのフィールド追加のみで対応できる点で、選択肢A・B・Cの優劣には影響しない。

### 既存の同期エンドポイント（`/internal/tasks/execute`）との関係

- **既存の同期APIは変更せず残し、非同期版を別エンドポイント（別パス）として追加する。**
- 理由:
  - 既存の同期APIは `docs/service-bash-invocation/` で方式検討・設計・実装・テストの全工程を経て
    承認済み・実装済みであり、軽量な処理であれば同期的に即時結果を得られる利点がある
    （ポーリングのオーバーヘッドが不要）。今回の要望は「時間がかかる処理を想定した拡張」であり、
    既存の同期APIを置き換える必要性がユーザー要望にも明記されていない。
  - 既存実装（`TaskExecutionController`/`TaskExecutionService`/関連テスト22ケース）に変更を加えると、
    承認済みの設計・テスト計画の再レビューが必要になり、影響範囲・手戻りコストが大きい。
  - 新規エンドポイントとして追加することで、既存資産への影響をゼロにしつつ、bash側は処理の特性
    （短時間で終わるか・長時間かかるか）に応じて同期/非同期のどちらのAPIを使うか選択できる
    （将来的な使い分けの余地を残せる）。
  - 非同期実行の中核処理（タスクの実体処理）自体は、既存の `TaskExecutionService.execute(...)` を
    そのまま呼び出す（再利用する）ことで、ロジックの重複を避ける。新規に追加するのは「非同期に
    呼び出すラッパー」「状態管理」「ポーリング/結果取得用のController」であり、既存Serviceの
    シグネチャ変更は不要（複数ファイルパスを扱うために `parameters` 拡張や新規メソッド追加が必要に
    なる場合は、既存メソッドの後方互換を保つかオーバーロードで対応する方針とし、詳細は次工程の
    設計で確定する）。

## 採用方式と理由

**選択肢A「インメモリ状態管理（`ConcurrentHashMap`）＋ `@Async`/`ExecutorService` による非同期実行」を
採用する。** 既存の同期エンドポイント（`/internal/tasks/execute`）は変更せず残し、非同期版は新規の
`/internal/tasks/execute-async` 系エンドポイント（パス名は次工程の詳細設計で確定）として追加する。

採用理由は以下の通り。

1. **既存資産・既存制約条件との整合性が最も高い**: 本リポジトリは現時点でDBへ接続する実装
   （マッパー・エンティティ・マイグレーション・接続設定）を一切持たない「デモ用途・単一インスタンス」
   のアプリケーションである（制約条件1・2、調査結果参照）。選択肢Aはこの現状にそのまま適合し、
   既存の `TaskExecutionService` を変更せず再利用できる。選択肢B・Cは、今回の要望の本質（非同期化＋
   状態トラッキング）とは別の大きな課題（DB基盤整備、Spring Batch導入）を同時に解決することを
   要求し、スコープが肥大化する。
2. **再起動時に状態が消えてよいという前提（制約条件5）と合致する**: 既存の同期API設計
   （`docs/service-bash-invocation/02_design.md` 0.2）も「デモ用途・開発者本人による手元操作」を
   前提としており、本機能もその前提を継承するのが整合的である。この前提の下では、選択肢B・Cが
   提供する「再起動後も状態が残る」という利点は実質的に活用されず、コストに対して見合わない。
3. **実装コストが最小かつ既存コードへの影響が最小**: 新規ライブラリの追加が不要（`build.gradle`
   変更不要）であり、既存の `TaskExecutionController`/`TaskExecutionService`/`LocalhostOnlyInterceptor`/
   `GlobalExceptionHandler` 等の既存資産（アクセス制御・例外ハンドリングの仕組み）をそのまま転用できる。
   新規追加するクラス（状態ストア、非同期実行用Service、ポーリング/結果取得用Controller、DTO）は
   `com.example.demo.task` パッケージ内に閉じた変更で済み、承認済みの既存設計・テストへの再修正は
   不要である。
4. **パフォーマンス上の優位性**: ステータス更新・参照が `ConcurrentHashMap` へのメモリ操作のみで完結し、
   DBアクセス（選択肢B）やSpring Batchのメタデータ管理（選択肢C）に伴うI/Oオーバーヘッドが発生しない。
   ポーリングの頻度が高くなることが想定される運用（bashからの定期ポーリング）において、応答性の面でも
   有利である。
5. **将来の拡張性を閉じない**: 状態管理を担うコンポーネント（状態ストア）をインタフェースとして
   設計しておけば（次工程の詳細設計で具体化）、将来「再起動後も状態を残したい」「複数インスタンスに
   スケールしたい」という要件が生じた場合に、実装をDB永続化（選択肢B）に切り替える余地を残せる。
   現時点でその要件がない以上、先行してDB基盤・Spring Batchを導入するのはYAGNI
   （You Aren't Gonna Need It）の観点からも避けるべきと判断した。

選択肢B（MyBatis/PostgreSQL永続化）は、本リポジトリにDB接続実装が皆無である現状を踏まえると、
本機能のためだけにDB基盤全体（接続設定・マイグレーション方式・マッパー実装・テスト環境整備）を
新規に立ち上げることになり、再起動時の状態保持が不要という前提（制約条件5）の下では明らかに
コストに対して効果が見合わないため見送る。

選択肢C（Spring Batch）は、プロジェクト名（`spring-boot-batch`）との方向性の一致という心理的な
親和性はあるものの、(a) 選択肢Bと同様のDB基盤整備コストを内包し、かつ (b) `Job`/`Step`/`ItemReader`/
`ItemWriter` 等の抽象化が今回の「単純なタスクを非同期実行し状態をトラッキングする」という要件規模に
対して過剰であり、オーバーエンジニアリングのリスクが高いため見送る。将来、本格的なバッチ処理基盤
（チャンク処理、リスタート耐性、ジョブスケジューリング等）が必要になった時点で、改めてSpring Batchの
導入を独立した方式検討として行うべきである。

以上より、次工程の詳細設計（`02_design.md`）では、選択肢Aを前提として以下を具体化する。

- 状態ストア（`ConcurrentHashMap` ベース）のクラス設計、状態遷移（`PENDING`/`RUNNING`/`SUCCEEDED`/
  `FAILED` 等）の定義。
- `@Async`（`@EnableAsync` + 専用 `ThreadPoolTaskExecutor`）または明示的な `ExecutorService` の
  どちらを採用するかの確定と、そのスレッドプール設定方針。
- 新規エンドポイント（非同期実行・ステータス確認・結果取得）のパス・リクエスト/レスポンスDTO・
  HTTPステータスの詳細仕様。
- 複数入力/出力ファイルパスのDTOフィールド定義、入力ファイル存在検証のタイミング・エラー表現。
- 完了済みタスクの状態の保持期間・削除方針（メモリリーク防止）。
- 既存の `TaskExecutionService` との連携方法（再利用するメソッドシグネチャ、必要な拡張点）。
- アクセス制御（`LocalhostOnlyInterceptor`）を新規エンドポイントにも適用すること（`/internal/**`
  配下に配置することで自動適用される）の確認。

## 参考: bash から見た運用イメージ（概要のみ）

採用方式（選択肢A）における bash クライアントスクリプトの利用イメージは以下のようになる想定である
（詳細なスクリプト実装は後続の実装工程の対象であり、ここでは方式検討の妥当性を補足する参考情報として
記載する）。

```bash
# 1. 非同期実行をリクエストし、トラッキングキー（taskId）を取得する
TASK_ID=$(curl -s -X POST http://127.0.0.1:8080/internal/tasks/execute-async \
  -H "Content-Type: application/json" \
  -d '{"taskName": "sample-task", "inputFilePaths": ["/data/in1.csv", "/data/in2.csv"]}' \
  | jq -r '.taskId')

# 2. ポーリングで状況確認（実行中はRUNNING、完了したらSUCCEEDED/FAILED）
while :; do
  STATUS=$(curl -s http://127.0.0.1:8080/internal/tasks/${TASK_ID}/status | jq -r '.status')
  [ "$STATUS" = "SUCCEEDED" ] || [ "$STATUS" = "FAILED" ] && break
  sleep 2
done

# 3. 完了後、結果（出力ファイルパス等）を取得する
curl -s http://127.0.0.1:8080/internal/tasks/${TASK_ID}/result
```

上記はあくまでイメージであり、エンドポイントの正確なパス・レスポンス形式（`taskId`/`status`等のJSON
キー名を含む）は次工程の `02_design.md` で確定する。本書での結論は「キーの取得→ポーリング→結果取得」
という3段階のフローを選択肢Aの構成（新規エンドポイント3本）で自然に実現できる、という点である。
