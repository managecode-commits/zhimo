#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
install_root="${XDG_DATA_HOME:-$HOME/.local/share}/shurufa"
component_dir="${XDG_DATA_HOME:-$HOME/.local/share}/ibus/component"
binary_dir="${XDG_DATA_HOME:-$HOME/.local/share}/shurufa/bin"

cd "$repo_dir"
cargo build --release -p ime-ffi

install -d "$install_root/lib" "$binary_dir" "$component_dir"
install -m 755 target/release/libime_ffi.so "$install_root/lib/libime_ffi.so"
install -m 755 platform/linux-ibus/shurufa_ibus.py "$binary_dir/shurufa-ibus"

temporary_component="$(mktemp)"
trap 'rm -f "$temporary_component"' EXIT
sed "s|@SHURUFA_IBUS_EXEC@|env SHURUFA_IME_LIBRARY=$install_root/lib/libime_ffi.so $binary_dir/shurufa-ibus --ibus|" \
  platform/linux-ibus/shurufa.xml > "$temporary_component"
install -m 644 "$temporary_component" "$component_dir/shurufa.xml"

ibus restart
echo "Installed Shurufa IBus component for the current user."
