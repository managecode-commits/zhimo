//! Small, dependency-free Pinyin reference engine.
//!
//! This validates product behavior and the generic engine contract. Production dictionaries
//! and segmentation will be supplied by the isolated librime adapter.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use ime_core::{
    Action, ActionBatch, Candidate, CandidateId, Composition, EngineError, EngineMetadata,
    FeedbackEvent, InputContext, InputEngine, InputEvent, Key, Segment, SegmentState, SessionId,
};
use ime_data::{LearningModel, LearningRecord};

pub struct PinyinEngine {
    sessions: Mutex<HashMap<SessionId, String>>,
    learning: Mutex<LearningModel>,
}

impl Default for PinyinEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl PinyinEngine {
    #[must_use]
    pub fn new() -> Self {
        Self::with_learning(LearningModel::new("learned-lexicon", "zh-CN", "local"))
    }

    #[must_use]
    pub fn with_learning(learning: LearningModel) -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            learning: Mutex::new(learning),
        }
    }

    #[must_use]
    pub fn learning_records(&self) -> Vec<LearningRecord> {
        self.learning
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .records()
    }

    fn lookup(&self, input: &str) -> Vec<Candidate> {
        let Some(lookup) = LookupInput::new(input) else {
            return Vec::new();
        };
        let learning = self
            .learning
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut unique_candidates = HashMap::<String, Candidate>::new();
        for entry in lexicon().iter().filter(|entry| lookup.matches(entry)) {
            let learned_score = learning.score(input, &entry.text);
            let bounded_score = i32::try_from(learned_score).unwrap_or(i32::MAX);
            let exact_match_bonus = if lookup.exact(entry) {
                1_000_000.0
            } else {
                0.0
            };
            let candidate = Candidate {
                id: CandidateId(format!("pinyin:{}", entry.text)),
                display_text: entry.text.clone(),
                commit_text: entry.text.clone(),
                language: Some("zh-CN".to_owned()),
                source: "pinyin.reference".to_owned(),
                score: f64::from(entry.weight)
                    + f64::from(bounded_score) * 10_000.0
                    + exact_match_bonus,
                annotation: Some(entry.display_pinyin.clone()),
                learning_allowed: true,
            };
            let existing = unique_candidates
                .entry(entry.text.clone())
                .or_insert_with(|| candidate.clone());
            if candidate.score > existing.score {
                *existing = candidate;
            }
        }
        let mut candidates = unique_candidates.into_values().collect::<Vec<_>>();
        candidates.sort_by(|left, right| {
            right
                .score
                .total_cmp(&left.score)
                .then_with(|| left.commit_text.cmp(&right.commit_text))
        });
        candidates.truncate(50);
        candidates
    }

    fn composing_actions(&self, input: &str) -> ActionBatch {
        let candidates = self.lookup(input);
        let display_input = if normalized_t9_input(input).is_some() {
            candidates
                .first()
                .and_then(|candidate| candidate.annotation.clone())
                .unwrap_or_else(|| input.to_owned())
        } else {
            input.to_owned()
        };
        let composition = Composition {
            segments: vec![Segment {
                text: display_input,
                state: SegmentState::Composing,
                language: Some("zh-Latn-pinyin".to_owned()),
            }],
            cursor: ime_core::grapheme_count(input),
        };
        ActionBatch(vec![
            Action::UpdateComposition(composition),
            Action::ShowCandidates(candidates),
        ])
    }
}

impl InputEngine for PinyinEngine {
    fn metadata(&self) -> EngineMetadata {
        EngineMetadata {
            id: "pinyin.reference".to_owned(),
            display_name: "Reference Pinyin Engine".to_owned(),
            languages: vec!["zh-CN".to_owned()],
        }
    }

    fn create_session(&self, session: &SessionId) -> Result<(), EngineError> {
        self.sessions
            .lock()
            .map_err(lock_error)?
            .insert(session.clone(), String::new());
        Ok(())
    }

