// Copyright © 2026 立方田 <managecode@gmail.com>
//! Bounded dictionary beam search. This is not a contextual language model.
use super::{reading_matches, Candidate, CandidateId, LearningModel, LookupInput};

const BEAM: usize = 12;

#[derive(Clone, Default)]
struct Path {
    text: String,
    reading: String,
    score: f64,
    words: usize,
}

pub(super) fn decode(
    input: &str,
    selected: Option<&str>,
    learning: &LearningModel,
) -> Vec<Candidate> {
    // Bound work on each keystroke; never consume an invalid or incomplete suffix.
    if input.len() < 4
        || input.len() > 64
        || !input.is_ascii()
        || input.starts_with('\'')
        || input.contains("''")
        || !input
            .bytes()
            .all(|c| c.is_ascii_lowercase() || (b'2'..=b'9').contains(&c) || c == b'\'')
    {
        return Vec::new();
    }
    let mut beams = vec![Vec::<Path>::new(); input.len() + 1];
    beams[0].push(Path::default());
    for start in 0..input.len() {
        if beams[start].is_empty() {
            continue;
        }
        prune(&mut beams[start]);
        let paths = beams[start].clone();
        for end in start + 1..=(start + 24).min(input.len()) {
            if input.as_bytes()[end - 1] == b'\'' {
                continue;
            }
            let raw = &input[start..end];
            let Some(lookup) = LookupInput::new(raw) else {
                continue;
            };
            let mut entries = lookup
                .entries()
                .iter()
                .copied()
                .take_while(|entry| lookup.exact(entry))
                .filter(|entry| {
                    reading_matches(entry, raw, if start == 0 { selected } else { None })
                })
                .collect::<Vec<_>>();
            let signature = super::learning_signature(raw);
            entries.sort_by(|a, b| {
                learning
                    .score(&signature, &b.text)
                    .cmp(&learning.score(&signature, &a.text))
                    .then_with(|| b.weight.cmp(&a.weight))
                    .then_with(|| a.text.cmp(&b.text))
            });
            let next = if input.as_bytes().get(end) == Some(&b'\'') {
                end + 1
            } else {
                end
            };
            for entry in entries.into_iter().take(4) {
                let score = f64::from(entry.weight.clamp(1, 1_000_000)).ln() - 16.0
                    + f64::from(
                        u8::try_from(learning.score(&signature, &entry.text).clamp(0, 20))
                            .unwrap_or(20),
                    ) * 0.1;
                for path in &paths {
                    beams[next].push(Path {
                        text: format!("{}{}", path.text, entry.text),
                        reading: if path.reading.is_empty() {
                            entry.display_pinyin.clone()
                        } else {
                            format!("{} {}", path.reading, entry.display_pinyin)
                        },
                        score: path.score + score,
                        words: path.words + 1,
                    });
                }
            }
            if beams[next].len() > BEAM * 8 {
                prune(&mut beams[next]);
            }
        }
    }
    let mut complete = beams.pop().unwrap_or_default();
    prune(&mut complete);
    complete
        .into_iter()
        .filter(|path| path.words > 1)
        .take(5)
        .map(|path| Candidate {
            id: CandidateId(format!("pinyin-sentence:{}", path.text)),
            display_text: path.text.clone(),
            commit_text: path.text,
            language: Some("zh-CN".into()),
            source: "pinyin.reference.sentence".into(),
            score: path.score,
            annotation: Some(path.reading),
            learning_allowed: true,
        })
        .collect()
}

fn prune(paths: &mut Vec<Path>) {
    paths.sort_by(|a, b| {
        b.score
            .total_cmp(&a.score)
            .then_with(|| a.text.cmp(&b.text))
    });
    let mut seen = std::collections::HashSet::new();
    paths.retain(|path| seen.insert(path.text.clone()));
    paths.truncate(BEAM);
}
