#!/usr/bin/env bash
# dsh-ctl.sh —— DeepSeek Harness 服务控制（在终端环境内运行）
# 用法: dsh-ctl.sh {start|stop|restart|status|logs}
set -e

DSH_DATA_DIR="${DSH_DATA_DIR:-$HOME/.dsh}"
PID_FILE="$DSH_DATA_DIR/dsh.pid"
LOG_FILE="$DSH_DATA_DIR/dsh.log"
PORT_FILE="$DSH_DATA_DIR/dsh.port"

# 读取引擎环境（install 时写入）
if [ -f "$DSH_DATA_DIR/engine.env" ]; then
  # shellcheck disable=SC1091
  . "$DSH_DATA_DIR/engine.env"
fi
DSH_RUN="${DSH_RUN:-dsh}"
DSH_PORT="${DSH_PORT:-3080}"
WORKSPACE_DIR="${DSH_WORKSPACE:-/storage/emulated/0/dsh-workspace}"

log() { echo "[dsh-ctl] $*"; }

is_running() {
  [ -f "$PID_FILE" ] || return 1
  local pid
  pid=$(cat "$PID_FILE" 2>/dev/null || echo "")
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

health() {
  # 端口探测（curl 或 /dev/tcp 兜底）
  if command -v curl >/dev/null 2>&1; then
    curl -s -o /dev/null -w "%{http_code}" --connect-timeout 2 "http://127.0.0.1:$DSH_PORT" 2>/dev/null
  else
    (echo > "/dev/tcp/127.0.0.1/$DSH_PORT") >/dev/null 2>&1 && echo "200" || echo "000"
  fi
}

start() {
  if is_running; then
    log "already running (pid $(cat "$PID_FILE"))"
    return 0
  fi
  mkdir -p "$DSH_DATA_DIR" "$WORKSPACE_DIR"
  log "starting dsh on port $DSH_PORT (cwd=$WORKSPACE_DIR)..."
  # 在 workspace 目录启动，dsh 默认以该目录作为文件系统位置
  cd "$WORKSPACE_DIR"
  # shellcheck disable=SC2086
  nohup bash -c "cd '$WORKSPACE_DIR' && $DSH_RUN web --port $DSH_PORT" >"$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "$DSH_PORT" > "$PORT_FILE"
  # 等待健康检查（最多 30s）
  local i=0
  while [ $i -lt 30 ]; do
    if is_running; then
      local code
      code=$(health)
      if [ "$code" = "200" ] || [ "$code" = "000" ] && [ "$code" != "000" ]; then
        log "dsh up (http://127.0.0.1:$DSH_PORT)"
        return 0
      fi
      # 000 且进程活着 → 可能尚未就绪，继续等
      if [ "$code" != "000" ]; then
        log "dsh responding ($code)"
        return 0
      fi
    fi
    sleep 1
    i=$((i + 1))
  done
  log "started but not healthy yet; check logs: $LOG_FILE"
}

stop() {
  if ! is_running; then
    log "not running"
    rm -f "$PID_FILE"
    return 0
  fi
  local pid
  pid=$(cat "$PID_FILE")
  log "stopping pid $pid..."
  kill "$pid" 2>/dev/null || true
  # 等进程退出
  local i=0
  while kill -0 "$pid" 2>/dev/null && [ $i -lt 10 ]; do
    sleep 1
    i=$((i + 1))
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  log "stopped"
}

status() {
  if is_running; then
    local code
    code=$(health)
    echo "running pid=$(cat "$PID_FILE") port=$DSH_PORT http=$code"
  else
    echo "stopped"
  fi
}

logs() {
  if [ -f "$LOG_FILE" ]; then
    tail -n "${2:-100}" "$LOG_FILE"
  else
    echo "no log file"
  fi
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) logs ;;
  *) echo "usage: $0 {start|stop|restart|status|logs}"; exit 1 ;;
esac