    fn process(
        &self,
        session: &SessionId,
        event: &InputEvent,
        context: &InputContext,
    ) -> Result<ActionBatch, EngineError> {
        let mut sessions = self.sessions.lock().map_err(lock_error)?;
        let input = sessions.get_mut(session).ok_or_else(|| EngineError {
            message: "pinyin session not found".to_owned(),
        })?;
        match event {
            InputEvent::Key(key) if key.pressed => match key.key {
                Key::Character(character)
                    if character.is_ascii_alphabetic()
                        || ('2'..='9').contains(&character)
                        || character == '\'' =>
                {
                    input.push(character.to_ascii_lowercase());
                }
                Key::Backspace => {
                    input.pop();
                }
                Key::Space | Key::Enter => {
                    let input_signature = input.clone();
                    let commit = self
                        .lookup(input)
                        .first()
                        .map_or_else(|| input.clone(), |candidate| candidate.commit_text.clone());
                    input.clear();
                    if context.effective_learning_allowed() {
                        self.learning
                            .lock()
                            .map_err(lock_error)?
                            .selected(&input_signature, &commit);
                    }
                    return Ok(ActionBatch(vec![
                        Action::CommitText(commit),
                        Action::CloseComposition,
                    ]));
                }
                Key::Escape => {
                    input.clear();
                    return Ok(ActionBatch(vec![
                        Action::UpdateComposition(Composition::default()),
                        Action::CloseComposition,
                    ]));
                }
                _ => return Ok(ActionBatch(vec![Action::Ignored])),
            },
            InputEvent::SelectCandidate(id) => {
                let commit =
                    id.0.strip_prefix("pinyin:")
                        .ok_or_else(|| EngineError {
                            message: "candidate does not belong to pinyin engine".to_owned(),
                        })?
                        .to_owned();
                let input_signature = std::mem::take(input);
                if context.effective_learning_allowed() {
                    self.learning
                        .lock()
                        .map_err(lock_error)?
                        .selected(&input_signature, &commit);
                }
                return Ok(ActionBatch(vec![
                    Action::CommitText(commit),
                    Action::CloseComposition,
                ]));
            }
            InputEvent::Reset => {
                input.clear();
                return Ok(ActionBatch(vec![
                    Action::UpdateComposition(Composition::default()),
                    Action::CloseComposition,
                ]));
            }
            InputEvent::Text(value) => input.push_str(&value.to_ascii_lowercase()),
            InputEvent::SpeechPartial(hypothesis) => {
                return Ok(ActionBatch(vec![Action::UpdateComposition(Composition {
                    segments: vec![Segment {
                        text: hypothesis.text.clone(),
                        state: SegmentState::SpeechPartial,
                        language: hypothesis.language.clone(),
                    }],
                    cursor: ime_core::grapheme_count(&hypothesis.text),
                })]));
            }
            InputEvent::SpeechFinal(hypothesis) => {
                input.clear();
                return Ok(ActionBatch(vec![
                    Action::CommitText(hypothesis.text.clone()),
                    Action::CloseComposition,
                ]));
            }
            InputEvent::Key(_) | InputEvent::SwitchEngine(_) => {
                return Ok(ActionBatch(vec![Action::Ignored]));
            }
        }
        Ok(self.composing_actions(input))
    }

    fn apply_feedback(&self, event: &FeedbackEvent) -> Result<(), EngineError> {
        let mut learning = self.learning.lock().map_err(lock_error)?;
        match event.kind {
            ime_core::FeedbackKind::Selected => {
                learning.selected(&event.input_signature, &event.committed_text);
            }
            ime_core::FeedbackKind::Rejected | ime_core::FeedbackKind::Undone => {
                learning.rejected(&event.input_signature, &event.committed_text);
            }
            ime_core::FeedbackKind::Deleted => {
                learning.delete(&event.input_signature, &event.committed_text);
            }
        }
        Ok(())
    }

    fn close_session(&self, session: &SessionId) {
        if let Ok(mut sessions) = self.sessions.lock() {
            sessions.remove(session);
        }
    }
}

fn lock_error<T>(_: std::sync::PoisonError<T>) -> EngineError {
    EngineError {
        message: "pinyin engine state lock poisoned".to_owned(),
    }
}

