// Copyright © 2026 立方田 <managecode@gmail.com>
//! Small, dependency-free Pinyin reference engine.
//!
//! This validates product behavior and the generic engine contract. Production dictionaries
//! and segmentation will be supplied by the isolated librime adapter.

use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};

use ime_core::{
    Action, ActionBatch, Candidate, CandidateId, Composition, EngineError, EngineMetadata,
    FeedbackEvent, InputContext, InputEngine, InputEvent, Key, Segment, SegmentState, SessionId,
};
use ime_data::{LearningModel, LearningRecord};

mod abbreviation;

pub struct PinyinEngine {
    sessions: Mutex<HashMap<SessionId, String>>,
    phrases: Mutex<HashMap<SessionId, PhraseProgress>>,
    learning: Mutex<LearningModel>,
    readings: Mutex<HashMap<SessionId, String>>,
}

#[derive(Default)]
struct PhraseProgress {
    signature: String,
    reading: String,
    text: String,
    parts: usize,
}

fn learning_signature(input: &str) -> String {
    normalized_t9_input(input).unwrap_or_else(|| normalized_pinyin(input))
}

fn learn_selection(learning: &mut LearningModel, signature: &str, reading: &str, text: &str) {
    let mut keys = HashSet::from([learning_signature(signature)]);
    let pinyin = normalized_pinyin(reading);
    if !pinyin.is_empty() {
        keys.insert(
            reading
                .split([' ', '\''])
                .filter(|s| !s.is_empty())
                .collect::<Vec<_>>()
                .join("'"),
        );
        keys.insert(t9_signature(&pinyin));
        keys.insert(pinyin);
    }
    for key in keys.into_iter().filter(|key| !key.is_empty()) {
        learning.selected(&key, text);
    }
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
            phrases: Mutex::new(HashMap::new()),
            learning: Mutex::new(learning),
            readings: Mutex::new(HashMap::new()),
        }
    }

    #[must_use]
    pub fn learning_records(&self) -> Vec<LearningRecord> {
        self.learning
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .records()
    }

    #[cfg(test)]
    fn lookup(&self, input: &str) -> Vec<Candidate> {
        self.lookup_reading(input, None)
    }

    // Keep the ordered ranking stages together; regression tests cover their precedence.
    #[allow(clippy::too_many_lines)]
    fn lookup_reading(&self, input: &str, selected: Option<&str>) -> Vec<Candidate> {
        let Some(lookup) = LookupInput::new(input) else {
            return Vec::new();
        };
        let learning = self
            .learning
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let signature = learning_signature(input);
        let mut unique_candidates = HashMap::<String, (u8, i64, Candidate)>::new();
        for entry in lookup
            .entries()
            .iter()
            .copied()
            .chain(abbreviation::lookup(input))
            .filter(|entry| {
                (lookup.matches(entry) || abbreviation::matches(&entry.display_pinyin, input))
                    && reading_matches(entry, input, selected)
            })
        {
            let learned_score = learning.score(&signature, &entry.text);
            let bounded_score = i32::try_from(learned_score).unwrap_or(i32::MAX);
            let exact = if lookup.exact(entry) {
                2
            } else {
                u8::from(abbreviation::matches(&entry.display_pinyin, input))
            };
            let candidate = Candidate {
                id: CandidateId(format!("pinyin:{}", entry.text)),
                display_text: entry.text.clone(),
                commit_text: entry.text.clone(),
                language: Some("zh-CN".to_owned()),
                source: "pinyin.reference".to_owned(),
                score: f64::from(entry.weight) + f64::from(bounded_score) * 10_000.0,
                annotation: Some(entry.display_pinyin.clone()),
                learning_allowed: true,
            };
            let existing = unique_candidates
                .entry(entry.text.clone())
                .or_insert_with(|| (exact, learned_score, candidate.clone()));
            if exact > existing.0 || (exact == existing.0 && candidate.score > existing.2.score) {
                *existing = (exact, learned_score, candidate);
            }
        }
        // Learned phrases are real lexicon entries, not only bonuses on static words.
        // Resolve each word's best reading once, rather than rescanning all N
        // learning records for each of N records on every key press.
        let mut learned_readings = HashMap::<&str, &str>::new();
        for record in learning
            .iter_records()
            .filter(|record| record.effective_weight() > 0)
        {
            let reading = record.key.input_signature.as_str();
            if reading
                .chars()
                .all(|ch| ch.is_ascii_lowercase() || ch == '\'')
            {
                learned_readings
                    .entry(record.key.value.as_str())
                    .and_modify(|current| {
                        let rank = |value: &str| (value.matches('\'').count(), value.len());
                        if rank(reading) > rank(current)
                            || (rank(reading) == rank(current) && reading > *current)
                        {
                            *current = reading;
                        }
                    })
                    .or_insert(reading);
            }
        }
        for record in learning.iter_records() {
            if record.effective_weight() <= 0
                || !record
                    .key
                    .value
                    .chars()
                    .all(|ch| ('\u{3400}'..='\u{9fff}').contains(&ch))
            {
                continue;
            }
            let reading = learned_readings
                .get(record.key.value.as_str())
                .copied()
                .unwrap_or(&signature);
            if record.key.input_signature != signature && !abbreviation::matches(reading, input) {
                continue;
            }
            let learned_entry = LexiconEntry {
                pinyin: normalized_pinyin(reading),
                t9: t9_signature(reading),
                display_pinyin: reading.to_owned(),
                text: record.key.value.clone(),
                weight: 0,
            };
            if !reading_matches(&learned_entry, input, selected) {
                continue;
            }
            let exact = if lookup.exact(&learned_entry) { 2 } else { 1 };
            let candidate = Candidate {
                id: CandidateId(format!("pinyin:{}", record.key.value)),
                display_text: record.key.value.clone(),
                commit_text: record.key.value.clone(),
                language: Some("zh-CN".to_owned()),
                source: "pinyin.reference".to_owned(),
                score: f64::from(i32::try_from(record.effective_weight()).unwrap_or(i32::MAX)),
                annotation: Some(reading.to_owned()),
                learning_allowed: true,
            };
            unique_candidates
                .entry(record.key.value.clone())
                .and_modify(|existing| {
                    existing.0 = existing.0.max(exact);
                    existing.1 = existing.1.max(record.effective_weight());
                })
                .or_insert((exact, record.effective_weight(), candidate));
        }
        let mut candidates = unique_candidates.into_values().collect::<Vec<_>>();
        candidates.sort_by(|left, right| {
            right
                .0
                .cmp(&left.0)
                .then_with(|| right.1.cmp(&left.1))
                .then_with(|| right.2.score.total_cmp(&left.2.score))
                .then_with(|| left.2.commit_text.cmp(&right.2.commit_text))
        });
        // T9 has many exact single-character readings. Keep the best exact
        // candidates first, but reserve reachable slots for phrase abbreviations.
        let abbreviations = candidates
            .iter()
            .filter(|candidate| candidate.0 == 1)
            .take(5)
            .map(|candidate| candidate.2.id.clone())
            .collect::<Vec<_>>();
        for id in abbreviations.into_iter().rev() {
            if let Some(position) = candidates.iter().position(|candidate| candidate.2.id == id) {
                if position > 15 {
                    let candidate = candidates.remove(position);
                    candidates.insert(15, candidate);
                }
            }
        }
        candidates.truncate(50);
        let mut result: Vec<_> = candidates
            .into_iter()
            .map(|(_, _, candidate)| candidate)
            .collect();
        // A missing phrase must not strand the whole buffer. Offer characters
        // for the leading syllable, carrying the exact raw prefix consumed.
        let normalized = match &lookup {
            LookupInput::Pinyin(s) | LookupInput::T9(s) => s,
        };
        let mut partial = Vec::new();
        for length in (1..normalized.len().min(7)).rev() {
            let prefix = &normalized[..length];
            let Some(prefix_lookup) = LookupInput::new(prefix) else {
                continue;
            };
            let mut entries: Vec<_> = prefix_lookup
                .entries()
                .iter()
                .copied()
                .filter(|entry| {
                    prefix_lookup.exact(entry)
                        && entry.text.chars().count() == 1
                        && selected.is_none_or(|reading| entry.pinyin == reading)
                })
                .collect();
            entries.sort_by(|a, b| {
                learning
                    .score(prefix, &b.text)
                    .cmp(&learning.score(prefix, &a.text))
                    .then_with(|| b.weight.cmp(&a.weight))
                    .then_with(|| a.text.cmp(&b.text))
            });
            let mut consumed = 0;
            let mut units = 0;
            for (offset, ch) in input.char_indices() {
                if ch.is_ascii_alphabetic() || ('2'..='9').contains(&ch) {
                    units += 1;
                }
                if units == length {
                    consumed = offset + ch.len_utf8();
                    break;
                }
            }
            if consumed == 0 || input[..consumed].trim_start_matches('\'').contains('\'') {
                continue;
            }
            for entry in entries.into_iter().take(40) {
                partial.push(Candidate {
                    id: CandidateId(format!("pinyin-part:{consumed}:{}", entry.text)),
                    display_text: entry.text.clone(),
                    commit_text: entry.text.clone(),
                    language: Some("zh-CN".to_owned()),
                    source: "pinyin.reference".to_owned(),
                    score: f64::from(entry.weight),
                    annotation: Some(entry.display_pinyin.clone()),
                    learning_allowed: true,
                });
            }
        }
        if !partial.is_empty() {
            result.truncate(20);
            result.extend(partial.into_iter().take(80));
        }
        result
    }

    fn commit_candidate(
        &self,
        session: &SessionId,
        input: &mut String,
        candidate: &Candidate,
        context: &InputContext,
    ) -> Result<ActionBatch, EngineError> {
        let consumed = candidate
            .id
            .0
            .strip_prefix("pinyin-part:")
            .and_then(|value| value.split(':').next())
            .and_then(|value| value.parse::<usize>().ok())
            .unwrap_or(input.len());
        let signature = input[..consumed].to_owned();
        *input = input[consumed..].trim_start_matches('\'').to_owned();
        if context.effective_learning_allowed() {
            let mut progress = self.phrases.lock().map_err(lock_error)?;
            let phrase = progress.entry(session.clone()).or_default();
            phrase.signature.push_str(&learning_signature(&signature));
            phrase
                .reading
                .push_str(candidate.annotation.as_deref().unwrap_or(""));
            phrase.reading.push(' ');
            phrase.text.push_str(&candidate.commit_text);
            phrase.parts += 1;
            let mut learning = self.learning.lock().map_err(lock_error)?;
            learn_selection(
                &mut learning,
                &signature,
                candidate.annotation.as_deref().unwrap_or(""),
                &candidate.commit_text,
            );
            if input.is_empty() {
                if phrase.parts > 1 {
                    learn_selection(
                        &mut learning,
                        &phrase.signature,
                        &phrase.reading,
                        &phrase.text,
                    );
                }
                progress.remove(session);
            }
        }
        let mut actions = vec![Action::CommitText(candidate.commit_text.clone())];
        self.readings.lock().map_err(lock_error)?.remove(session);
        if input.is_empty() {
            actions.push(Action::CloseComposition);
        } else {
            actions.extend(self.composing_actions(session, input).0);
        }
        Ok(ActionBatch(actions))
    }

    fn composing_actions(&self, session: &SessionId, input: &str) -> ActionBatch {
        let selected = self
            .readings
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(session)
            .cloned();
        let candidates = self.lookup_reading(input, selected.as_deref());
        // Keep the editable buffer truthful. Ambiguous T9 interpretations belong
        // in candidate annotations, not in a guessed/pre-completed preedit.
        let display_input = input.to_owned();
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
            Action::ReadingOptions {
                readings: t9_readings(input),
                selected,
            },
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

    // One exhaustive event transition table; splitting arms would obscure state changes.
    #[allow(clippy::too_many_lines)]
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
        if matches!(event, InputEvent::Reset | InputEvent::SpeechFinal(_))
            || matches!(event, InputEvent::Key(key) if key.pressed && matches!(key.key, Key::Backspace | Key::Escape))
        {
            self.readings.lock().map_err(lock_error)?.remove(session);
        }
        if !context.effective_learning_allowed() {
            self.phrases.lock().map_err(lock_error)?.remove(session);
        }
        match event {
            InputEvent::Key(key) if key.pressed => match key.key {
                Key::Character(character)
                    if character.is_ascii_alphabetic()
                        || ('2'..='9').contains(&character)
                        || character == '\'' =>
                {
                    if character != '\'' || (!input.is_empty() && !input.ends_with('\'')) {
                        input.push(character.to_ascii_lowercase());
                    }
                }
                Key::Backspace => {
                    input.pop();
                    if input.is_empty() {
                        self.phrases.lock().map_err(lock_error)?.remove(session);
                    }
                }
                Key::Enter => {
                    // Return commits what was typed; Space selects a Chinese
                    // candidate. Never learn a candidate from a raw-text commit.
                    self.readings.lock().map_err(lock_error)?.remove(session);
                    self.phrases.lock().map_err(lock_error)?.remove(session);
                    if input.is_empty() {
                        return Ok(ActionBatch(vec![Action::Ignored]));
                    }
                    return Ok(ActionBatch(vec![
                        Action::CommitText(std::mem::take(input)),
                        Action::CloseComposition,
                    ]));
                }
                Key::Space => {
                    let selected = self
                        .readings
                        .lock()
                        .map_err(lock_error)?
                        .get(session)
                        .cloned();
                    if let Some(candidate) = self.lookup_reading(input, selected.as_deref()).first()
                    {
                        return self.commit_candidate(session, input, candidate, context);
                    }
                    let commit = std::mem::take(input);
                    self.phrases.lock().map_err(lock_error)?.remove(session);
                    return Ok(ActionBatch(vec![
                        Action::CommitText(commit),
                        Action::CloseComposition,
                    ]));
                }
                Key::Escape => {
                    input.clear();
                    self.phrases.lock().map_err(lock_error)?.remove(session);
                    return Ok(ActionBatch(vec![
                        Action::UpdateComposition(Composition::default()),
                        Action::CloseComposition,
                    ]));
                }
                _ => return Ok(ActionBatch(vec![Action::Ignored])),
            },
            InputEvent::SelectCandidate(id) => {
                if let Some(reading) = id.0.strip_prefix("pinyin-reading:") {
                    if reading.is_empty() {
                        self.readings.lock().map_err(lock_error)?.remove(session);
                    } else if t9_readings(input).iter().any(|value| value == reading) {
                        self.readings
                            .lock()
                            .map_err(lock_error)?
                            .insert(session.clone(), reading.to_owned());
                    } else {
                        return Err(EngineError {
                            message: "reading is not in the current pinyin menu".to_owned(),
                        });
                    }
                    return Ok(self.composing_actions(session, input));
                }
                let selected = self
                    .readings
                    .lock()
                    .map_err(lock_error)?
                    .get(session)
                    .cloned();
                let candidate = self
                    .lookup_reading(input, selected.as_deref())
                    .into_iter()
                    .find(|candidate| candidate.id == *id)
                    .ok_or_else(|| EngineError {
                        message: "candidate is not in the current pinyin menu".to_owned(),
                    })?;
                return self.commit_candidate(session, input, &candidate, context);
            }
            InputEvent::Reset => {
                input.clear();
                self.phrases.lock().map_err(lock_error)?.remove(session);
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
                self.phrases.lock().map_err(lock_error)?.remove(session);
                return Ok(ActionBatch(vec![
                    Action::CommitText(hypothesis.text.clone()),
                    Action::CloseComposition,
                ]));
            }
            InputEvent::Key(_) | InputEvent::SwitchEngine(_) => {
                return Ok(ActionBatch(vec![Action::Ignored]));
            }
        }
        {
            let mut readings = self.readings.lock().map_err(lock_error)?;
            if input.is_empty()
                || readings
                    .get(session)
                    .is_some_and(|reading| !t9_readings(input).contains(reading))
            {
                readings.remove(session);
            }
        }
        Ok(self.composing_actions(session, input))
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
        if let Ok(mut readings) = self.readings.lock() {
            readings.remove(session);
        }
        if let Ok(mut sessions) = self.sessions.lock() {
            sessions.remove(session);
        }
        if let Ok(mut phrases) = self.phrases.lock() {
            phrases.remove(session);
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

fn reading_matches(entry: &LexiconEntry, input: &str, selected: Option<&str>) -> bool {
    if selected.is_none() && !input.contains('\'') {
        return true;
    }
    let syllables = entry
        .display_pinyin
        .split([' ', '\''])
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>();
    if selected.is_some_and(|reading| syllables.first().copied() != Some(reading)) {
        return false;
    }
    if abbreviation::matches(&entry.display_pinyin, input) {
        return true;
    }
    if !input.contains('\'') {
        return true;
    }
    let mut boundaries = HashSet::new();
    let mut offset = 0;
    for syllable in syllables {
        offset += normalized_pinyin(syllable).len();
        boundaries.insert(offset);
    }
    let mut offset = 0;
    for ch in input.chars() {
        if ch == '\'' {
            if !boundaries.contains(&offset) {
                return false;
            }
        } else if ch.is_ascii_alphabetic() || ('2'..='9').contains(&ch) {
            offset += 1;
        }
    }
    true
}

fn t9_readings(input: &str) -> Vec<String> {
    static SYLLABLES: OnceLock<Vec<(String, String)>> = OnceLock::new();
    let Some(_) = normalized_t9_input(input) else {
        return Vec::new();
    };
    let syllables = SYLLABLES.get_or_init(|| {
        let unique = lexicon()
            .iter()
            .filter(|entry| entry.text.chars().count() == 1)
            .map(|entry| entry.pinyin.clone())
            .collect::<HashSet<_>>();
        unique
            .into_iter()
            .map(|reading| (t9_signature(&reading), reading))
            .collect()
    });
    let first = input.split('\'').next().unwrap_or("");
    let explicit = input.contains('\'');
    let mut choices = syllables
        .iter()
        .filter(|(digits, _)| {
            if explicit {
                digits == first
            } else {
                digits.starts_with(first) || first.starts_with(digits)
            }
        })
        .collect::<Vec<_>>();
    choices.sort_by(|a, b| {
        (b.0 == first)
            .cmp(&(a.0 == first))
            .then_with(|| b.0.len().min(first.len()).cmp(&a.0.len().min(first.len())))
            .then_with(|| a.1.cmp(&b.1))
    });
    choices
        .into_iter()
        .map(|(_, reading)| reading.clone())
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
    fn entries(&self) -> &'static [&'static LexiconEntry] {
        static PINYIN: OnceLock<Vec<&'static LexiconEntry>> = OnceLock::new();
        static T9: OnceLock<Vec<&'static LexiconEntry>> = OnceLock::new();
        let (index, input, key): (_, _, fn(&LexiconEntry) -> &str) = match self {
            Self::Pinyin(input) => (&PINYIN, input.as_str(), |entry| entry.pinyin.as_str()),
            Self::T9(input) => (&T9, input.as_str(), |entry| entry.t9.as_str()),
        };
        let entries = index.get_or_init(|| {
            let mut entries = lexicon().iter().collect::<Vec<_>>();
            entries.sort_unstable_by(|left, right| key(left).cmp(key(right)));
            entries
        });
        let start = entries.partition_point(|entry| key(entry) < input);
        let count = entries[start..].partition_point(|entry| key(entry).starts_with(input));
        &entries[start..start + count]
    }

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
            (include_str!("../data/common_phrase_lexicon.tsv"), 0),
            (include_str!("../data/geography_lexicon.tsv"), 0),
            (include_str!("../data/curated_phrase_lexicon.tsv"), 0),
            (include_str!("../data/reference_lexicon.tsv"), 10_000_000),
            // Product name is compiled into every reference-engine build,
            // independent of downloaded dictionaries and user learning.
            ("zhi mo\t知墨\t10000", 10_000_000),
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
    fn abbreviations_and_mixed_spelling_work_in_both_layouts() {
        let engine = PinyinEngine::new();
        for (input, word) in [
            ("nh", "你好"),
            ("wm", "我们"),
            ("zg", "中国"),
            ("wlw", "物联网"),
            ("nih", "你好"),
            ("nhao", "你好"),
            ("wulw", "物联网"),
            ("n'h", "你好"),
            ("ni'h", "你好"),
            ("zhg", "中国"),
            ("zm", "知墨"),
        ] {
            for code in [input.to_owned(), t9_signature(input)] {
                let values = engine.lookup(&code);
                assert!(
                    values.iter().any(|c| c.commit_text == word),
                    "{code}: {word}"
                );
                assert_eq!(
                    values.iter().map(|c| &c.id).collect::<HashSet<_>>().len(),
                    values.len()
                );
            }
        }
        assert!(!abbreviation::matches("ni hao", "n'ihao"));
        assert!(!abbreviation::matches("ni hao", "nhx"));
        assert!(!abbreviation::matches("ni hao", "n''h"));
        assert!(!abbreviation::matches("ni hao", "n"));
        assert!(!abbreviation::matches("jian", "j'an"));
    }

    #[test]
    fn abbreviation_selection_commits_and_is_learned_without_beating_exact_spelling() {
        let engine = PinyinEngine::new();
        let session = SessionId("abbreviation".into());
        let context = InputContext::default();
        engine.create_session(&session).unwrap();
        engine
            .process(&session, &InputEvent::Text("nh".into()), &context)
            .unwrap();
        let actions = engine
            .process(
                &session,
                &InputEvent::SelectCandidate(CandidateId("pinyin:你好".into())),
                &context,
            )
            .unwrap();
        assert_eq!(actions.committed_text(), Some("你好"));
        assert_eq!(engine.lookup("nh")[0].commit_text, "你好");
        assert!(engine
            .learning_records()
            .iter()
            .any(|r| r.key.input_signature == "nh" && r.key.value == "你好"));
        assert_eq!(engine.lookup("ren")[0].commit_text, "人");
        assert!(engine
            .lookup("nihao")
            .iter()
            .any(|c| c.commit_text == "你好"));
    }

    #[test]
    #[ignore = "manual release-mode latency measurement"]
    fn abbreviation_lookup_latency() {
        let engine = PinyinEngine::new();
        let cases = ["nh", "wm", "wulw", "64", "96", "9859"];
        let start = std::time::Instant::now();
        for code in cases {
            engine.lookup(code);
        }
        eprintln!(
            "abbreviation cold indexes and six lookups: {:?}",
            start.elapsed()
        );
        let start = std::time::Instant::now();
        for _ in 0..20 {
            for code in cases {
                engine.lookup(code);
            }
        }
        eprintln!("abbreviation 120 warm lookups: {:?}", start.elapsed());
    }

    #[test]
    fn curated_phrases_are_synced_and_searchable() {
        let engine = PinyinEngine::new();
        let rime = include_str!(
            "../../../platform/android-ime/app/src/main/assets/rime/zhimo_curated.dict.yaml"
        );
        let mut expected_rows = Vec::new();
        let mut seen = std::collections::HashSet::new();
        for line in include_str!("../data/curated_phrase_lexicon.tsv")
            .lines()
            .filter(|line| !line.is_empty() && !line.starts_with('#'))
        {
            let fields: Vec<_> = line.split('\t').collect();
            assert_eq!(fields.len(), 3);
            assert!(
                seen.insert((fields[0], fields[1])),
                "duplicate curated phrase"
            );
            assert_eq!(
                fields[0].split_whitespace().count(),
                fields[1].chars().count()
            );
            assert!(fields[2].parse::<i32>().unwrap() > 0);
            expected_rows.push(format!("{}\t{}\t{}", fields[1], fields[0], fields[2]));
            let reading = fields[0].replace(' ', "");
            for input in [
                reading.clone(),
                fields[0].replace(' ', "'"),
                t9_signature(&reading),
            ] {
                assert!(
                    engine
                        .lookup(&input)
                        .iter()
                        .any(|c| c.commit_text == fields[1]),
                    "{input}: missing {}",
                    fields[1]
                );
            }
        }
        assert_eq!(
            rime.split_once("...\n")
                .unwrap()
                .1
                .lines()
                .collect::<Vec<_>>(),
            expected_rows
        );
        let schema = include_str!(
            "../../../platform/android-ime/app/src/main/assets/rime/zhimo_pinyin.dict.yaml"
        );
        assert!(schema.contains("  - zhimo_curated\n"));
        assert!(engine.lookup("jj").iter().any(|c| c.commit_text == "极简"));
        assert!(engine
            .lookup("jijian")
            .iter()
            .any(|c| c.commit_text != "极简"));
    }

    #[test]
    fn product_name_is_built_in_for_full_pinyin_and_t9() {
        let engine = PinyinEngine::new();
        for input in ["zhimo", "zhi'mo", "94466", "944'66"] {
            let candidates = engine.lookup(input);
            let matches: Vec<_> = candidates
                .iter()
                .filter(|c| c.commit_text == "知墨")
                .collect();
            assert_eq!(
                matches.len(),
                1,
                "{input}: missing or duplicate product name"
            );
            assert_eq!(matches[0].annotation.as_deref(), Some("zhi mo"));
        }
    }

    #[test]
    fn geographic_names_support_full_pinyin_and_nine_key() {
        let engine = PinyinEngine::new();
        for (reading, word) in [
            ("hebeisheng", "河北省"),
            ("neimengguzizhiqu", "内蒙古自治区"),
            ("shijiazhuangshi", "石家庄市"),
            ("wulumuqishi", "乌鲁木齐市"),
            ("liangjiangxinqu", "两江新区"),
            ("shennongjialinqu", "神农架林区"),
            ("kekedalashi", "可克达拉市"),
            ("baiyangshi", "白杨市"),
            ("xianggangtebiexingzhengqu", "香港特别行政区"),
            ("aomentebiexingzhengqu", "澳门特别行政区"),
            ("taibeishi", "台北市"),
            ("jinmenxian", "金门县"),
            ("shanxian", "单县"),
            ("fanshixian", "繁峙县"),
            ("yingjingxian", "荥经县"),
            ("xunxian", "浚县"),
            ("guoyangxian", "涡阳县"),
            ("zhongmouxian", "中牟县"),
            ("hunchunshi", "珲春市"),
            ("tanchangxian", "宕昌县"),
        ] {
            for input in [reading.to_owned(), t9_signature(reading)] {
                assert!(
                    engine.lookup(&input).iter().any(|c| c.commit_text == word),
                    "{input}: {word}"
                );
            }
        }
    }

    #[test]
    fn geographic_overlay_is_loaded_without_losing_syllable_boundaries() {
        for line in include_str!("../data/geography_lexicon.tsv")
            .lines()
            .filter(|line| !line.starts_with('#'))
        {
            let fields = line.split('\t').collect::<Vec<_>>();
            assert_eq!(fields.len(), 3);
            assert_eq!(
                fields[0].split_whitespace().count(),
                fields[1].chars().count()
            );
            let lookup = LookupInput::new(fields[0]).unwrap();
            assert!(
                lookup
                    .entries()
                    .iter()
                    .any(|entry| entry.text == fields[1] && lookup.exact(entry)),
                "{}",
                fields[1]
            );
        }
    }

    #[test]
    fn manual_boundary_excludes_unsplit_reading() {
        let engine = PinyinEngine::new();
        assert!(engine.lookup("jian").iter().any(|c| c.commit_text == "间"));
        let split = engine.lookup("ji'an");
        assert!(split.iter().any(|c| c.commit_text == "吉安"));
        assert!(!split.iter().any(|c| c.commit_text == "间"));
        assert!(!engine.lookup("xi'an").iter().any(|c| c.commit_text == "先"));
    }

    #[test]
    fn t9_sidebar_contains_ambiguous_readings_without_candidate_limit() {
        let choices = t9_readings("5426");
        for reading in ["jian", "jiao", "lian", "liao"] {
            assert!(choices.contains(&reading.to_owned()));
        }
        assert!(t9_readings("ji'an").is_empty());
        assert!(t9_readings("54'26")
            .iter()
            .all(|reading| t9_signature(reading) == "54"));
    }

    #[test]
    fn t9_reading_selection_filters_without_committing_and_can_be_reset() {
        let engine = PinyinEngine::new();
        let session = SessionId("reading".into());
        engine.create_session(&session).unwrap();
        let context = InputContext::default();
        engine
            .process(&session, &InputEvent::Text("5426".into()), &context)
            .unwrap();
        let selected = engine
            .process(
                &session,
                &InputEvent::SelectCandidate(CandidateId("pinyin-reading:lian".into())),
                &context,
            )
            .unwrap();
        assert!(selected.committed_text().is_none());
        assert!(selected
            .0
            .iter()
            .any(|a| matches!(a, Action::ReadingOptions { selected: Some(s), .. } if s == "lian")));
        let menu = selected
            .0
            .iter()
            .find_map(|a| match a {
                Action::ShowCandidates(c) => Some(c),
                _ => None,
            })
            .unwrap();
        for word in ["连", "脸", "练"] {
            assert!(menu.iter().any(|c| c.commit_text == word));
        }
        assert!(menu.iter().all(|c| c
            .annotation
            .as_deref()
            .unwrap_or("")
            .split([' ', '\''])
            .next()
            == Some("lian")));
        assert!(engine
            .process(
                &session,
                &InputEvent::SelectCandidate(CandidateId("pinyin-reading:hao".into())),
                &context
            )
            .is_err());
        let automatic = engine
            .process(
                &session,
                &InputEvent::SelectCandidate(CandidateId("pinyin-reading:".into())),
                &context,
            )
            .unwrap();
        assert!(automatic
            .0
            .iter()
            .any(|a| matches!(a, Action::ReadingOptions { selected: None, .. })));
        engine
            .process(
                &session,
                &InputEvent::SelectCandidate(CandidateId("pinyin-reading:lian".into())),
                &context,
            )
            .unwrap();
        let committed = engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Space)),
                &context,
            )
            .unwrap();
        assert!(committed.committed_text().is_some());
        assert!(!engine.readings.lock().unwrap().contains_key(&session));
    }

    #[test]
    fn deleting_selected_reading_and_repeated_boundary_are_safe() {
        let engine = PinyinEngine::new();
        let session = SessionId("editing".into());
        engine.create_session(&session).unwrap();
        let context = InputContext::default();
        for ch in "'ji''an".chars() {
            engine
                .process(
                    &session,
                    &InputEvent::Key(ime_core::KeyEvent::press(Key::Character(ch))),
                    &context,
                )
                .unwrap();
        }
        assert_eq!(engine.sessions.lock().unwrap()[&session], "ji'an");
        engine
            .process(&session, &InputEvent::Reset, &context)
            .unwrap();
        engine
            .process(&session, &InputEvent::Text("5426".into()), &context)
            .unwrap();
        engine
            .process(
                &session,
                &InputEvent::SelectCandidate(CandidateId("pinyin-reading:lian".into())),
                &context,
            )
            .unwrap();
        engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Backspace)),
                &context,
            )
            .unwrap();
        assert!(!engine.readings.lock().unwrap().contains_key(&session));
        assert_eq!(engine.sessions.lock().unwrap()[&session], "542");
    }

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
    fn exact_syllable_beats_project_prefix_boost() {
        let engine = PinyinEngine::new();
        assert_eq!(engine.lookup("re")[0].commit_text, "热");
        assert_eq!(engine.lookup("ren")[0].commit_text, "人");
    }

    #[test]
    fn indexed_lookup_matches_full_scan() {
        for input in ["r", "re", "ren", "nihao", "64426", "78826", "zzzzzz"] {
            let lookup = LookupInput::new(input).expect("input");
            assert_eq!(
                lookup.entries().len(),
                lexicon()
                    .iter()
                    .filter(|entry| lookup.matches(entry))
                    .count()
            );
        }
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
            matches!(action, Action::UpdateComposition(composition) if composition.segments[0].text == "64426" && composition.cursor == 5)
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
        // Linguistic input for 输入法, not a project identifier.
        assert_eq!(t9_signature("shu'ru'fa"), "7487832");
        assert_eq!(t9_signature("zhongwen"), "94664936");
        assert!(pinyin_exact_match("nihao", "64'426"));
    }

    #[test]
    fn return_commits_raw_pinyin_while_space_selects_chinese() {
        for (text, expected) in [("jixu", "继续"), ("nihao", "你好")] {
            for key in [Key::Enter, Key::Space] {
                let engine = PinyinEngine::new();
                let session = SessionId("return-space".to_owned());
                let context = InputContext::default();
                engine.create_session(&session).unwrap();
                engine
                    .process(&session, &InputEvent::Text(text.to_owned()), &context)
                    .unwrap();
                let actions = engine
                    .process(
                        &session,
                        &InputEvent::Key(ime_core::KeyEvent::press(key.clone())),
                        &context,
                    )
                    .unwrap();
                assert_eq!(
                    actions.committed_text(),
                    Some(if key == Key::Enter { text } else { expected })
                );
                let idle = engine
                    .process(
                        &session,
                        &InputEvent::Key(ime_core::KeyEvent::press(Key::Enter)),
                        &context,
                    )
                    .unwrap();
                assert_eq!(idle.committed_text(), None);
                assert!(matches!(idle.0.as_slice(), [Action::Ignored]));
            }
        }
    }

    #[test]
    fn unknown_input_falls_back_to_raw_text() {
        let engine = PinyinEngine::new();
        let session = SessionId("fallback".to_owned());
        engine.create_session(&session).expect("session");
        engine
            .process(
                &session,
                &InputEvent::Text("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz".to_owned()),
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
        assert_eq!(
            actions.committed_text(),
            Some("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")
        );
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
        let values = actions
            .0
            .iter()
            .find_map(|action| match action {
                Action::ShowCandidates(values) => Some(values),
                _ => None,
            })
            .unwrap();
        assert!(!values
            .iter()
            .any(|candidate| candidate.commit_text == "你好"));
        let first = values
            .iter()
            .find(|candidate| candidate.commit_text == "你")
            .unwrap();
        let selected = engine
            .process(
                &session,
                &InputEvent::SelectCandidate(first.id.clone()),
                &InputContext::default(),
            )
            .unwrap();
        assert_eq!(selected.committed_text(), Some("你"));
        assert!(selected.0.iter().any(|action| matches!(action, Action::UpdateComposition(value) if value.segments[0].text == "haox")));
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
            ("shu'ru'fa", "输入法"),
            ("rengongzhineng", "人工智能"),
            ("lvdai", "履带"),
            ("lv'dai", "履带"),
            ("wulianwang", "物联网"),
            ("wu'lian'wang", "物联网"),
            ("58324", "履带"),
            ("9854269264", "物联网"),
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
    fn unknown_phrase_can_be_selected_one_character_at_a_time() {
        for code in ["maolvbei", "mao'lv'bei", "62658234"] {
            let engine = PinyinEngine::new();
            let session = SessionId(code.to_owned());
            engine.create_session(&session).unwrap();
            let context = InputContext::default();
            let mut actions = engine
                .process(&session, &InputEvent::Text(code.to_owned()), &context)
                .unwrap();
            for (index, expected) in ["猫", "驴", "杯"].iter().enumerate() {
                let selected = actions
                    .0
                    .iter()
                    .find_map(|action| match action {
                        Action::ShowCandidates(values) => values
                            .iter()
                            .find(|c| c.commit_text == *expected)
                            .map(|c| c.id.clone()),
                        _ => None,
                    })
                    .unwrap_or_else(|| panic!("missing {expected} for {code}: {actions:?}"));
                actions = engine
                    .process(&session, &InputEvent::SelectCandidate(selected), &context)
                    .unwrap();
                assert_eq!(actions.committed_text(), Some(*expected));
                assert_eq!(
                    actions
                        .0
                        .iter()
                        .any(|a| matches!(a, Action::CloseComposition)),
                    index == 2
                );
                if index < 2 {
                    assert!(!engine
                        .learning_records()
                        .iter()
                        .any(|record| record.key.value == "猫驴杯"));
                }
            }
            let restored = PinyinEngine::with_learning(LearningModel::from_records(
                "learned-lexicon",
                "zh-CN",
                "reopened",
                engine.learning_records(),
            ));
            for spelling in ["maolvbei", "mao'lv'bei", "62658234", "mlb", "mao'lb", "652"] {
                assert_eq!(restored.lookup(spelling)[0].commit_text, "猫驴杯");
            }
        }
    }

    #[test]
    fn frequency_beats_static_priority_and_cancelled_groups_are_not_learned() {
        let engine = PinyinEngine::new();
        let session = SessionId("frequency".into());
        engine.create_session(&session).unwrap();
        let context = InputContext::default();
        for word in ["泥", "泥", "你"] {
            engine
                .process(&session, &InputEvent::Text("ni".into()), &context)
                .unwrap();
            engine
                .process(
                    &session,
                    &InputEvent::SelectCandidate(CandidateId(format!("pinyin:{word}"))),
                    &context,
                )
                .unwrap();
        }
        assert_eq!(engine.lookup("ni")[0].commit_text, "泥");
        assert_eq!(engine.lookup("64")[0].commit_text, "泥");
        engine
            .process(&session, &InputEvent::Text("maolvbei".into()), &context)
            .unwrap();
        let choice = engine
            .lookup("maolvbei")
            .into_iter()
            .find(|c| c.commit_text == "猫")
            .unwrap();
        engine
            .process(&session, &InputEvent::SelectCandidate(choice.id), &context)
            .unwrap();
        engine
            .process(&session, &InputEvent::Reset, &context)
            .unwrap();
        assert!(!engine
            .learning_records()
            .iter()
            .any(|record| record.key.value == "猫驴杯"));
        assert!(engine.phrases.lock().unwrap().is_empty());
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
