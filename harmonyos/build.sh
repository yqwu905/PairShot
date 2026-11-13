#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
if [[ -f /workspace/.harmony-environment/env.sh && -z "${DEVECO_HOME:-}" ]]; then
  source /workspace/.harmony-environment/env.sh
fi
if [[ -n "${DEVECO_HOME:-}" ]]; then
  export PATH="$DEVECO_HOME/bin:$DEVECO_HOME/tool/node/bin:$PATH"
fi
mkdir -p ../work/harmony/hvigor ../work/harmony/npm-cache
export HVIGOR_USER_HOME="${HVIGOR_USER_HOME:-$(cd ../work/harmony/hvigor && pwd)}"
export npm_config_cache="${npm_config_cache:-$(cd ../work/harmony/npm-cache && pwd)}"
if ! command -v hvigorw >/dev/null 2>&1; then
  echo '请安装 DevEco Command Line Tools 5.1.0，并将其 bin 目录添加到 PATH，或设置 DEVECO_HOME。' >&2
  exit 1
fi
hvigorw --mode module -p product=default -p module=entry@default -p buildMode=debug assembleHap --no-daemon "$@"
