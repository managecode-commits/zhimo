#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
install_root="${XDG_DATA_HOME:-$HOME/.local/share}/zhimo"
component_dir="${XDG_DATA_HOME:-$HOME/.local/share}/ibus/component"
binary_dir="${XDG_DATA_HOME:-$HOME/.local/share}/zhimo/bin"

if ! command -v cargo >/dev/null 2>&1 && [[ -x "${HOME}/.cargo/bin/cargo" ]]; then
  export PATH="${HOME}/.cargo/bin:$PATH"
fi
command -v cargo >/dev/null 2>&1 || {
  echo "cargo is required to build the shared Runtime" >&2
  exit 2
}

cd "$repo_dir"
cargo build --release -p ime-ffi

install -d "$install_root/lib" "$binary_dir" "$component_dir"
icon_dir="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/scalable/apps"
install -d "$icon_dir"
install -m 644 assets/branding/zhimo.svg "$icon_dir/zhimo.svg"
install -m 755 target/release/libime_ffi.so "$install_root/lib/libime_ffi.so"
install -m 755 platform/linux-ibus/zhimo_ibus.py "$binary_dir/zhimo-ibus"
install -m 644 platform/linux-ibus/handwriting_panel.py "$binary_dir/handwriting_panel.py"
install -m 644 platform/linux-ibus/desktop_panel.py "$binary_dir/desktop_panel.py"
install -m 644 platform/linux-ibus/desktop_client.py "$binary_dir/desktop_client.py"
if [[ -f target/shared-speech/libzhimo_speech.so ]]; then
  install -m 755 target/shared-speech/libzhimo_speech.so "$install_root/lib/libzhimo_speech.so"
  install -d "$install_root/models/speech"
  cp -R models/speech/. "$install_root/models/speech/"
else
  echo "Speech runtime not built; voice requires platform/shared-speech and pulseaudio-utils." >&2
fi
install -d "$install_root/models"
cp -R models/handwriting "$install_root/models/"

temporary_component="$(mktemp)"
trap 'rm -f "$temporary_component"' EXIT
sed "s|@ZHIMO_IBUS_EXEC@|$binary_dir/zhimo-ibus --ibus|" \
  platform/linux-ibus/zhimo.xml > "$temporary_component"
install -m 644 "$temporary_component" "$component_dir/zhimo.xml"

IBUS_COMPONENT_PATH="$component_dir:/usr/share/ibus/component" ibus write-cache
ibus restart
for _ in {1..10}; do
  ibus address >/dev/null 2>&1 && break
  sleep 0.5
done
echo "Installed Zhimo IBus component for the current user."
