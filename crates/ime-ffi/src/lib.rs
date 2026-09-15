// Copyright © 2026 立方田 <managecode@gmail.com>
//! Minimal C ABI proving ownership and UTF-8 transfer across platform bridges.

mod handwriting;

use std::ffi::{c_char, CStr, CString};
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::Arc;

use ime_core::{
    Action, ActionBatch, CandidateId, FeedbackEvent, FeedbackKind, InputContext, InputEngine,
    InputEvent, InputScope, Key, KeyEvent, Runtime, SessionId, SpeechHypothesis,
};
use ime_data::{merge_records, migrate_json_to_sqlite, JsonFileStore, LearningModel, SqliteStore};
use ime_engine_latin::LatinEngine;
use ime_engine_pinyin::PinyinEngine;
use ime_engine_router::LanguageRouter;
use ime_speech::whisper_cpp::WhisperCppProvider;
use ime_speech::{
    AudioFrame, SpeechController, SpeechPolicy, SpeechSessionConfig, SpeechSessionHandle,
};

pub struct ImeHandle {
    runtime: Runtime,
    session: SessionId,
    last_commit: Option<CString>,
    last_actions: CString,
    last_action_batch: ActionBatch,
    query_buffer: Option<CString>,
    context: InputContext,
    latin: Arc<LatinEngine>,
    pinyin: Arc<PinyinEngine>,
    learning_database: Option<PathBuf>,
}

pub struct ImeSpeechHandle {
    controller: SpeechController<WhisperCppProvider>,
    active: Option<SpeechSessionHandle>,
}

impl Drop for ImeSpeechHandle {
    fn drop(&mut self) {
        if let Some(active) = self.active.take() {
            self.controller.cancel(active);
        }
    }
}

#[no_mangle]
pub const extern "C" fn ime_runtime_abi_version() -> u32 {
    0x0001_0003
}

#[no_mangle]
pub const extern "C" fn ime_runtime_capabilities() -> u64 {
    let capabilities =
        (1_u64 << 0) | (1_u64 << 1) | (1_u64 << 2) | (1_u64 << 3) | (1_u64 << 5) | (1_u64 << 6);
    #[cfg(feature = "native-librime")]
    {
        capabilities | (1_u64 << 4)
    }
    #[cfg(not(feature = "native-librime"))]
    {
        capabilities
    }
}

#[no_mangle]
pub extern "C" fn ime_runtime_new() -> *mut ImeHandle {
    create_handle("bilingual", None)
}

fn create_handle(engine_id: &str, data_dir: Option<&Path>) -> *mut ImeHandle {
    create_handle_with_engine(engine_id, data_dir, None)
}

fn create_handle_with_engine(
    engine_id: &str,
    data_dir: Option<&Path>,
    additional_engine: Option<Arc<dyn InputEngine>>,
) -> *mut ImeHandle {
    let mut runtime = Runtime::new();
    let learning_database = data_dir.map(|directory| directory.join("learning-v1.sqlite3"));
    let (stored_records, device_id) =
        if let (Some(directory), Some(database)) = (data_dir, learning_database.as_ref()) {
            let store = SqliteStore::new(database);
            let legacy = JsonFileStore::new(directory.join("learning-v1.json"));
            let mut records = store.load().unwrap_or_default();
            if records.is_empty()
                && directory.join("learning-v1.json").is_file()
                && migrate_json_to_sqlite(&legacy, &store).is_ok()
            {
                records = store.load().unwrap_or_default();
            }
            let device_id = store
                .device_id()
                .unwrap_or_else(|_| format!("local-process-{}", std::process::id()));
            (records, device_id)
        } else {
            (
                Vec::new(),
                format!("ephemeral-process-{}", std::process::id()),
            )
        };
    let latin = Arc::new(LatinEngine::with_learning(LearningModel::from_records(
        "learned-lexicon",
        "en",
        &device_id,
        stored_records.clone(),
    )));
    let pinyin = Arc::new(PinyinEngine::with_learning(LearningModel::from_records(
        "learned-lexicon",
        "zh-CN",
        &device_id,
        stored_records,
    )));
    let latin_engine: Arc<dyn InputEngine> = latin.clone();
    let pinyin_engine: Arc<dyn InputEngine> = pinyin.clone();
    let mut child_engines = vec![Arc::clone(&latin_engine), Arc::clone(&pinyin_engine)];
    if let Some(engine) = additional_engine.as_ref() {
        child_engines.push(Arc::clone(engine));
    }
    let Ok(router) = LanguageRouter::new("bilingual", "latin", child_engines) else {
        return ptr::null_mut();
    };
    runtime.register_engine(latin_engine);
    runtime.register_engine(pinyin_engine);
    if let Some(engine) = additional_engine {
        runtime.register_engine(engine);
    }
    runtime.register_engine(Arc::new(router));
    match runtime.create_session(engine_id) {
        Ok(session) => Box::into_raw(Box::new(ImeHandle {
            runtime,
            session,
            last_commit: None,
            last_actions: CString::new("[]").expect("static JSON has no NUL"),
            last_action_batch: ActionBatch::default(),
            query_buffer: None,
            context: InputContext::default(),
            latin,
            pinyin,
            learning_database,
        })),
        Err(_) => ptr::null_mut(),
    }
}

