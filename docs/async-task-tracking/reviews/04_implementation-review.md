日時: 2026-06-29 00:00:00
対象ドキュメント: 実装フェーズの成果物一式（プロダクションコード・テストコード・bashスクリプト）
  - src/main/java/com/example/demo/task/async/ 配下一式
    （AsyncTaskExecutionController, AsyncTaskExecutionService, AsyncTaskExecutionStateStore,
    AsyncTaskRecord, AsyncTaskStatus, AsyncTaskProperties, AsyncTaskRetentionScheduler,
    config/AsyncTaskExecutorConfig）
  - src/main/java/com/example/demo/task/dto/AsyncTaskExecutionRequest.java,
    AsyncTaskExecutionAcceptedResponse.java, AsyncTaskStatusResponse.java
  - src/main/java/com/example/demo/task/exception/AsyncTaskNotFoundException.java,
    AsyncInputFileNotFoundException.java, GlobalExceptionHandler.java（追加分3メソッド）
  - src/main/resources/application.properties（新規4キー追加分）
  - scripts/run-async-task.sh
  - src/test/java/com/example/demo/task/async/AsyncTaskExecutionStateStoreTest.java（SS-01〜04）
  - src/test/java/com/example/demo/task/async/AsyncTaskRetentionSchedulerTest.java（RT-01〜04）
  - src/test/java/com/example/demo/task/async/AsyncTaskExecutionServiceAcceptTest.java（AS-01〜07）
  - src/test/java/com/example/demo/task/async/AsyncTaskExecutionServiceExecuteAsyncTest.java（AE-01〜07）
  - src/test/java/com/example/demo/task/async/AsyncTaskExecutionServiceGetStatusTest.java（GS-01〜02）
  - src/test/java/com/example/demo/task/async/AsyncTaskExecutionControllerTest.java
    （AC-00〜12, GC-01〜02, GS-03〜06）
  - src/test/java/com/example/demo/task/exception/GlobalExceptionHandlerAsyncTaskTest.java（EH-A01〜04）
  - src/test/java/com/example/demo/task/async/AsyncTaskTrackingIntegrationTest.java（IG-01〜04）

  参照元（前工程の承認済みドキュメント）:
  - docs/async-task-tracking/01_method-study.md
  - docs/async-task-tracking/02_design.md（コミット4cceba5で承認）
  - docs/async-task-tracking/03_test-plan.md（コミット42cd992で承認）

レビュー観点:
  1. 設計書（02_design.md）との整合性（クラス構成・インターフェース・エラーハンドリング方針）
  2. テスト計画（03_test-plan.md）の全ケースID（SS/RT/AS/AE/GS/AC/GC/EH-A/IG、必須51+任意IG-04）の
     カバレッジ（ID単位の漏れ確認）
  3. bashスクリプトが方式検討・設計の仕様（トラッキングキー受け取り→ポーリング→結果取得、
     複数入力/出力ファイルの受け渡し）を満たしているか
  4. コード品質（命名、エラーハンドリング、Spring Boot/Java作法）

  注記（制約）: 本レビューはサンドボックス環境にJava 25ツールチェイン・Gradleテスト依存JARが
  存在しないため、`./gradlew compileJava`/`test`によるコンパイル・実行確認は行っていない。
  目視でのコード照合（メソッド名・シグネチャ・importパスの整合性、既存クラスとの一致確認）に
  基づくレビューである。

