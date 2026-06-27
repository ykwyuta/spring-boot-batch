日時: 2026-06-27 12:00:00
対象ドキュメント: docs/async-task-tracking/02_design.md
レビュー観点: 方式検討（01_method-study.md、選択肢A: インメモリConcurrentHashMap＋
  @Async/ThreadPoolTaskExecutor）との整合性、REST API仕様・エラーハンドリング方針の妥当性、
  後続のテスト計画工程でC1（分岐網羅）を洗い出せる粒度になっているか
指摘事項:
  - （軽微）エンドポイント統合（5.1節、`GET /internal/tasks/{taskId}` に状態確認・結果取得を
    統合）は理由が具体的に説明されており妥当だが、01_method-study.md 末尾の「bash から見た
    運用イメージ」のシェルスクリプト例が `/internal/tasks/${TASK_ID}/status` と
    `/internal/tasks/${TASK_ID}/result` という統合前の2エンドポイント構成のままになっている。
    02_design.md側で決定を上書きしたことは明記されているため実害はないが、方式検討の参考情報と
    設計書の最終決定に齟齬が残っている状態であり、次工程（テスト計画）やさらに後続の実装工程で
    bashクライアントを実装する際に古い情報を参照してしまうリスクがわずかにある。
  - （軽微）9.1節のC1分岐表で、A7（`inputFilePaths`省略）・A8（空配列を明示）・A9（すべて存在する
    通常ファイル）が独立した行として並んでいるが、A7/A8はいずれも「検証対象なしのため検証スキップ
    で処理続行」という同一の挙動になる。テスト計画工程でA7とA8を別ケースとして区別する必要性
    （省略時のデフォルト値生成ロジックの分岐と、空配列が明示された場合のバリデーション通過の分岐は
    実装上経路が異なる可能性がある）を一言補足しておくと、テスト計画工程での「このケース、本当に
    A9と独立したテストが必要か」という判断のブレを防げる。
  - （軽微）`errorCode="TASK_NOT_FOUND"` という文字列が、(a) 既存同期API・`TaskExecutionErrorCode`
    enumの値、(b) 非同期APIの`AsyncTaskNotFoundException`が返すエラーレスポンスの`errorCode`、
    (c) `AsyncTaskRecord.errorCode`フィールドに格納されうる値（B2、`TaskExecutionException`が
    `TASK_NOT_FOUND`で非同期スレッド内でスローされた場合）という3つの異なる文脈で再利用されている。
    6.3節で(a)と(b)の違いは説明されているが、(c)については8.2節・9.2節に記載がありながら、
    6.3節の説明範囲には含まれておらず、3者の関係性を一望できる記述がない。実害は小さい
    （AsyncTaskStatusResponseには5.5節の決定でerrorCode自体を含めないため、(c)はAPI応答には
    現れない内部状態にすぎない）が、テスト計画工程でB2のテストケースを書く際に紛れる可能性がある
    ため、注記を一文追加するとより明確になる。
対応: 上記3点はいずれも設計の方針・結論を変更する必要のある指摘ではなく、テスト計画工程に進む前に
  必須で対応すべき指摘ではないと判断する。次工程（03_test-plan.md）作成時に以下を踏まえて
  テストケースを設計すれば実害なく引き継げる。
  - bashの運用イメージは02_design.md「5.1」「5.5」の最終決定（1エンドポイント統合）に従う
    （01_method-study.mdの参考シェルスクリプトは古い情報として読み替える）。
  - A7/A8は別ケースとして実装し、実装結果が同一の処理パス（検証スキップ）に収束することを
    確認するテストとして書けば問題ない。
  - B2（TaskExecutionService内でのTASK_NOT_FOUND）のテストケースでは、HTTPレスポンスの
    `errorCode`には現れないこと（5.5節の通り）を期待値として明記すればよい。
  再修正は不要と判断する。
結論: 承認

