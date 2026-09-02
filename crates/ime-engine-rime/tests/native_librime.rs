#![cfg(feature = "native-librime")]

use std::path::PathBuf;

use ime_core::{Action, InputContext, InputEngine, InputEvent, Key, KeyEvent, SessionId};
use ime_engine_rime::{native::NativeRimeBackend, RimeEngine};

#[test]
fn native_librime_composes_and_commits_real_dictionary_text() {
    let shared_dir = std::env::var("SHURUFA_RIME_TEST_DATA")
        .expect("SHURUFA_RIME_TEST_DATA must point to librime's data/minimal directory");
    let user_dir = unique_user_dir();
    std::fs::create_dir_all(&user_dir).expect("create isolated Rime user directory");

    let backend = NativeRimeBackend::initialize(
        &shared_dir,
        user_dir.to_str().expect("UTF-8 test directory"),
    )
    .expect("initialize native librime");
    let engine = RimeEngine::new(backend);
    let session = SessionId("native-rime-test".to_owned());
    engine
        .create_session(&session)
        .expect("create Rime session");

    let mut saw_candidates = false;
    for character in "nihao".chars() {
        let actions = engine
            .process(
                &session,
                &InputEvent::Key(KeyEvent::press(Key::Character(character))),
                &InputContext::default(),
            )
            .expect("process native Rime key");
        saw_candidates |= actions
            .0
            .iter()
            .any(|action| matches!(action, Action::ShowCandidates(values) if !values.is_empty()));
    }
    assert!(
        saw_candidates,
        "librime did not expose dictionary candidates"
    );

    let actions = engine
        .process(
            &session,
            &InputEvent::Key(KeyEvent::press(Key::Space)),
            &InputContext::default(),
        )
        .expect("commit native Rime candidate");
    let committed = actions.committed_text().expect("native Rime commit");
    assert!(
        !committed.is_ascii(),
        "expected a Chinese dictionary result"
    );
    engine.close_session(&session);
}

fn unique_user_dir() -> PathBuf {
    std::env::temp_dir().join(format!(
        "shurufa-rime-test-{}-{:?}",
        std::process::id(),
        std::thread::current().id()
    ))
}