指摘事項:
  - （確認事項として記録、対応不要）`AsyncTaskExecutionStateStore#update`の実装は、設計書「3.1」の
    擬似コードにはない`current == null`時の`IllegalStateException`スローを追加している
    （存在しないtaskIdに対しては`compute`がレコードを生成してしまう事故を防ぐ防御的実装）。
    設計書の分岐表（9節）にもテスト計画にも対応するケースはないが、これは設計の方針
    （「既存レコードが存在することが既知の場合に限定する」という利用契約をJavadocで明記）の
    範囲内の補強であり、設計と矛盾するものではない。C1網羅の対象分岐としても元々存在しない
    （update呼び出し前に必ずsave済みであることが全呼び出し経路で保証されている）ため、
    テストケース追加の要求はしない。
  - （軽微・対応不要）`AsyncTaskExecutionService`の`acceptAsyncExecution`は、`self.executeAsync(...)`
    （`@Lazy`注入された自己プロキシ経由の呼び出し）を直接try-catchして`RejectedExecutionException`
    を捕捉している。設計書「4.3」では「ディスパッチ呼び出し（Executor.execute相当）が
    呼び出し元スレッドでRejectedExecutionExceptionをスローし、それを呼び出し元に伝播させる」と
    説明されていたが、これはSpringの`AsyncExecutionInterceptor`の標準的な動作
    （`@Async`メソッド呼び出し自体がExecutorへの`submit`を呼び出し元スレッドで行うため、
    戻り値が`void`の場合は`RejectedExecutionException`がそのまま呼び出し元へ同期的に伝播する）と
    一致しており、設計意図を正しく実装している。AS-07のテスト（`self`をMockitoでモック化し
    `executeAsync`呼び出し時に直接例外をスローさせる手法）も、テスト計画の指示通り
    「ディスパッチ呼び出し自体が例外をスローするか否か」のみを検証する設計であり妥当。
  - 上記2点はいずれも実装の問題ではなく、レビュー時に設計との対応関係を明確化するための記録で
    あり、修正は不要と判断する。

  ID単位のテスト計画カバレッジ確認結果（全て確認済み、漏れなし）:
  - SS-01〜SS-04（4件）: AsyncTaskExecutionStateStoreTest.javaに実装。find/update/removeの
    挙動が設計書3.1と一致。
  - RT-01〜RT-04（4件、境界値含む）: AsyncTaskRetentionSchedulerTest.javaに実装。
    `isBefore(threshold)`判定（D1/D2/D3および等値境界）を正しく検証。
  - AS-01〜AS-07（7件）: AsyncTaskExecutionServiceAcceptTest.javaに実装。null/空リスト/
    ファイル存在検証・ディレクトリ判定・ディスパッチ成功/飽和（PENDINGレコードのrollback含む）を
    全て検証。
  - AE-01〜AE-07（7件、AE-08はIG-02で実体化）: AsyncTaskExecutionServiceExecuteAsyncTest.javaに
    実装。RUNNING遷移・正常終了・TASK_NOT_FOUND・TASK_EXECUTION_FAILED・想定外例外・
    出力ファイルパス決定（正常系/異常系）を全て検証。
  - GS-01〜GS-02（2件）: AsyncTaskExecutionServiceGetStatusTest.javaに実装。
  - AC-00〜AC-12（13件）, GC-01〜GC-02（2件）, GS-03〜GS-06（4件）: 合計19件、
    AsyncTaskExecutionControllerTest.javaに実装。バリデーション全分岐・正常系レスポンス組み立て・
    例外伝播・ステータス別レスポンス組み立て（FAILED時に200のままでerrorCodeフィールドが
    存在しないことの確認含む）を網羅。
  - EH-A01〜EH-A04（4件）: GlobalExceptionHandlerAsyncTaskTest.javaに実装。新規3ハンドラの
    レスポンス組み立てと、既存汎用ハンドラにフォールバックしないことの確認を実施。
  - IG-01〜IG-04（4件、IG-04は任意）: AsyncTaskTrackingIntegrationTest.javaに実装。
    `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate`でLocalhostOnlyInterceptor
    の自動適用確認・同期API422 vs 非同期API200+FAILEDの差異確認・正常系E2E・入力ファイル不存在/
    未知taskIdの404確認を実施。任意ケースのIG-04も実装済みで、必須51件を上回るカバレッジ。
  - テストメソッド数の実測（grep `@Test`カウント）: SS(4)+RT(4)+AS(7)+AE(7)+GS(2)+
    Controller集約(19=AC13+GC2+GS4)+EH-A(4)+IG(4) = 51件。テスト計画「9. ケース数まとめ」の
    必須51件と一致し、IG-04（任意）も実装済みのため計画上の合計52件相当を満たす。

  設計書との整合性確認結果:
  - パッケージ構成・クラス名（AsyncTaskExecutionController/Service/StateStore/Record/Status/
    Properties/RetentionScheduler/config.AsyncTaskExecutorConfig）は設計書「2」の構成と完全一致。
  - 状態遷移（PENDING→RUNNING→SUCCEEDED|FAILED、T1〜T4）はAsyncTaskRecordのwithRunning/
    withSucceeded/withFailedで設計書「3.2」「3.3」通りに実装。
  - エンドポイント構成（POST /internal/tasks/execute-async で202、GET /internal/tasks/{taskId}で
    統合された状態/結果取得）は設計書「5.1」「5.6」のメソッドシグネチャと一致
    （`executeAsync`/`getStatus`のパス・戻り値型・ステータスコードまで一致）。
  - 入力ファイル検証（Files.exists/isRegularFile、taskId発行・PENDING登録前に実施）は設計書
    「5.3」「6.1」の手順4a/4bと一致。出力ファイルパス決定ロジック（sample-task時のみ
    `/tmp/async-tasks/{taskId}/result.txt`）は設計書「5.4」のデモ実装と一致。
  - GlobalExceptionHandlerは既存4メソッドを変更せず新規3メソッドのみ追加しており、設計書「8.1」
    「11」の方針と一致。既存のTaskExecutionController/Service/DTO/例外/Interceptor/Configは
    本実装フェーズのコミット範囲で一切変更されていないことをgit diffで確認済み
    （設計書「11. 既存コードへの影響範囲」の「変更なし」記載との整合）。
  - application.propertiesの新規4キー（async-task.executor.*、async-task.retention-minutes）は
    設計書「10」の記載値（2/4/50/30）と一致。`@ConfigurationProperties`バインド方式
    （AsyncTaskProperties、コンパクトコンストラクタでデフォルト値補完）も設計意図
    （キー欠落時も起動時エラーにならない）を満たす。
  - `@Lazy AsyncTaskExecutionService self`による自己プロキシ経由の`@Async`呼び出しは、設計書には
    明記がない実装上の工夫（Spring AOPの自己呼び出し制約への対処）だが、Spring標準パターンとして
    妥当であり、設計の意図（`@Async`メソッド呼び出しが実際に別スレッドで実行されること）を正しく
    実現する。Javadocコメントで理由も明記されており、後続の保守者にも分かりやすい。

  bashスクリプト（scripts/run-async-task.sh）の確認結果:
  - トラッキングキー受け取り（`POST /internal/tasks/execute-async`のレスポンスから`jq -r '.taskId'`
    で取得）→ポーリング（`GET /internal/tasks/${task_id}`を`--interval`間隔でループ、
    SUCCEEDED/FAILEDで停止、`--timeout`でタイムアウト検知）→結果取得（最終的な状態レスポンス本文を
    標準出力に出力）という3段階のフローを満たしており、01_method-study.mdの運用イメージおよび
    02_design.mdの最終決定（1エンドポイント統合）と整合する。
  - 複数入力ファイル（`-i`オプションの複数指定をjqでJSON配列に組み立てる）・複数パラメータ
    （`-p key=value`の複数指定をJSONオブジェクトに組み立てる）に対応しており、方式検討の要望4
    （複数入力ファイル）を満たす。出力ファイルパスはAPIレスポンスの`outputFilePaths`配列に
    含まれる形でそのまま標準出力に出るため、複数出力ファイルの受け渡しも自然にサポートされる
    （bash側で追加のパース処理は要求していないが、`jq`で後続処理が可能な形式であり要件を満たす）。
  - 終了コードの設計（0: SUCCEEDED、1: 引数エラー/APIエラー/タイムアウト、2: FAILED）が
    スクリプト先頭のコメントとコード本体で一致しており、bashの呼び出し元（cron等）が判定しやすい。
  - `set -euo pipefail`によるエラー検知、`curl`/`jq`の存在確認など、シェルスクリプトの基本的な
    防御的実装が行われている。

  コード品質の確認結果:
  - 命名規則は設計書「2」で指定された`AsyncTaskXxx`/`AsyncTaskExecutionXxx`プレフィックスに
    統一されており、既存の`TaskExecutionXxx`命名規則と区別できる。
  - 例外クラス（AsyncTaskNotFoundException/AsyncInputFileNotFoundException）は単純な
    `RuntimeException`継承で、既存の`TaskExecutionException`（errorCode保持型）とは異なる
    シンプルな設計が設計書「8.2」の記載通りに実装されている。
  - ControllerはDTO変換のみを担い業務ロジックを持たない、Service層は既存`TaskExecutionService`を
    変更せず呼び出すのみ、という既存の責務分担方針を踏襲している。
  - Javadocコメントが各クラス・メソッドに設計書の章番号を参照する形で付与されており、
    設計とコードの対応関係が追跡しやすい。
  - テストコードも既存の`TaskExecutionControllerTest`等と同じimportパス・アノテーション
    （`@WebMvcTest`、`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`、
    `@MockitoBean`）を使用しており、Spring Boot 4.0系・既存プロジェクトの作法と一貫している。

対応: 指摘事項はいずれも実装の修正を要求するものではなく、設計との対応関係を確認・記録した
  ものである。再修正は不要と判断する。

結論: 承認
