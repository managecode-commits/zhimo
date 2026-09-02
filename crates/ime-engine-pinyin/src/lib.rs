//! Small, dependency-free Pinyin reference engine.
//!
//! This validates product behavior and the generic engine contract. Production dictionaries
//! and segmentation will be supplied by the isolated librime adapter.

use std::collections::HashMap;
use std::sync::Mutex;

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
        const ENTRIES: &[(&str, &[&str])] = &[
            ("ni", &["你", "呢", "泥"]),
            ("nihao", &["你好"]),
            ("hao", &["好", "号", "浩"]),
            ("shuru", &["输入"]),
            ("shurufa", &["输入法"]),
            ("zhongwen", &["中文"]),
            ("yingwen", &["英文"]),
            ("shijie", &["世界"]),
        ];
        let learning = self
            .learning
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut candidates = ENTRIES
            .iter()
            .filter(|(key, _)| pinyin_matches_input(key, input))
            .flat_map(|(key, values)| {
                values.iter().enumerate().map(|(index, value)| {
                    let learned_score = learning.score(input, value);
                    let bounded_index = u32::try_from(index).unwrap_or(u32::MAX);
                    let bounded_score = i32::try_from(learned_score).unwrap_or(i32::MAX);
                    let exact_match_bonus = if pinyin_exact_match(key, input) {
                        1_000.0
                    } else {
                        0.0
                    };
                    Candidate {
                        id: CandidateId(format!("pinyin:{value}")),
                        display_text: (*value).to_owned(),
                        commit_text: (*value).to_owned(),
                        language: Some("zh-CN".to_owned()),
                        source: "pinyin.reference".to_owned(),
                        score: 100.0 - f64::from(bounded_index)
                            + f64::from(bounded_score) * 10.0
                            + exact_match_bonus,
                        annotation: Some((*key).to_owned()),
                        learning_allowed: true,
                    }
                })
            })
            .collect::<Vec<_>>();
        candidates.sort_by(|left, right| right.score.total_cmp(&left.score));
        candidates
    }

    fn composing_actions(&self, input: &str) -> ActionBatch {
        let composition = Composition {
            segments: vec![Segment {
                text: input.to_owned(),
                state: SegmentState::Composing,
                language: Some("zh-Latn-pinyin".to_owned()),
            }],
            cursor: ime_core::grapheme_count(input),
        };
        ActionBatch(vec![
            Action::UpdateComposition(composition),
            Action::ShowCandidates(self.lookup(input)),
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

fn pinyin_matches_input(pinyin: &str, input: &str) -> bool {
    if let Some(digits) = normalized_t9_input(input) {
        let signature = t9_signature(pinyin);
        signature.starts_with(&digits) || digits.starts_with(&signature)
    } else {
        pinyin.starts_with(input) || input.starts_with(pinyin)
    }
}

fn pinyin_exact_match(pinyin: &str, input: &str) -> bool {
    if let Some(digits) = normalized_t9_input(input) {
        t9_signature(pinyin) == digits
    } else {
        pinyin == input
    }
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
