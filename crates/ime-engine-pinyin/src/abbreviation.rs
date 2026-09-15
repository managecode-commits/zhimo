// Copyright © 2026 立方田 <managecode@gmail.com>
//! Syllable-aware abbreviation matching. No exponential spelling expansion.
use super::{LexiconEntry, lexicon, normalized_t9_input, t9_signature};
use std::collections::HashMap;
use std::sync::OnceLock;

type Forms = Vec<Vec<String>>;
type Index = HashMap<u8, Vec<(&'static LexiconEntry, Forms)>>;

fn forms(reading: &str, numeric: bool) -> Forms {
    reading.split([' ', '\'']).filter(|s| !s.is_empty()).map(|s| {
        let mut values = vec![s.to_owned(), s.chars().next().unwrap().to_string()];
        if s.starts_with("zh") || s.starts_with("ch") || s.starts_with("sh") {
            values.push(s[..2].to_owned());
        }
        if numeric { values = values.into_iter().map(|v| t9_signature(&v)).collect(); }
        values.sort();
        values.dedup();
        values
    }).collect()
}

fn accepts(forms: &Forms, input: &str) -> bool {
    if forms.len() < 2 || input.len() >= 128 || forms.len() > 32 { return false; }
    // Each frontier is an input position after one complete or abbreviated syllable.
    // Apostrophes are consumed only at syllable boundaries.
    let mut positions = 1u128;
    for alternatives in forms {
        let mut next = 0u128;
        while positions != 0 {
            let offset = positions.trailing_zeros() as usize;
            positions &= positions - 1;
            for code in alternatives {
                if input[offset..].starts_with(code) {
                    let mut end = offset + code.len();
                    if input.as_bytes().get(end) == Some(&b'\'') { end += 1; }
                    next |= 1u128 << end;
                }
            }
        }
        if next == 0 { return false; }
        positions = next;
    }
    positions & (1u128 << input.len()) != 0
}

pub(super) fn matches(reading: &str, input: &str) -> bool {
    let numeric = normalized_t9_input(input).is_some();
    accepts(&forms(reading, numeric), &input.to_ascii_lowercase())
}

pub(super) fn lookup(input: &str) -> Vec<&'static LexiconEntry> {
    static LETTERS: OnceLock<Index> = OnceLock::new();
    static DIGITS: OnceLock<Index> = OnceLock::new();
    if input.len() < 2 || input.len() > 128 { return Vec::new(); }
    let numeric = normalized_t9_input(input).is_some();
    let input = input.to_ascii_lowercase();
    let index = (if numeric { &DIGITS } else { &LETTERS }).get_or_init(|| {
        let mut index = Index::new();
        for entry in lexicon() {
            let codes = forms(&entry.display_pinyin, numeric);
            if codes.len() < 2 { continue; }
            if let Some(first) = codes[0].first().and_then(|s| s.bytes().next()) {
                index.entry(first).or_default().push((entry, codes));
            }
        }
        index
    });
    index.get(&input.as_bytes()[0]).into_iter().flatten()
        .filter(|(_, forms)| accepts(forms, &input))
        .map(|(entry, _)| *entry).collect()
}