/// Creates a runtime with the production librime engine registered as `rime`.
/// Returns null when the library was built without the `native-librime` feature.
///
/// # Safety
/// Every argument must point to a NUL-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_new_with_rime(
    engine_id: *const c_char,
    data_dir: *const c_char,
    rime_shared_dir: *const c_char,
    rime_user_dir: *const c_char,
) -> *mut ImeHandle {
    if engine_id.is_null()
        || data_dir.is_null()
        || rime_shared_dir.is_null()
        || rime_user_dir.is_null()
    {
        return ptr::null_mut();
    }
    let (Ok(engine_id), Ok(data_dir), Ok(shared_dir), Ok(user_dir)) = (
        CStr::from_ptr(engine_id).to_str(),
        CStr::from_ptr(data_dir).to_str(),
        CStr::from_ptr(rime_shared_dir).to_str(),
        CStr::from_ptr(rime_user_dir).to_str(),
    ) else {
        return ptr::null_mut();
    };
    #[cfg(feature = "native-librime")]
    {
        let Ok(backend) =
            ime_engine_rime::native::NativeRimeBackend::initialize(shared_dir, user_dir)
        else {
            return ptr::null_mut();
        };
        let engine: Arc<dyn InputEngine> = Arc::new(ime_engine_rime::RimeEngine::new(backend));
        create_handle_with_engine(engine_id, Some(Path::new(data_dir)), Some(engine))
    }
    #[cfg(not(feature = "native-librime"))]
    {
        let _ = (engine_id, data_dir, shared_dir, user_dir);
        ptr::null_mut()
    }
}

/// Creates a runtime session for `bilingual`, `latin`, or `pinyin.reference`.
///
/// # Safety
/// `engine_id` must point to a NUL-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_new_with_engine(engine_id: *const c_char) -> *mut ImeHandle {
    if engine_id.is_null() {
        return ptr::null_mut();
    }
    CStr::from_ptr(engine_id)
        .to_str()
        .map_or(ptr::null_mut(), |id| create_handle(id, None))
}

/// Creates a runtime with persistent learning data under `data_dir`.
///
/// # Safety
/// Both arguments must point to NUL-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_new_with_data_dir(
    engine_id: *const c_char,
    data_dir: *const c_char,
) -> *mut ImeHandle {
    if engine_id.is_null() || data_dir.is_null() {
        return ptr::null_mut();
    }
    let (Ok(engine_id), Ok(data_dir)) = (
        CStr::from_ptr(engine_id).to_str(),
        CStr::from_ptr(data_dir).to_str(),
    ) else {
        return ptr::null_mut();
    };
    create_handle(engine_id, Some(Path::new(data_dir)))
}

impl ImeHandle {
    fn record_actions(&mut self, actions: &ActionBatch) -> Result<(), ()> {
        let json = serde_json::to_vec(actions).map_err(|_| ())?;
        self.last_actions = CString::new(json).map_err(|_| ())?;
        self.last_action_batch = actions.clone();
        self.query_buffer = None;
        Ok(())
    }

    fn query_string(&mut self, value: &str) -> *const c_char {
        let Ok(value) = CString::new(value) else {
            return ptr::null();
        };
        self.query_buffer = Some(value);
        self.query_buffer
            .as_ref()
            .map_or(ptr::null(), |value| value.as_ptr())
    }

    fn process(&mut self, event: &InputEvent) -> Result<ActionBatch, ()> {
        let actions = self
            .runtime
            .process(&self.session, event, &self.context)
            .map_err(|_| ())?;
        self.record_actions(&actions)?;
        Ok(actions)
    }

    fn save_learning(&self) -> Result<(), ()> {
        let Some(path) = &self.learning_database else {
            return Ok(());
        };
        let current = merge_records(
            self.latin.learning_records(),
            self.pinyin.learning_records(),
        );
        let store = SqliteStore::new(path);
        store.merge_save(&current).map_err(|_| ())
    }
}

/// # Safety
/// `handle` must be null or a pointer returned by [`ime_runtime_new`] that has not been freed.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_free(handle: *mut ImeHandle) {
    if !handle.is_null() {
        let handle = Box::from_raw(handle);
        let _ = handle.save_learning();
        drop(handle);
    }
}

/// Flushes learning data transactionally. Returns 0 on success.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_flush(handle: *const ImeHandle) -> i32 {
    let Some(handle) = handle.as_ref() else {
        return -1;
    };
    handle.save_learning().map_or(-2, |()| 0)
}

