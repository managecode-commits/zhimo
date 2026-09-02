//! Unicode boundary helpers for runtime graphemes and UTF-16 platform APIs.

use unicode_segmentation::UnicodeSegmentation;

#[must_use]
pub fn grapheme_count(text: &str) -> usize {
    text.graphemes(true).count()
}

#[must_use]
pub fn utf16_offset_for_grapheme(text: &str, grapheme_index: usize) -> usize {
    text.graphemes(true)
        .take(grapheme_index)
        .map(|grapheme| grapheme.encode_utf16().count())
        .sum()
}

/// Maps a possibly mid-surrogate offset to the preceding grapheme boundary.
#[must_use]
pub fn grapheme_index_for_utf16_offset(text: &str, utf16_offset: usize) -> usize {
    let mut consumed = 0;
    for (index, grapheme) in text.graphemes(true).enumerate() {
        let next = consumed + grapheme.encode_utf16().count();
        if next > utf16_offset {
            return index;
        }
        consumed = next;
    }
    grapheme_count(text)
}

pub fn remove_last_grapheme(text: &mut String) -> bool {
    let Some((index, _)) = text.grapheme_indices(true).next_back() else {
        return false;
    };
    text.truncate(index);
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn treats_combining_marks_and_emoji_sequences_as_single_units() {
        let value = "e\u{301}👨‍👩‍👧‍👦";
        assert_eq!(grapheme_count(value), 2);
        assert_eq!(utf16_offset_for_grapheme(value, 1), 2);
        assert_eq!(grapheme_index_for_utf16_offset(value, 2), 1);
    }

    #[test]
    fn removes_a_complete_grapheme_cluster() {
        let mut value = "Ae\u{301}".to_owned();
        assert!(remove_last_grapheme(&mut value));
        assert_eq!(value, "A");
    }
}
