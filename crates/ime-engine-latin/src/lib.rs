// Copyright © 2026 立方田 <managecode@gmail.com>
//! Reference Latin engine used to validate the language-neutral runtime contract.

use std::collections::HashMap;
use std::sync::Mutex;

use ime_core::{
    Action, ActionBatch, Candidate, CandidateId, Composition, EngineError, EngineMetadata,
    FeedbackEvent, InputContext, InputEngine, InputEvent, Key, Segment, SegmentState, SessionId,
};
use ime_data::{LearningModel, LearningRecord};

pub struct LatinEngine {
    sessions: Mutex<HashMap<SessionId, String>>,
    learning: Mutex<LearningModel>,
}

impl Default for LatinEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl LatinEngine {
    #[must_use]
    pub fn new() -> Self {
        Self::with_learning(LearningModel::new("learned-lexicon", "en", "local"))
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

    fn actions_for(&self, text: &str) -> ActionBatch {
        if text.is_empty() {
            return ActionBatch(vec![
                Action::UpdateComposition(Composition::default()),
                Action::CloseComposition,
            ]);
        }
        let composition = Composition {
            segments: vec![Segment {
                text: text.to_owned(),
                state: SegmentState::Composing,
                language: Some("en".to_owned()),
            }],
            cursor: ime_core::grapheme_count(text),
        };
        let learning = self
            .learning
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let query = text.to_lowercase();
        let mut words = [
            "hello", "help", "held", "world", "input", "method", "language", "keyboard",
        ]
        .into_iter()
        .filter_map(|word| {
            let prefix = word.starts_with(&query);
            let distance = levenshtein(&query, word);
            (prefix || (query.len() >= 3 && distance <= 1)).then_some((word, distance, prefix))
        })
        .map(|(word, distance, prefix)| {
            let score = learning.score(text, word);
            let bounded_score = i32::try_from(score).unwrap_or(i32::MAX);
            Candidate {
                id: CandidateId(format!("latin:{word}")),
                display_text: word.to_owned(),
                commit_text: word.to_owned(),
                language: Some("en".to_owned()),
                source: "latin.reference".to_owned(),
                score: (if prefix { 100.0 } else { 50.0 }) - f64::from(distance)
                    + f64::from(bounded_score) * 10.0,
                annotation: if score != 0 {
                    Some(format!("learned score {score}"))
                } else if !prefix {
                    Some("spelling correction".to_owned())
                } else {
                    None
                },
                learning_allowed: true,
            }
        })
        .collect::<Vec<_>>();
        words.sort_by(|left, right| right.score.total_cmp(&left.score));
        ActionBatch(vec![
            Action::UpdateComposition(composition),
            Action::ShowCandidates(words),
        ])
    }
}

impl InputEngine for LatinEngine {
    fn metadata(&self) -> EngineMetadata {
        EngineMetadata {
            id: "latin".to_owned(),
            display_name: "Reference Latin Engine".to_owned(),
            languages: vec!["en".to_owned()],
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
        let text = sessions.get_mut(session).ok_or_else(|| EngineError {
            message: "engine session not found".to_owned(),
        })?;
        match event {
            InputEvent::Key(key) if key.pressed => match key.key {
                Key::Character(character) => text.push(character),
                Key::Backspace => {
                    text.pop();
                }
                Key::Space | Key::Enter => {
                    let committed = std::mem::take(text);
                    return Ok(if committed.is_empty() {
                        ActionBatch(vec![Action::Ignored])
                    } else {
                        let predictions = next_word_candidates(&committed);
                        ActionBatch(vec![
                            Action::CommitText(committed),
                            Action::ShowCandidates(predictions),
                            Action::CloseComposition,
                        ])
                    });
                }
                Key::Escape => {
                    text.clear();
                    return Ok(ActionBatch(vec![
                        Action::UpdateComposition(Composition::default()),
                        Action::CloseComposition,
                    ]));
                }
                Key::Left | Key::Right | Key::PageUp | Key::PageDown => {
                    return Ok(ActionBatch(vec![Action::Ignored]))
                }
            },
            InputEvent::Text(value) => text.push_str(value),
            InputEvent::SpeechPartial(hypothesis) => {
                let composition = Composition {
                    segments: vec![Segment {
                        text: hypothesis.text.clone(),
                        state: SegmentState::SpeechPartial,
                        language: hypothesis.language.clone(),
                    }],
                    cursor: ime_core::grapheme_count(&hypothesis.text),
                };
                return Ok(ActionBatch(vec![Action::UpdateComposition(composition)]));
            }
            InputEvent::SpeechFinal(hypothesis) => {
                text.clear();
                return Ok(ActionBatch(vec![
                    Action::CommitText(hypothesis.text.clone()),
                    Action::CloseComposition,
                ]));
            }
            InputEvent::SelectCandidate(id) => {
                let commit =
                    id.0.strip_prefix("latin:")
                        .ok_or_else(|| EngineError {
                            message: "candidate does not belong to latin engine".to_owned(),
                        })?
                        .to_owned();
                let input_signature = std::mem::take(text);
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
                text.clear();
                return Ok(ActionBatch(vec![
                    Action::UpdateComposition(Composition::default()),
                    Action::CloseComposition,
                ]));
            }
            InputEvent::Key(_) | InputEvent::SwitchEngine(_) => {
                return Ok(ActionBatch(vec![Action::Ignored]));
            }
        }
        Ok(self.actions_for(text))
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

fn levenshtein(left: &str, right: &str) -> u32 {
    let mut previous = (0..=right.chars().count()).collect::<Vec<_>>();
    for (left_index, left_character) in left.chars().enumerate() {
        let mut current = vec![left_index + 1];
        for (right_index, right_character) in right.chars().enumerate() {
            let substitution =
                previous[right_index] + usize::from(left_character != right_character);
            current.push(
                (current[right_index] + 1)
                    .min(previous[right_index + 1] + 1)
                    .min(substitution),
            );
        }
        previous = current;
    }
    u32::try_from(previous[right.chars().count()]).unwrap_or(u32::MAX)
}

fn next_word_candidates(word: &str) -> Vec<Candidate> {
    let next = match word.to_ascii_lowercase().as_str() {
        "hello" => &["world", "there"][..],
        "input" => &["method"][..],
        "language" => &["model"][..],
        _ => &[],
    };
    next.iter()
        .enumerate()
        .map(|(index, value)| Candidate {
            id: CandidateId(format!("latin:{value}")),
            display_text: (*value).to_owned(),
            commit_text: (*value).to_owned(),
            language: Some("en".to_owned()),
            source: "latin.reference.next-word".to_owned(),
            score: 20.0 - f64::from(u32::try_from(index).unwrap_or(u32::MAX)),
            annotation: Some("next word".to_owned()),
            learning_allowed: true,
        })
        .collect()
}

fn lock_error<T>(_: std::sync::PoisonError<T>) -> EngineError {
    EngineError {
        message: "engine state lock poisoned".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ime_core::{KeyEvent, Runtime};
    use std::sync::Arc;

    #[test]
    fn composes_and_commits_text() {
        let mut runtime = Runtime::new();
        runtime.register_engine(Arc::new(LatinEngine::new()));
        let session = runtime.create_session("latin").expect("session");
        for character in ['h', 'e', 'l', 'l', 'o'] {
            runtime
                .process(
                    &session,
                    &InputEvent::Key(KeyEvent::press(Key::Character(character))),
                    &InputContext::default(),
                )
                .expect("key");
        }
        let result = runtime
            .process(
                &session,
                &InputEvent::Key(KeyEvent::press(Key::Space)),
                &InputContext::default(),
            )
            .expect("commit");
        assert_eq!(result.committed_text(), Some("hello"));
    }

    #[test]
    fn speech_partial_is_composition_and_final_is_commit() {
        let engine = LatinEngine::new();
        let session = SessionId("speech".to_owned());
        engine.create_session(&session).expect("session");
        let partial = engine
            .process(
                &session,
                &InputEvent::SpeechPartial(ime_core::SpeechHypothesis {
                    text: "hello wor".to_owned(),
                    confidence: Some(0.7),
                    language: Some("en".to_owned()),
                }),
                &InputContext::default(),
            )
            .expect("partial");
        assert!(matches!(
            partial.0.first(),
            Some(Action::UpdateComposition(_))
        ));
        let final_result = engine
            .process(
                &session,
                &InputEvent::SpeechFinal(ime_core::SpeechHypothesis {
                    text: "hello world".to_owned(),
                    confidence: Some(0.9),
                    language: Some("en".to_owned()),
                }),
                &InputContext::default(),
            )
            .expect("final");
        assert_eq!(final_result.committed_text(), Some("hello world"));
    }

    #[test]
    fn password_scope_never_enables_learning() {
        let context = InputContext {
            scope: ime_core::InputScope::Password,
            ..InputContext::default()
        };
        assert!(!context.effective_learning_allowed());
    }

    #[test]
    fn suggests_spelling_corrections_and_next_words() {
        let engine = LatinEngine::new();
        let session = SessionId("smart-latin".to_owned());
        engine.create_session(&session).expect("session");
        let correction = engine
            .process(
                &session,
                &InputEvent::Text("helo".to_owned()),
                &InputContext::default(),
            )
            .expect("correction");
        assert!(correction.0.iter().any(|action| {
            matches!(action, Action::ShowCandidates(values) if values.iter().any(|value| value.commit_text == "hello"))
        }));
        engine
            .process(&session, &InputEvent::Reset, &InputContext::default())
            .expect("reset");
        engine
            .process(
                &session,
                &InputEvent::Text("hello".to_owned()),
                &InputContext::default(),
            )
            .expect("input");
        let committed = engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Space)),
                &InputContext::default(),
            )
            .expect("commit");
        assert!(committed.0.iter().any(|action| {
            matches!(action, Action::ShowCandidates(values) if values.iter().any(|value| value.commit_text == "world"))
        }));
    }
}