/// Feeds one UTF-8 string and returns 0 on success.
///
/// # Safety
/// `handle` must be valid and `text` must point to a NUL-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_feed_utf8(handle: *mut ImeHandle, text: *const c_char) -> i32 {
    let Some(handle) = handle.as_mut() else {
        return -1;
    };
    if text.is_null() {
        return -2;
    }
    let Ok(text) = CStr::from_ptr(text).to_str() else {
        return -3;
    };
    for character in text.chars() {
        if handle
            .process(&InputEvent::Key(KeyEvent::press(Key::Character(character))))
            .is_err()
        {
            return -4;
        }
    }
    0
}

/// Commits the current composition. Returned pointer is owned by the handle and valid until the next call or free.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_commit(handle: *mut ImeHandle) -> *const c_char {
    let Some(handle) = handle.as_mut() else {
        return ptr::null();
    };
    let Ok(actions) = handle.process(&InputEvent::Key(KeyEvent::press(Key::Space))) else {
        return ptr::null();
    };
    let Some(text) = actions.committed_text().map(str::to_owned) else {
        return ptr::null();
    };
    let Ok(value) = CString::new(text) else {
        return ptr::null();
    };
    handle.last_commit = Some(value);
    handle
        .last_commit
        .as_ref()
        .map_or(ptr::null(), |value| value.as_ptr())
}

/// Switches the active child engine of a bilingual session.
///
/// # Safety
/// Both pointers must be valid and `engine_id` must be NUL-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_switch_engine(
    handle: *mut ImeHandle,
    engine_id: *const c_char,
) -> i32 {
    let Some(handle) = handle.as_mut() else {
        return -1;
    };
    if engine_id.is_null() {
        return -2;
    }
    let Ok(engine_id) = CStr::from_ptr(engine_id).to_str() else {
        return -3;
    };
    handle
        .process(&InputEvent::SwitchEngine(engine_id.to_owned()))
        .map_or(-4, |_| 0)
}

/// Sets the input scope: 0 normal, 1 password, 2 email, 3 URL, 4 code.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_set_input_scope(handle: *mut ImeHandle, scope: u32) -> i32 {
    let Some(handle) = handle.as_mut() else {
        return -1;
    };
    handle.context.scope = match scope {
        0 => InputScope::Normal,
        1 => InputScope::Password,
        2 => InputScope::Email,
        3 => InputScope::Url,
        4 => InputScope::Code,
        _ => return -2,
    };
    0
}

/// Sets per-application learning and network policy flags.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_set_privacy_policy(
    handle: *mut ImeHandle,
    learning_allowed: i32,
    network_allowed: i32,
) -> i32 {
    let Some(handle) = handle.as_mut() else {
        return -1;
    };
    handle.context.learning_allowed = learning_allowed != 0;
    handle.context.network_allowed = network_allowed != 0;
    0
}

/// Sets the non-sensitive application identifier used by local policy rules.
///
/// # Safety
/// `handle` must be valid and `application_id` must be null or NUL-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_set_application_id(
    handle: *mut ImeHandle,
    application_id: *const c_char,
) -> i32 {
    let Some(handle) = handle.as_mut() else {
        return -1;
    };
    if application_id.is_null() {
        handle.context.application_id = None;
        return 0;
    }
    let Ok(value) = CStr::from_ptr(application_id).to_str() else {
        return -2;
    };
    handle.context.application_id = (!value.is_empty()).then(|| value.to_owned());
    0
}

/// Sends a non-text key command: 0 backspace, 1 enter, 2 space, 3 escape,
/// 4 left, 5 right, 6 previous candidate page, 7 next candidate page.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_send_command(handle: *mut ImeHandle, command: u32) -> i32 {
    let Some(handle) = handle.as_mut() else {
        return -1;
    };
    let key = match command {
        0 => Key::Backspace,
        1 => Key::Enter,
        2 => Key::Space,
        3 => Key::Escape,
        4 => Key::Left,
        5 => Key::Right,
        6 => Key::PageUp,
        7 => Key::PageDown,
        _ => return -2,
    };
    handle
        .process(&InputEvent::Key(KeyEvent::press(key)))
        .map_or(-3, |_| 0)
}

/// Selects a candidate by the ID returned in the last action JSON.
///
/// # Safety
/// Both pointers must be valid and `candidate_id` must be NUL-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_select_candidate(
    handle: *mut ImeHandle,
    candidate_id: *const c_char,
) -> i32 {
    let Some(handle) = handle.as_mut() else {
        return -1;
    };
    if candidate_id.is_null() {
        return -2;
    }
    let Ok(candidate_id) = CStr::from_ptr(candidate_id).to_str() else {
        return -3;
    };
    handle
        .process(&InputEvent::SelectCandidate(ime_core::CandidateId(
            candidate_id.to_owned(),
        )))
        .map_or(-4, |_| 0)
}

