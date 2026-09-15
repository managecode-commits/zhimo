#!/bin/bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo 'Requires macOS'; exit 2; }
package_dir="$(cd "$(dirname "$0")" && pwd)"
source_app="$package_dir/Zhimo.app"
test -d "$source_app"
test "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$source_app/Contents/Info.plist")" = dev.zhimo.inputmethod
codesign --verify --deep --strict "$source_app"
input_directory="$HOME/Library/Input Methods"
destination="$input_directory/Zhimo.app"
test ! -L "$input_directory" && test ! -L "$destination"
mkdir -p "$input_directory"
stage_directory="$(mktemp -d "$input_directory/.zhimo-install.XXXXXX")"
ditto "$source_app" "$stage_directory/Zhimo.app"
codesign --verify --deep --strict "$stage_directory/Zhimo.app"
backup=""
if test -e "$destination"; then
  test "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$destination/Contents/Info.plist")" = dev.zhimo.inputmethod
  backup_directory="$HOME/Library/Application Support/Zhimo/InstallBackups"
  test ! -L "$backup_directory"
  mkdir -p "$backup_directory"
  backup="$backup_directory/Zhimo-$(date +%Y%m%d-%H%M%S).app"
  test ! -e "$backup"
  mv "$destination" "$backup"
  echo "Old app preserved at $backup"
fi
if ! mv "$stage_directory/Zhimo.app" "$destination"; then
  if test -n "$backup" && test ! -e "$destination"; then mv "$backup" "$destination"; fi
  echo 'Installation failed; inspect the preserved staging directory and backup.' >&2
  exit 1
fi
rmdir "$stage_directory"
echo 'Installed for current user. Save documents, log out/in, then add 知墨 in System Settings → Keyboard → Input Sources.'
echo 'No input source was forced, no editor was terminated, and no personal dictionary was removed.'
