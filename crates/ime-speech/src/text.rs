//! Conservative transcript cleanup shared by ASR providers.

/// Normalizes whitespace and common spoken punctuation without changing words.
#[must_use]
pub fn normalize_transcript(input: &str, language: Option<&str>) -> String {
    let compact = input.split_whitespace().collect::<Vec<_>>().join(" ");
    if language.is_some_and(|value| value.starts_with("zh")) {
        compact
            .replace(" 逗号", "，")
            .replace("逗号 ", "，")
            .replace(" 句号", "。")
            .replace("句号 ", "。")
            .replace(" 问号", "？")
            .replace("问号 ", "？")
            .replace(' ', "")
    } else {
        compact
            .replace(" comma", ",")
            .replace(" period", ".")
            .replace(" question mark", "?")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_chinese_spoken_punctuation() {
        assert_eq!(
            normalize_transcript("你好 逗号 世界 句号", Some("zh-CN")),
            "你好，世界。"
        );
    }

    #[test]
    fn keeps_english_words_and_compacts_spacing() {
        assert_eq!(
            normalize_transcript("hello   world comma next", Some("en")),
            "hello world, next"
        );
    }
}
