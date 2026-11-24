#!/bin/bash
set -euo pipefail
transaction="$JOBPILOT_UPDATE_DIR"
target="$JOBPILOT_UPDATE_TARGET"
version="$JOBPILOT_UPDATE_VERSION"
parent_pid="$JOBPILOT_UPDATE_PID"
port="$JOBPILOT_UPDATE_PORT"
parent=$(dirname "$target")
backup="$parent/.JobPilot-backup-$(date +%s)-$$.app"
mount="$transaction/mount"
staging=""
new_pid=""
mounted=0
moved=0
parent_exited=0
success=0
status() {
  printf '{"state":"%s","version":"%s","message":"%s"}\n' "$1" "$version" "$2" > "$transaction/status.tmp"
  mv "$transaction/status.tmp" "$transaction/status.json"
}
result() {
  cp "$transaction/status.json" "$transaction/../result.tmp"
  mv "$transaction/../result.tmp" "$transaction/../result.json"
}
args=()
while IFS= read -r -d '' argument; do args+=("$argument"); done < "$transaction/args.bin"
restart() {
  if [[ "${args[0]+set}" == set ]]; then
    "$target/Contents/MacOS/JobPilot" "${args[@]}" >> "$transaction/restarted.log" 2>&1 &
  else
    "$target/Contents/MacOS/JobPilot" >> "$transaction/restarted.log" 2>&1 &
  fi
  new_pid=$!
}
cleanup() {
  trap - EXIT
  if [[ "$success" == 0 ]]; then
    if [[ -n "$new_pid" ]]; then kill -TERM "$new_pid" 2>/dev/null || true; fi
    if [[ "$moved" == 1 ]]; then
      if [[ -d "$target" ]]; then mv "$target" "$staging/failed.app"; fi
      mv "$backup" "$target"
      restart
    elif [[ "$parent_exited" == 1 ]]; then
      restart
    fi
    status FAILED '本次更新未完成，已保留或恢复原程序，可稍后重试'
    result
  fi
  if [[ "$mounted" == 1 ]]; then hdiutil detach "$mount" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
mkdir "$mount"
hdiutil attach -readonly -nobrowse -mountpoint "$mount" "$transaction/package.dmg" >/dev/null
mounted=1
source="$mount/JobPilot.app"
[[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$source/Contents/Info.plist")" == "$version" ]]
codesign --verify --deep "$source"
staging=$(mktemp -d "$parent/.JobPilot-update.XXXXXX")
ditto "$source" "$staging/JobPilot.app"
codesign --verify --deep "$staging/JobPilot.app"
status READY '安装包已准备完成'
for ((i=0;i<120;i++)); do
  kill -0 "$parent_pid" 2>/dev/null || break
  sleep 1
done
if kill -0 "$parent_pid" 2>/dev/null; then exit 1; fi
parent_exited=1
mv "$target" "$backup"
moved=1
mv "$staging/JobPilot.app" "$target"
restart
for ((i=0;i<60;i++)); do
  if curl -fsS --max-time 2 --noproxy '*' "http://127.0.0.1:$port/api/health" 2>/dev/null |
       grep -Fq "\"version\":\"$version\""; then
    status DONE '更新完成，软件已重新启动'
    result
    success=1
    rmdir "$staging" 2>/dev/null || true
    exit 0
  fi
  kill -0 "$new_pid" 2>/dev/null || break
  sleep 1
done
exit 1