補足（レビューの根拠）:
  - 方式検討との整合性: 01_method-study.mdで採用された選択肢A（`ConcurrentHashMap`による
    インメモリ状態管理＋`@Async`/`ThreadPoolTaskExecutor`による非同期実行）が、02_design.mdの
    2節（パッケージ構成）・3節（状態ストア設計）・4節（`@Async`+専用`ThreadPoolTaskExecutor`の
    確定）でそのまま具体化されている。状態遷移（PENDING/RUNNING/SUCCEEDED/FAILED）、
    UUIDをトラッキングキーとする方針、既存`TaskExecutionService`の再利用、既存同期APIを
    変更せず別エンドポイントとして追加する方針など、方式検討で確定した制約・前提条件
    （単一インスタンス、再起動時の状態消失許容、ファイル転送機構不要）と矛盾する記述はない。
    方式検討が「設計フェーズに委ねる」とした論点（ポーリングAPIの形、出力ファイルパス決定方式、
    保持期間・削除方針）も、それぞれ5.1節・5.4節・7節で具体的な決定とその理由が示されている。
  - インターフェース・エラーハンドリングの妥当性: REST API仕様（5節）はリクエスト/レスポンス
    JSON例・フィールド表・HTTPステータスが明記されており、bashからのポーリングに必要な情報
    （`status`/`taskName`/`message`/`outputFilePaths`/`createdAt`/`updatedAt`）が
    `GET /internal/tasks/{taskId}` のレスポンスに欠落なく含まれている。完了前の結果取得を
    `409 Conflict`ではなく`200 OK`＋ステータス表現にする決定（5.5節）も、理由（エンドポイント統合
    との整合、ポーリング運用のシンプルさ）が具体的に説明されている。エラーハンドリング方針
    （8節）は既存`GlobalExceptionHandler`の実装（`src/main/java/com/example/demo/task/exception/
    GlobalExceptionHandler.java`）を確認した結果、既存4メソッド
    （`handleHttpMessageNotReadableException`/`handleValidationException`/
    `handleTaskExecutionException`/`handleUnexpectedException`）が設計書の記載と一致しており、
    新規3メソッド（`handleAsyncTaskNotFoundException`/`handleAsyncInputFileNotFoundException`/
    `handleRejectedExecutionException`）は`@ExceptionHandler`の型ディスパッチ規則上、既存の
    `Exception.class`汎用ハンドラより優先して呼ばれるため、設計通りに動作する。
  - 既存同期APIとの差異の識別性（指摘事項2）: 8.3節の比較表に「業務的な処理失敗（既存同期APIのみ）」
    の行で、既存同期APIの422と非同期APIの200+status=FAILEDの違いが明示され、表の直後の段落で
    「同期API・非同期APIの設計思想の違いとして本書で明示しておくべき重要なポイント」「後続のテスト
    計画工程で、同期APIとの対比を明示したテストケースを設けることを推奨する」と明記されている。
    次工程のテスト計画でこの差異を重要な検証項目として識別できる十分な粒度・強調がある。
  - C1分岐の完全性・粒度（9節）: A0a〜A15（非同期実行リクエストAPI、16項目）、B1〜B6
    （非同期実行本体、6項目）、C0a〜C8（状態取得API、11項目）、D1〜D3（保持期限スケジューラ、
    3項目）が、既存設計書（docs/service-bash-invocation/02_design.md「4.2」）と同じ表形式
    （分岐箇所／条件／挙動／HTTPステータス）で整理されており、各行が実装時の条件分岐
    （Bean Validation、ファイル存在検証、スレッドプール飽和、状態ストア検索、TTL判定）に
    1対1で対応している。表の末尾に正常系/異常系の分類も明記されており、次工程でテストケースを
    機械的に1行=1ケース以上に対応させやすい。既存実装（`TaskExecutionService.java`、
    `KNOWN_TASK_NAMES`によるtaskName解決ロジック）を確認した結果、B1〜B4の分岐
    （正常終了／TASK_NOT_FOUND／TASK_EXECUTION_FAILED／想定外例外）は既存の3パターン
    （sample-task/task-business-failure/task-unexpected-error）に正確に対応しており、
    実装との不整合はない。
  - 既存クラスへの影響範囲（11節）: 「GlobalExceptionHandlerへの3メソッド追加のみで他は無変更」
    という記載について、`src/main/java/com/example/demo/task/`配下の既存ファイル一覧
    （TaskExecutionController.java/TaskExecutionService.java/TaskExecutionResult.java/
    config/InternalApiWebConfig.java/dto/TaskExecutionRequest.java・TaskExecutionResponse.java/
    exception/ErrorResponse.java・GlobalExceptionHandler.java・TaskExecutionErrorCode.java・
    TaskExecutionException.java/interceptor/LocalhostOnlyInterceptor.java）を実際に確認し、
    設計書11節の記述（各クラス「変更なし」）と矛盾がないことを確認した。既存の
    `TaskExecutionErrorCode`enumに値を追加せず新規例外クラスで表現する方針（6.3節・8.2節）も
    既存enumの実装（TASK_NOT_FOUND/TASK_EXECUTION_FAILEDの2値のみ）と整合しており、
    service-bash-invocation機能への非破壊原則は守られている。