/// Returns the last action batch as UTF-8 JSON, owned by the handle.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_last_actions_json(handle: *const ImeHandle) -> *const c_char {
    handle
        .as_ref()
        .map_or(ptr::null(), |value| value.last_actions.as_ptr())
}

/// Returns the number of actions in the last batch.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_action_count(handle: *const ImeHandle) -> usize {
    handle
        .as_ref()
        .map_or(0, |value| value.last_action_batch.0.len())
}

/// Returns an action kind: 1 composition, 2 candidates, 3 commit, 4 close,
/// 5 ignored, 6 candidate page metadata (JSON), or 0 for an invalid index.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_action_kind(
    handle: *const ImeHandle,
    action_index: usize,
) -> u32 {
    let Some(action) = handle
        .as_ref()
        .and_then(|value| value.last_action_batch.0.get(action_index))
    else {
        return 0;
    };
    match action {
        Action::UpdateComposition(_) => 1,
        Action::ShowCandidates(_) => 2,
        Action::CommitText(_) => 3,
        Action::CloseComposition => 4,
        Action::Ignored => 5,
        Action::CandidatePage { .. } => 6,
        Action::PinyinReadings { .. } => 7,
    }
}

/// Returns composition or commit text for an action. The returned pointer is
/// valid until the next query or mutating runtime call.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_action_text(
    handle: *mut ImeHandle,
    action_index: usize,
) -> *const c_char {
    let Some(handle) = handle.as_mut() else {
        return ptr::null();
    };
    let value = match handle.last_action_batch.0.get(action_index) {
        Some(Action::UpdateComposition(composition)) => composition.text(),
        Some(Action::CommitText(text)) => text.clone(),
        _ => return ptr::null(),
    };
    handle.query_string(&value)
}

/// Returns the candidate count for a ShowCandidates action.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_candidate_count(
    handle: *const ImeHandle,
    action_index: usize,
) -> usize {
    match handle
        .as_ref()
        .and_then(|value| value.last_action_batch.0.get(action_index))
    {
        Some(Action::ShowCandidates(candidates)) => candidates.len(),
        _ => 0,
    }
}

/// Returns a candidate field: 0 id, 1 display text, 2 commit text,
/// 3 annotation. The returned pointer is valid until the next query or
/// mutating runtime call.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_candidate_field(
    handle: *mut ImeHandle,
    action_index: usize,
    candidate_index: usize,
    field: u32,
) -> *const c_char {
    let Some(handle) = handle.as_mut() else {
        return ptr::null();
    };
    let value = match handle.last_action_batch.0.get(action_index) {
        Some(Action::ShowCandidates(candidates)) => candidates.get(candidate_index),
        _ => None,
    };
    let Some(candidate) = value else {
        return ptr::null();
    };
    let value = match field {
        0 => candidate.id.0.clone(),
        1 => candidate.display_text.clone(),
        2 => candidate.commit_text.clone(),
        3 => candidate.annotation.clone().unwrap_or_default(),
        _ => return ptr::null(),
    };
    handle.query_string(&value)
}

/// Delivers a platform/system ASR hypothesis through the common input event path.
/// `final_result=0` updates composition; any other value commits text.
///
/// # Safety
/// `handle` must be valid; text and optional language must be NUL-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_speech_result(
    handle: *mut ImeHandle,
    text: *const c_char,
    language: *const c_char,
    confidence: f32,
    final_result: i32,
) -> i32 {
    let Some(handle) = handle.as_mut() else {
        return -1;
    };
    if text.is_null() || handle.context.scope == InputScope::Password {
        return -2;
    }
    let Ok(text) = CStr::from_ptr(text).to_str() else {
        return -3;
    };
    let language = if language.is_null() {
        None
    } else {
        let Ok(value) = CStr::from_ptr(language).to_str() else {
            return -3;
        };
        (!value.is_empty()).then(|| value.to_owned())
    };
    let hypothesis = SpeechHypothesis {
        text: text.to_owned(),
        confidence: confidence.is_finite().then_some(confidence),
        language,
    };
    let event = if final_result == 0 {
        InputEvent::SpeechPartial(hypothesis)
    } else {
        InputEvent::SpeechFinal(hypothesis)
    };
    handle.process(&event).map_or(-4, |_| 0)
}

