#!/usr/bin/env bash
# dsh-install.sh —— 在终端环境（Termux/PROOT）内安装 DeepSeek Harness
#
# 假设已经处于终端环境中（$PREFIX 已就绪，bash 可用）。
# 两种引擎模式：
#   MODE=npm   : npm 全局安装 @deepseek-ai/dsh（官方包，更新一条命令）
#   MODE=src   : git clone 源码 + pnpm build（追最新 commit，更新要重新构建）
# 通过环境变量 DSH_MODE 或第一个参数传入。
set -e

DSH_MODE="${DSH_MODE:-${1:-npm}}"
DSH_DIR="${DSH_DIR:-$HOME/dsh}"
DSH_DATA_DIR="${DSH_DATA_DIR:-$HOME/.dsh}"
WORKSPACE_DIR="${DSH_WORKSPACE:-/storage/emulated/0/dsh-workspace}"
DSH_PORT="${DSH_PORT:-3080}"

log() { echo "[dsh-install] $*"; }

# 1. 基础依赖：node / pnpm / git / curl
if ! command -v node >/dev/null 2>&1; then
  log "installing nodejs via pkg..."
  pkg install -y nodejs 2>/dev/null || apt install -y nodejs
fi
if ! command -v git >/dev/null 2>&1; then
  log "installing git..."
  pkg install -y git 2>/dev/null || apt install -y git
fi
if ! command -v pnpm >/dev/null 2>&1; then
  log "installing pnpm..."
  pkg install -y pnpm 2>/dev/null || npm install -g pnpm
fi
if ! command -v curl >/dev/null 2>&1; then
  pkg install -y curl 2>/dev/null || true
fi

mkdir -p "$DSH_DATA_DIR" "$WORKSPACE_DIR"

# 2. 安装 dsh 本体
if [ "$DSH_MODE" = "src" ]; then
  if [ ! -d "$DSH_DIR/.git" ]; then
    log "cloning deepseek-harness (source mode)..."
    git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git "$DSH_DIR"
  else
    log "repo exists, pulling latest..."
    git -C "$DSH_DIR" pull --ff-only || true
  fi
  log "building dsh from source (this may take a while)..."
  cd "$DSH_DIR"
  pnpm install --frozen-lockfile=false 2>/dev/null || pnpm install
  pnpm run build
  DSH_RUN="cd $DSH_DIR && pnpm dsh"
else
  # 优先安装 App 内置的 dsh（版本固定、离线可用），不存在才走网络
  if [ -f "$DSH_DATA_DIR/vendor/dsh.tgz" ]; then
    log "installing bundled @deepseek-ai/dsh (from vendor/dsh.tgz)..."
    npm install -g "$DSH_DATA_DIR/vendor/dsh.tgz"
  else
    log "installing @deepseek-ai/dsh via npm (latest)..."
    npm install -g @deepseek-ai/dsh
  fi
  DSH_RUN="dsh"
fi

echo "DSH_RUN=$DSH_RUN" > "$DSH_DATA_DIR/engine.env"
echo "DSH_MODE=$DSH_MODE" >> "$DSH_DATA_DIR/engine.env"
echo "DSH_DIR=$DSH_DIR" >> "$DSH_DATA_DIR/engine.env"
echo "DSH_WORKSPACE=$WORKSPACE_DIR" >> "$DSH_DATA_DIR/engine.env"
echo "DSH_PORT=$DSH_PORT" >> "$DSH_DATA_DIR/engine.env"

log "install done. mode=$DSH_MODE"
log "workspace: $WORKSPACE_DIR"
log "start with: dsh-ctl.sh start"