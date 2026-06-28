#!/usr/bin/env bash
#
# 非同期タスク実行API（POST /internal/tasks/execute-async）にタスクを登録し、
# 完了（SUCCEEDED/FAILED）まで GET /internal/tasks/{taskId} をポーリングして結果を取得する。
#
# 依存コマンド: curl, jq
#
# 使い方:
#   scripts/run-async-task.sh -t <taskName> [-i <inputFilePath>]... [-p <key=value>]...
#                              [-b <baseUrl>] [--interval <秒>] [--timeout <秒>]
#
# 例:
#   # 入力ファイルなし、パラメータなし
#   scripts/run-async-task.sh -t sample-task
#
#   # 入力ファイル複数・パラメータ複数を指定
#   scripts/run-async-task.sh -t sample-task \
#     -i /data/in1.csv -i /data/in2.csv \
#     -p key1=value1 -p key2=value2
#
#   # 接続先・ポーリング間隔・タイムアウトを変更
#   scripts/run-async-task.sh -t sample-task -b http://127.0.0.1:8080 --interval 2 --timeout 300
#
# 終了コード:
#   0: タスクが SUCCEEDED で完了
#   1: 引数エラー・APIエラー（HTTPエラー応答）・ポーリングタイムアウト
#   2: タスクが FAILED で完了（HTTP的には正常応答だが、業務処理としては失敗）

set -euo pipefail

BASE_URL="http://127.0.0.1:8080"
TASK_NAME=""
INPUT_FILE_PATHS=()
PARAMS=()
POLL_INTERVAL_SECONDS=2
TIMEOUT_SECONDS=300

usage() {
    sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        -t|--task)
            TASK_NAME="$2"
            shift 2
            ;;
        -i|--input)
            INPUT_FILE_PATHS+=("$2")
            shift 2
            ;;
        -p|--param)
            PARAMS+=("$2")
            shift 2
            ;;
        -b|--base-url)
            BASE_URL="$2"
            shift 2
            ;;
        --interval)
            POLL_INTERVAL_SECONDS="$2"
            shift 2
            ;;
        --timeout)
            TIMEOUT_SECONDS="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [ -z "$TASK_NAME" ]; then
    echo "error: -t/--task is required" >&2
    usage >&2
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "error: curl is required but not found in PATH" >&2
    exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
    echo "error: jq is required but not found in PATH" >&2
    exit 1
fi

# inputFilePaths（JSON配列）を組み立てる。未指定時は空配列。
input_file_paths_json="[]"
for path in "${INPUT_FILE_PATHS[@]:-}"; do
    [ -z "$path" ] && continue
    input_file_paths_json=$(jq -c --arg p "$path" '. + [$p]' <<<"$input_file_paths_json")
done

# parameters（JSONオブジェクト、key=value形式）を組み立てる。未指定時は空オブジェクト。
parameters_json="{}"
for kv in "${PARAMS[@]:-}"; do
    [ -z "$kv" ] && continue
    key="${kv%%=*}"
    value="${kv#*=}"
    if [ "$key" = "$kv" ]; then
        echo "error: -p/--param must be in key=value form: $kv" >&2
        exit 1
    fi
    parameters_json=$(jq -c --arg k "$key" --arg v "$value" '. + {($k): $v}' <<<"$parameters_json")
done

request_body=$(jq -n \
    --arg taskName "$TASK_NAME" \
    --argjson parameters "$parameters_json" \
    --argjson inputFilePaths "$input_file_paths_json" \
    '{taskName: $taskName, parameters: $parameters, inputFilePaths: $inputFilePaths}')

echo "submitting task: $TASK_NAME" >&2

submit_response=$(curl -s -w 'HTTPSTATUS:%{http_code}' -X POST "${BASE_URL}/internal/tasks/execute-async" \
    -H "Content-Type: application/json" \
    -d "$request_body")
submit_status=$(echo "$submit_response" | tr -d '\n' | sed -e 's/.*HTTPSTATUS://')
submit_body=$(echo "$submit_response" | sed -e 's/HTTPSTATUS\:[0-9]*$//')

if [ "$submit_status" != "202" ]; then
    echo "error: task submission failed (HTTP $submit_status)" >&2
    echo "$submit_body" >&2
    exit 1
fi

task_id=$(jq -r '.taskId' <<<"$submit_body")
echo "task accepted: taskId=$task_id" >&2

elapsed=0
status="PENDING"
status_body=""
while [ "$elapsed" -lt "$TIMEOUT_SECONDS" ]; do
    status_response=$(curl -s -w 'HTTPSTATUS:%{http_code}' "${BASE_URL}/internal/tasks/${task_id}")
    status_code=$(echo "$status_response" | tr -d '\n' | sed -e 's/.*HTTPSTATUS://')
    status_body=$(echo "$status_response" | sed -e 's/HTTPSTATUS\:[0-9]*$//')

    if [ "$status_code" != "200" ]; then
        echo "error: status check failed (HTTP $status_code)" >&2
        echo "$status_body" >&2
        exit 1
    fi

    status=$(jq -r '.status' <<<"$status_body")
    echo "taskId=$task_id status=$status (elapsed ${elapsed}s)" >&2

    if [ "$status" = "SUCCEEDED" ] || [ "$status" = "FAILED" ]; then
        break
    fi

    sleep "$POLL_INTERVAL_SECONDS"
    elapsed=$((elapsed + POLL_INTERVAL_SECONDS))
done

if [ "$status" != "SUCCEEDED" ] && [ "$status" != "FAILED" ]; then
    echo "error: polling timed out after ${TIMEOUT_SECONDS}s (taskId=$task_id, last status=$status)" >&2
    exit 1
fi

echo "$status_body"

if [ "$status" = "FAILED" ]; then
    exit 2
fi
exit 0