/// Records explicit positive/negative feedback. Kind: 0 selected, 1 rejected,
/// 2 deleted, 3 undone.
///
/// # Safety
/// String arguments must be non-null, NUL-terminated UTF-8; language may be null.
#[no_mangle]
pub unsafe extern "C" fn ime_runtime_feedback(
    handle: *const ImeHandle,
    candidate_id: *const c_char,
    input_signature: *const c_char,
    committed_text: *const c_char,
    kind: u32,
    language: *const c_char,
) -> i32 {
    let Some(handle) = handle.as_ref() else {
        return -1;
    };
    if candidate_id.is_null() || input_signature.is_null() || committed_text.is_null() {
        return -2;
    }
    let (Ok(candidate_id), Ok(input_signature), Ok(committed_text)) = (
        CStr::from_ptr(candidate_id).to_str(),
        CStr::from_ptr(input_signature).to_str(),
        CStr::from_ptr(committed_text).to_str(),
    ) else {
        return -3;
    };
    let kind = match kind {
        0 => FeedbackKind::Selected,
        1 => FeedbackKind::Rejected,
        2 => FeedbackKind::Deleted,
        3 => FeedbackKind::Undone,
        _ => return -4,
    };
    let language = if language.is_null() {
        None
    } else {
        let Ok(language) = CStr::from_ptr(language).to_str() else {
            return -3;
        };
        (!language.is_empty()).then(|| language.to_owned())
    };
    if !handle.context.effective_learning_allowed() {
        return -5;
    }
    handle
        .runtime
        .apply_feedback(
            &handle.session,
            &FeedbackEvent {
                candidate_id: CandidateId(candidate_id.to_owned()),
                input_signature: input_signature.to_owned(),
                committed_text: committed_text.to_owned(),
                kind,
                language,
            },
        )
        .map_or(-6, |()| 0)
}

/// Creates an offline whisper.cpp speech worker.
///
/// # Safety
/// Arguments must point to NUL-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ime_speech_whisper_new(
    executable: *const c_char,
    model: *const c_char,
    temporary_directory: *const c_char,
) -> *mut ImeSpeechHandle {
    if executable.is_null() || model.is_null() || temporary_directory.is_null() {
        return ptr::null_mut();
    }
    let (Ok(executable), Ok(model), Ok(temporary_directory)) = (
        CStr::from_ptr(executable).to_str(),
        CStr::from_ptr(model).to_str(),
        CStr::from_ptr(temporary_directory).to_str(),
    ) else {
        return ptr::null_mut();
    };
    Box::into_raw(Box::new(ImeSpeechHandle {
        controller: SpeechController::new(
            WhisperCppProvider::new(executable, model, temporary_directory),
            SpeechPolicy::default(),
        ),
        active: None,
    }))
}

/// # Safety
/// `handle` must be null or a live speech handle.
#[no_mangle]
pub unsafe extern "C" fn ime_speech_free(handle: *mut ImeSpeechHandle) {
    if !handle.is_null() {
        drop(Box::from_raw(handle));
    }
}

/// Starts one recording session. Languages are comma-separated BCP-47 tags.
///
/// # Safety
/// Handles must be valid and `languages` must be NUL-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ime_speech_start(
    speech: *mut ImeSpeechHandle,
    ime: *const ImeHandle,
    languages: *const c_char,
) -> i32 {
    start_speech(speech, ime, languages, ptr::null())
}

/// Starts speech with comma-separated local hotwords derived from the user's
/// confirmed personal lexicon.
///
/// # Safety
/// Handles and both UTF-8 string pointers must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_speech_start_with_hotwords(
    speech: *mut ImeSpeechHandle,
    ime: *const ImeHandle,
    languages: *const c_char,
    hotwords: *const c_char,
) -> i32 {
    start_speech(speech, ime, languages, hotwords)
}

unsafe fn start_speech(
    speech: *mut ImeSpeechHandle,
    ime: *const ImeHandle,
    languages: *const c_char,
    hotwords: *const c_char,
) -> i32 {
    let (Some(speech), Some(ime)) = (speech.as_mut(), ime.as_ref()) else {
        return -1;
    };
    if languages.is_null() || speech.active.is_some() {
        return -2;
    }
    let Ok(languages) = CStr::from_ptr(languages).to_str() else {
        return -3;
    };
    let hotwords = if hotwords.is_null() {
        Vec::new()
    } else {
        let Ok(values) = CStr::from_ptr(hotwords).to_str() else {
            return -3;
        };
        values
            .split(',')
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_owned)
            .collect()
    };
    let config = SpeechSessionConfig {
        languages: languages
            .split(',')
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_owned)
            .collect(),
        interim_results: false,
        offline_required: true,
        hotwords,
    };
    match speech.controller.start(&config, &ime.context) {
        Ok(handle) => {
            speech.active = Some(handle);
            0
        }
        Err(_) => -4,
    }
}

/// Pushes interleaved floating-point PCM samples.
///
/// # Safety
/// `samples` must address `sample_count` readable floats and the handle must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_speech_push_f32(
    speech: *mut ImeSpeechHandle,
    samples: *const f32,
    sample_count: usize,
    sample_rate_hz: u32,
    channels: u16,
) -> i32 {
    let Some(speech) = speech.as_mut() else {
        return -1;
    };
    let Some(active) = speech.active else {
        return -2;
    };
    if samples.is_null() && sample_count != 0 {
        return -3;
    }
    let values = if sample_count == 0 {
        Vec::new()
    } else {
        std::slice::from_raw_parts(samples, sample_count).to_vec()
    };
    speech
        .controller
        .push_audio(
            active,
            AudioFrame {
                samples: values,
                sample_rate_hz,
                channels,
            },
        )
        .map_or(-4, |()| 0)
}

