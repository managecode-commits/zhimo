#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
revision=0c6861ef7420ee780270ca6d993d18d4101049d0
dictionary_sha256=e341598343a0f0f2035bb1aafc34a7f3bb7887deeecb3f60796262aaa2983e6b
license_sha256=cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30
source_url="https://raw.githubusercontent.com/rime/rime-pinyin-simp/$revision"
asset_dir="$root_dir/platform/android-ime/app/src/main/assets/rime"
fallback_data="$root_dir/crates/ime-engine-pinyin/data/pinyin_simp_lexicon.tsv"
temporary_dir=$(mktemp -d)
trap 'rm -rf "$temporary_dir"' EXIT

curl -fsSL "$source_url/pinyin_simp.dict.yaml" -o "$temporary_dir/pinyin_simp.dict.yaml"
curl -fsSL "$source_url/LICENSE" -o "$temporary_dir/LICENSE-pinyin-simp.txt"
printf '%s  %s\n' "$dictionary_sha256" "$temporary_dir/pinyin_simp.dict.yaml" | sha256sum --check --status
printf '%s  %s\n' "$license_sha256" "$temporary_dir/LICENSE-pinyin-simp.txt" | sha256sum --check --status

install -m 0644 "$temporary_dir/pinyin_simp.dict.yaml" "$asset_dir/pinyin_simp.dict.yaml"
install -m 0644 "$temporary_dir/LICENSE-pinyin-simp.txt" "$asset_dir/LICENSE-pinyin-simp.txt"

# The generic fallback/T9 engine consumes the same pinned vocabulary in a compact TSV form.
# Rime columns are: text, space-delimited pinyin, weight.
awk -F '\t' '
    /^\.\.\.$/ { in_dictionary = 1; next }
    in_dictionary && NF >= 3 && $1 !~ /^#/ {
        print $2 "\t" $1 "\t" $3
    }
' "$temporary_dir/pinyin_simp.dict.yaml" > "$temporary_dir/pinyin_simp_lexicon.tsv"
install -m 0644 "$temporary_dir/pinyin_simp_lexicon.tsv" "$fallback_data"

printf 'Vendored rime-pinyin-simp %s (%s entries).\n' \
    "$revision" "$(wc -l < "$fallback_data")"
