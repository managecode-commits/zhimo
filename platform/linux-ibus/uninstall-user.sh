#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
component="$data_home/ibus/component/zhimo.xml"
install_root="$data_home/zhimo"

if [[ -f "$component" ]]; then
  mv "$component" "$component.disabled"
fi
if [[ -d "$install_root" ]]; then
  mv "$install_root" "$install_root.disabled"
fi
IBUS_COMPONENT_PATH="${data_home}/ibus/component:/usr/share/ibus/component" ibus write-cache
ibus restart
echo "Zhimo IBus files were disabled as *.disabled and can be recovered manually."