#[cfg(test)]
fn pinyin_exact_match(pinyin: &str, input: &str) -> bool {
    LookupInput::new(input).is_some_and(|lookup| {
        let entry = LexiconEntry::for_match(pinyin);
        lookup.exact(&entry)
    })
}

fn normalized_pinyin(value: &str) -> String {
    value
        .chars()
        .filter(char::is_ascii_alphabetic)
        .map(|character| character.to_ascii_lowercase())
        .collect()
}

#[derive(Debug)]
struct LexiconEntry {
    pinyin: String,
    t9: String,
    display_pinyin: String,
    text: String,
    weight: i32,
}

impl LexiconEntry {
    #[cfg(test)]
    fn for_match(pinyin: &str) -> Self {
        let normalized = normalized_pinyin(pinyin);
        Self {
            t9: t9_signature(&normalized),
            pinyin: normalized,
            display_pinyin: String::new(),
            text: String::new(),
            weight: 0,
        }
    }
}

enum LookupInput {
    Pinyin(String),
    T9(String),
}

impl LookupInput {
    fn new(input: &str) -> Option<Self> {
        if let Some(digits) = normalized_t9_input(input) {
            return Some(Self::T9(digits));
        }
        let pinyin = normalized_pinyin(input);
        (!pinyin.is_empty()).then_some(Self::Pinyin(pinyin))
    }

    fn matches(&self, entry: &LexiconEntry) -> bool {
        match self {
            Self::Pinyin(input) => entry.pinyin.starts_with(input),
            Self::T9(input) => entry.t9.starts_with(input),
        }
    }

    fn exact(&self, entry: &LexiconEntry) -> bool {
        match self {
            Self::Pinyin(input) => entry.pinyin == *input,
            Self::T9(input) => entry.t9 == *input,
        }
    }
}

fn lexicon() -> &'static [LexiconEntry] {
    static LEXICON: OnceLock<Vec<LexiconEntry>> = OnceLock::new();
    LEXICON.get_or_init(|| {
        let mut entries = HashMap::<(String, String), LexiconEntry>::new();
        for (source, priority_bonus) in [
            (include_str!("../data/pinyin_simp_lexicon.tsv"), 0),
            (include_str!("../data/reference_lexicon.tsv"), 10_000_000),
        ] {
            for line in source
                .lines()
                .filter(|line| !line.is_empty() && !line.starts_with('#'))
            {
                let mut fields = line.split('\t');
                let Some(display_pinyin) = fields
                    .next()
                    .map(str::trim)
                    .filter(|value| !value.is_empty())
                else {
                    continue;
                };
                let Some(text) = fields
                    .next()
                    .map(str::trim)
                    .filter(|value| !value.is_empty())
                else {
                    continue;
                };
                let Some(weight) = fields
                    .next()
                    .and_then(|value| value.trim().parse::<i32>().ok())
                    .and_then(|weight| weight.checked_add(priority_bonus))
                else {
                    continue;
                };
                let pinyin = normalized_pinyin(display_pinyin);
                if pinyin.is_empty() {
                    continue;
                }
                let key = (pinyin.clone(), text.to_owned());
                let candidate = LexiconEntry {
                    t9: t9_signature(&pinyin),
                    pinyin,
                    display_pinyin: display_pinyin.to_owned(),
                    text: text.to_owned(),
                    weight,
                };
                let entry = entries.entry(key).or_insert_with(|| candidate);
                if weight > entry.weight {
                    entry.weight = weight;
                    display_pinyin.clone_into(&mut entry.display_pinyin);
                }
            }
        }
        entries.into_values().collect()
    })
}

fn normalized_t9_input(input: &str) -> Option<String> {
    let is_t9 = !input.is_empty()
        && input
            .chars()
            .all(|character| ('2'..='9').contains(&character) || character == '\'')
        && input
            .chars()
            .any(|character| ('2'..='9').contains(&character));
    is_t9.then(|| {
        input
            .chars()
            .filter(|character| ('2'..='9').contains(character))
            .collect()
    })
}

