#!/usr/bin/env bash
# Renders an SVG to a 192x192 PNG with headless Edge: svg2png.sh in.svg out.png
set -euo pipefail
IN=$(cygpath -w "$(realpath "$1")"); OUT=$(cygpath -w "$(realpath -m "$2")")
TMP=$(mktemp -d)
cat > "$TMP/page.html" <<HTML
<html><body style="margin:0;background:transparent"><img src="file:///${IN//\//}" width="192" height="192"></body></html>
HTML
"/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" --headless=new --disable-gpu --hide-scrollbars \
  --default-background-color=00000000 --window-size=192,192 --screenshot="$OUT" "file:///$(cygpath -m "$TMP/page.html")" >/dev/null 2>&1
rm -rf "$TMP"
