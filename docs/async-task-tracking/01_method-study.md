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