fn t9_signature(pinyin: &str) -> String {
    pinyin
        .chars()
        .filter_map(|character| match character.to_ascii_lowercase() {
            'a' | 'b' | 'c' => Some('2'),
            'd' | 'e' | 'f' => Some('3'),
            'g' | 'h' | 'i' => Some('4'),
            'j' | 'k' | 'l' => Some('5'),
            'm' | 'n' | 'o' => Some('6'),
            'p' | 'q' | 'r' | 's' => Some('7'),
            't' | 'u' | 'v' => Some('8'),
            'w' | 'x' | 'y' | 'z' => Some('9'),
            _ => None,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nihao_commits_first_chinese_candidate() {
        let engine = PinyinEngine::new();
        let session = SessionId("test".to_owned());
        engine.create_session(&session).expect("session");
        for character in "nihao".chars() {
            engine
                .process(
                    &session,
                    &InputEvent::Key(ime_core::KeyEvent::press(Key::Character(character))),
                    &InputContext::default(),
                )
                .expect("input");
        }
        let actions = engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Space)),
                &InputContext::default(),
            )
            .expect("commit");
        assert_eq!(actions.committed_text(), Some("你好"));
    }

    #[test]
    fn t9_nihao_sequence_exposes_and_commits_chinese_candidate() {
        let engine = PinyinEngine::new();
        let session = SessionId("t9-nihao".to_owned());
        engine.create_session(&session).expect("session");
        let actions = engine
            .process(
                &session,
                &InputEvent::Text("64426".to_owned()),
                &InputContext::default(),
            )
            .expect("t9 input");
        let candidates = actions.0.iter().find_map(|action| match action {
            Action::ShowCandidates(candidates) => Some(candidates),
            _ => None,
        });
        assert_eq!(
            candidates
                .and_then(|items| items.first())
                .map(|item| item.commit_text.as_str()),
            Some("你好")
        );
        assert!(actions.0.iter().any(|action| {
            matches!(action, Action::UpdateComposition(composition) if composition.segments[0].text == "ni hao")
        }));

        let committed = engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Space)),
                &InputContext::default(),
            )
            .expect("commit");
        assert_eq!(committed.committed_text(), Some("你好"));
    }

    #[test]
    fn t9_signature_uses_standard_phone_mapping() {
        assert_eq!(t9_signature("nihao"), "64426");
        assert_eq!(t9_signature("shurufa"), "7487832");
        assert_eq!(t9_signature("zhongwen"), "94664936");
        assert!(pinyin_exact_match("nihao", "64'426"));
    }

    #[test]
    fn unknown_input_falls_back_to_raw_text() {
        let engine = PinyinEngine::new();
        let session = SessionId("fallback".to_owned());
        engine.create_session(&session).expect("session");
        engine
            .process(
                &session,
                &InputEvent::Text("xyz".to_owned()),
                &InputContext::default(),
            )
            .expect("input");
        let actions = engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Enter)),
                &InputContext::default(),
            )
            .expect("commit");
        assert_eq!(actions.committed_text(), Some("xyz"));
    }

    #[test]
    fn invalid_suffix_does_not_match_a_shorter_valid_entry() {
        let engine = PinyinEngine::new();
        let session = SessionId("invalid-suffix".to_owned());
        engine.create_session(&session).expect("session");
        let actions = engine
            .process(
                &session,
                &InputEvent::Text("nihaox".to_owned()),
                &InputContext::default(),
            )
            .expect("input");
        assert!(actions.0.iter().any(
            |action| matches!(action, Action::ShowCandidates(candidates) if candidates.is_empty())
        ));
    }

    #[test]
    fn empty_or_delimiter_only_input_has_no_candidates() {
        let engine = PinyinEngine::new();
        assert!(engine.lookup("").is_empty());
        assert!(engine.lookup("'").is_empty());
    }

    #[test]
    fn apostrophes_delimit_syllables_without_changing_lookup() {
        assert!(pinyin_exact_match("xi an", "xi'an"));
        assert!(pinyin_exact_match("ni hao", "nihao"));
    }

    #[test]
    fn reference_lexicon_covers_common_beta_phrases() {
        for (pinyin, expected) in [
            ("women", "我们"),
            ("zhongguo", "中国"),
            ("beijing", "北京"),
            ("jintian", "今天"),
            ("tianqi", "天气"),
            ("xiexie", "谢谢"),
            ("zaijian", "再见"),
        ] {
            assert_eq!(
                lexicon()
                    .iter()
                    .filter(|entry| pinyin_exact_match(&entry.pinyin, pinyin))
                    .max_by_key(|entry| entry.weight)
                    .map(|entry| entry.text.as_str()),
                Some(expected),
            );
        }
    }

    #[test]
    fn production_lexicon_covers_words_outside_the_beta_fixture() {
        let engine = PinyinEngine::new();
        for (pinyin, expected) in [
            ("putao", "葡萄"),
            ("dianshiju", "电视剧"),
            ("shurufa", "输入法"),
            ("rengongzhineng", "人工智能"),
        ] {
            assert!(
                engine
                    .lookup(pinyin)
                    .iter()
                    .any(|candidate| candidate.commit_text == expected),
                "missing {expected} for {pinyin}",
            );
        }
        assert!(engine
            .lookup("78826")
            .iter()
            .any(|candidate| candidate.commit_text == "葡萄"));
    }

    #[test]
    fn candidate_selection_changes_future_ranking() {
        let engine = PinyinEngine::new();
        let first = SessionId("learn-first".to_owned());
        engine.create_session(&first).expect("first session");
        engine
            .process(
                &first,
                &InputEvent::Text("ni".to_owned()),
                &InputContext::default(),
            )
            .expect("compose");
        engine
            .process(
                &first,
                &InputEvent::SelectCandidate(CandidateId("pinyin:泥".to_owned())),
                &InputContext::default(),
            )
            .expect("select");

        let second = SessionId("learn-second".to_owned());
        engine.create_session(&second).expect("second session");
        let actions = engine
            .process(
                &second,
                &InputEvent::Text("ni".to_owned()),
                &InputContext::default(),
            )
            .expect("compose after learning");
        let candidates = actions.0.iter().find_map(|action| match action {
            Action::ShowCandidates(candidates) => Some(candidates),
            _ => None,
        });
        assert_eq!(
            candidates
                .and_then(|items| items.first())
                .map(|item| item.commit_text.as_str()),
            Some("泥")
        );
    }

    #[test]
    fn password_scope_does_not_change_ranking() {
        let engine = PinyinEngine::new();
        let first = SessionId("private-first".to_owned());
        engine.create_session(&first).expect("first session");
        engine
            .process(
                &first,
                &InputEvent::Text("ni".to_owned()),
                &InputContext::default(),
            )
            .expect("compose");
        let private_context = InputContext {
            scope: ime_core::InputScope::Password,
            ..InputContext::default()
        };
        engine
            .process(
                &first,
                &InputEvent::SelectCandidate(CandidateId("pinyin:泥".to_owned())),
                &private_context,
            )
            .expect("private selection");

        let second = SessionId("private-second".to_owned());
        engine.create_session(&second).expect("second session");
        let actions = engine
            .process(
                &second,
                &InputEvent::Text("ni".to_owned()),
                &InputContext::default(),
            )
            .expect("compose after private input");
        let first_candidate = actions.0.iter().find_map(|action| match action {
            Action::ShowCandidates(candidates) => candidates.first(),
            _ => None,
        });
        assert_eq!(
            first_candidate.map(|candidate| candidate.commit_text.as_str()),
            Some("你")
        );
    }

    #[test]
    fn accepting_the_default_candidate_is_learned() {
        let engine = PinyinEngine::new();
        let session = SessionId("default-learning".to_owned());
        engine.create_session(&session).expect("session");
        for character in "hao".chars() {
            engine
                .process(
                    &session,
                    &InputEvent::Key(ime_core::KeyEvent::press(Key::Character(character))),
                    &InputContext::default(),
                )
                .expect("key");
        }
        engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Space)),
                &InputContext::default(),
            )
            .expect("commit");
        assert_eq!(engine.learning_records()[0].positive_count, 1);
    }
}