/// # Safety
/// `speech` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_speech_finish(speech: *mut ImeSpeechHandle) -> i32 {
    let Some(speech) = speech.as_mut() else {
        return -1;
    };
    let Some(active) = speech.active else {
        return -2;
    };
    speech.controller.finish(active).map_or(-3, |()| 0)
}

/// Cancels the active speech session and releases its buffered audio.
///
/// # Safety
/// `speech` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_speech_cancel(speech: *mut ImeSpeechHandle) -> i32 {
    let Some(speech) = speech.as_mut() else {
        return -1;
    };
    let Some(active) = speech.active.take() else {
        return -2;
    };
    speech.controller.cancel(active);
    0
}

/// Polls without blocking and feeds partial/final results into the IME. The
/// return value is the number of action batches, zero when inference is busy,
/// or a negative error code.
///
/// # Safety
/// Both handles must be valid.
#[no_mangle]
pub unsafe extern "C" fn ime_speech_poll_into_runtime(
    speech: *mut ImeSpeechHandle,
    ime: *mut ImeHandle,
) -> i32 {
    let (Some(speech), Some(ime)) = (speech.as_mut(), ime.as_mut()) else {
        return -1;
    };
    let Some(active) = speech.active else {
        return -2;
    };
    match speech
        .controller
        .poll_into_runtime(active, &ime.runtime, &ime.session, &ime.context)
    {
        Ok(batches) => {
            for batch in &batches {
                if ime.record_actions(batch).is_err() {
                    return -4;
                }
            }
            if !speech.controller.is_active(active) {
                speech.active = None;
            }
            i32::try_from(batches.len()).unwrap_or(i32::MAX)
        }
        Err(_) => {
            speech.controller.cancel(active);
            speech.active = None;
            -3
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn c_abi_round_trip() {
        assert_eq!(ime_runtime_abi_version(), 0x0001_0003);
        assert_ne!(ime_runtime_capabilities() & 1, 0);
        let handle = ime_runtime_new();
        assert!(!handle.is_null());
        let input = CString::new("hello").expect("cstring");
        // SAFETY: handle and input are created in this test and remain valid for every call.
        unsafe {
            assert_eq!(ime_runtime_feed_utf8(handle, input.as_ptr()), 0);
            let committed = ime_runtime_commit(handle);
            assert!(!committed.is_null());
            assert_eq!(CStr::from_ptr(committed).to_str().expect("utf8"), "hello");
            ime_runtime_free(handle);
        }
    }

    #[test]
    fn c_abi_switches_to_pinyin_and_exposes_actions() {
        let handle = ime_runtime_new();
        let pinyin = CString::new("pinyin.reference").expect("engine");
        let input = CString::new("nihao").expect("input");
        unsafe {
            assert_eq!(ime_runtime_switch_engine(handle, pinyin.as_ptr()), 0);
            assert_eq!(ime_runtime_set_input_scope(handle, 0), 0);
            assert_eq!(ime_runtime_feed_utf8(handle, input.as_ptr()), 0);
            let actions = CStr::from_ptr(ime_runtime_last_actions_json(handle))
                .to_str()
                .expect("json");
            assert!(actions.contains("你好"));
            assert_eq!(ime_runtime_abi_version(), 0x0001_0003);
            assert_ne!(ime_runtime_capabilities() & (1_u64 << 5), 0);
            let action_count = ime_runtime_action_count(handle);
            assert!(action_count >= 2);
            let candidate_action = (0..action_count)
                .find(|index| ime_runtime_action_kind(handle, *index) == 2)
                .expect("candidate action");
            assert!(ime_runtime_candidate_count(handle, candidate_action) > 0);
            let display = ime_runtime_candidate_field(handle, candidate_action, 0, 1);
            assert!(!display.is_null());
            assert_eq!(CStr::from_ptr(display).to_str().expect("display"), "你好");
            assert_eq!(
                CStr::from_ptr(ime_runtime_commit(handle))
                    .to_str()
                    .expect("commit"),
                "你好"
            );
            ime_runtime_free(handle);
        }
    }

    #[test]
    fn c_abi_pinyin_return_commits_raw_and_space_commits_candidate() {
        unsafe {
            let handle = ime_runtime_new();
            assert!(!handle.is_null());
            let engine = CString::new("pinyin.reference").unwrap();
            let input = CString::new("jixu").unwrap();
            assert_eq!(ime_runtime_switch_engine(handle, engine.as_ptr()), 0);
            for (command, expected) in [(1, "jixu"), (2, "继续")] {
                assert_eq!(ime_runtime_feed_utf8(handle, input.as_ptr()), 0);
                assert_eq!(ime_runtime_send_command(handle, command), 0);
                let commits: Vec<_> = (0..ime_runtime_action_count(handle))
                    .filter(|index| ime_runtime_action_kind(handle, *index) == 3)
                    .map(|index| CStr::from_ptr(ime_runtime_action_text(handle, index)).to_str().unwrap().to_owned())
                    .collect();
                assert_eq!(commits, vec![expected]);
            }
            ime_runtime_free(handle);
        }
    }

    #[test]
    fn c_abi_rejects_unknown_input_scope() {
        let handle = ime_runtime_new();
        unsafe {
            assert_eq!(ime_runtime_set_input_scope(handle, 99), -2);
            ime_runtime_free(handle);
        }
    }

    #[test]
    fn speech_abi_honors_password_scope_before_provider_access() {
        let ime = ime_runtime_new();
        let missing = CString::new("/definitely/missing").expect("path");
        let language = CString::new("en").expect("language");
        unsafe {
            let speech =
                ime_speech_whisper_new(missing.as_ptr(), missing.as_ptr(), missing.as_ptr());
            assert!(!speech.is_null());
            assert_eq!(ime_runtime_set_input_scope(ime, 1), 0);
            assert_eq!(ime_speech_start(speech, ime, language.as_ptr()), -4);
            ime_speech_free(speech);
            ime_runtime_free(ime);
        }
    }

    #[test]
    fn system_speech_abi_uses_composition_then_commit_and_blocks_passwords() {
        let handle = ime_runtime_new();
        let partial = CString::new("hello wor").expect("partial");
        let final_text = CString::new("hello world").expect("final");
        let language = CString::new("en").expect("language");
        unsafe {
            assert_eq!(
                ime_runtime_speech_result(handle, partial.as_ptr(), language.as_ptr(), 0.7, 0,),
                0
            );
            assert!(CStr::from_ptr(ime_runtime_last_actions_json(handle))
                .to_str()
                .expect("actions")
                .contains("SpeechPartial"));
            assert_eq!(
                ime_runtime_speech_result(handle, final_text.as_ptr(), language.as_ptr(), 0.9, 1,),
                0
            );
            assert!(CStr::from_ptr(ime_runtime_last_actions_json(handle))
                .to_str()
                .expect("actions")
                .contains("hello world"));
            assert_eq!(ime_runtime_set_input_scope(handle, 1), 0);
            assert_eq!(
                ime_runtime_speech_result(handle, final_text.as_ptr(), ptr::null(), f32::NAN, 1),
                -2
            );
            ime_runtime_free(handle);
        }
    }

    #[test]
    #[ignore = "requires WHISPER_CPP_BIN, WHISPER_CPP_MODEL and WHISPER_CPP_TEST_WAV"]
    fn speech_abi_transcribes_real_audio_into_input_actions() {
        let executable = std::env::var("WHISPER_CPP_BIN").expect("whisper binary");
        let model = std::env::var("WHISPER_CPP_MODEL").expect("whisper model");
        let wav = std::env::var("WHISPER_CPP_TEST_WAV").expect("test WAV");
        let expected =
            std::env::var("WHISPER_CPP_EXPECTED").unwrap_or_else(|_| "country".to_owned());
        let language_value =
            std::env::var("WHISPER_CPP_LANGUAGE").unwrap_or_else(|_| "en".to_owned());
        let temporary = std::env::temp_dir().join("zhimo-ffi-speech");
        let (samples, sample_rate, channels) = read_pcm16_wav(Path::new(&wav));
        let executable = CString::new(executable).expect("executable");
        let model = CString::new(model).expect("model");
        let temporary = CString::new(temporary.to_str().expect("temporary")).expect("temporary");
        let language = CString::new(language_value).expect("language");
        unsafe {
            let ime = ime_runtime_new();
            let speech =
                ime_speech_whisper_new(executable.as_ptr(), model.as_ptr(), temporary.as_ptr());
            assert_eq!(ime_speech_start(speech, ime, language.as_ptr()), 0);
            assert_eq!(
                ime_speech_push_f32(
                    speech,
                    samples.as_ptr(),
                    samples.len(),
                    sample_rate,
                    channels,
                ),
                0
            );
            assert_eq!(ime_speech_finish(speech), 0);
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(120);
            loop {
                let count = ime_speech_poll_into_runtime(speech, ime);
                assert!(count >= 0, "speech poll failed with {count}");
                if count > 0 {
                    let actions = CStr::from_ptr(ime_runtime_last_actions_json(ime))
                        .to_str()
                        .expect("actions");
                    assert!(actions.to_lowercase().contains(&expected.to_lowercase()));
                    break;
                }
                assert!(std::time::Instant::now() < deadline, "speech timed out");
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            ime_speech_free(speech);
            ime_runtime_free(ime);
        }
    }

    fn read_pcm16_wav(path: &Path) -> (Vec<f32>, u32, u16) {
        let bytes = std::fs::read(path).expect("read WAV");
        let channels = u16::from_le_bytes([bytes[22], bytes[23]]);
        let sample_rate = u32::from_le_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]);
        let data_offset = bytes
            .windows(4)
            .position(|window| window == b"data")
            .map(|index| index + 8)
            .expect("data chunk");
        let samples = bytes[data_offset..]
            .chunks_exact(2)
            .map(|value| f32::from(i16::from_le_bytes([value[0], value[1]])) / 32_768.0)
            .collect();
        (samples, sample_rate, channels)
    }

    #[test]
    fn c_abi_supports_backspace_and_candidate_selection() {
        let handle = ime_runtime_new();
        let pinyin = CString::new("pinyin.reference").expect("engine");
        let input = CString::new("nihx").expect("input");
        let candidate = CString::new("pinyin:你好").expect("candidate");
        unsafe {
            assert_eq!(ime_runtime_switch_engine(handle, pinyin.as_ptr()), 0);
            assert_eq!(ime_runtime_feed_utf8(handle, input.as_ptr()), 0);
            assert_eq!(ime_runtime_send_command(handle, 0), 0);
            assert_eq!(
                ime_runtime_feed_utf8(handle, CString::new("ao").expect("suffix").as_ptr()),
                0
            );
            assert_eq!(ime_runtime_select_candidate(handle, candidate.as_ptr()), 0);
            let actions = CStr::from_ptr(ime_runtime_last_actions_json(handle))
                .to_str()
                .expect("actions");
            assert!(actions.contains("CommitText"));
            assert!(actions.contains("你好"));
            ime_runtime_free(handle);
        }
    }

    #[test]
    fn c_abi_persists_learning_across_runtime_process_lifetimes() {
        let data_dir =
            std::env::temp_dir().join(format!("zhimo-ffi-learning-{}", std::process::id()));
        std::fs::create_dir_all(&data_dir).expect("data directory");
        let engine = CString::new("bilingual").expect("engine");
        let directory = CString::new(data_dir.to_str().expect("UTF-8 path")).expect("directory");
        let pinyin = CString::new("pinyin.reference").expect("pinyin");
        let ni = CString::new("ni").expect("input");
        let mud = CString::new("pinyin:泥").expect("candidate");
        unsafe {
            let first = ime_runtime_new_with_data_dir(engine.as_ptr(), directory.as_ptr());
            assert_eq!(ime_runtime_switch_engine(first, pinyin.as_ptr()), 0);
            assert_eq!(ime_runtime_feed_utf8(first, ni.as_ptr()), 0);
            assert_eq!(ime_runtime_select_candidate(first, mud.as_ptr()), 0);
            assert_eq!(ime_runtime_flush(first), 0);
            ime_runtime_free(first);

            let second = ime_runtime_new_with_data_dir(engine.as_ptr(), directory.as_ptr());
            assert_eq!(ime_runtime_switch_engine(second, pinyin.as_ptr()), 0);
            assert_eq!(ime_runtime_feed_utf8(second, ni.as_ptr()), 0);
            let actions = CStr::from_ptr(ime_runtime_last_actions_json(second))
                .to_str()
                .expect("actions");
            assert!(
                actions.find("泥").expect("learned candidate")
                    < actions.find("你").expect("default candidate")
            );
            ime_runtime_free(second);
        }
        std::fs::remove_dir_all(data_dir).expect("cleanup");
    }

    #[cfg(feature = "native-librime")]
    #[test]
    fn c_abi_runs_native_rime_through_the_public_boundary() {
        let shared = std::env::var("ZHIMO_RIME_TEST_DATA").expect("Rime test data");
        let base = std::env::temp_dir().join(format!("zhimo-ffi-rime-{}", std::process::id()));
        let user = base.join("rime-user");
        std::fs::create_dir_all(&user).expect("Rime user directory");
        let engine = CString::new("rime").expect("engine");
        let data = CString::new(base.to_str().expect("data path")).expect("data");
        let shared = CString::new(shared).expect("shared");
        let user = CString::new(user.to_str().expect("user path")).expect("user");
        unsafe {
            let handle = ime_runtime_new_with_rime(
                engine.as_ptr(),
                data.as_ptr(),
                shared.as_ptr(),
                user.as_ptr(),
            );
            assert!(!handle.is_null());
            assert_eq!(
                ime_runtime_feed_utf8(handle, CString::new("nihao").expect("input").as_ptr()),
                0
            );
            let actions = CStr::from_ptr(ime_runtime_last_actions_json(handle))
                .to_str()
                .expect("actions");
            assert!(actions.contains("ShowCandidates"));
            assert!(!actions.is_ascii());
            ime_runtime_free(handle);
        }
        std::fs::remove_dir_all(base).expect("cleanup");
    }
}
