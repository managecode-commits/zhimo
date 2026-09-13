#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
install_root="${XDG_DATA_HOME:-$HOME/.local/share}/shurufa"
component_dir="${XDG_DATA_HOME:-$HOME/.local/share}/ibus/component"
binary_dir="${XDG_DATA_HOME:-$HOME/.local/share}/shurufa/bin"

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
install -m 755 target/release/libime_ffi.so "$install_root/lib/libime_ffi.so"
install -m 755 platform/linux-ibus/shurufa_ibus.py "$binary_dir/shurufa-ibus"
install -m 644 platform/linux-ibus/handwriting_panel.py "$binary_dir/handwriting_panel.py"
install -d "$install_root/models"
cp -R models/handwriting "$install_root/models/"

temporary_component="$(mktemp)"
trap 'rm -f "$temporary_component"' EXIT
sed "s|@SHURUFA_IBUS_EXEC@|$binary_dir/shurufa-ibus --ibus|" \
  platform/linux-ibus/shurufa.xml > "$temporary_component"
install -m 644 "$temporary_component" "$component_dir/shurufa.xml"

IBUS_COMPONENT_PATH="$component_dir:/usr/share/ibus/component" ibus write-cache
ibus restart
for _ in {1..10}; do
  ibus address >/dev/null 2>&1 && break
  sleep 0.5
done
echo "Installed Shurufa IBus component for the current user."
