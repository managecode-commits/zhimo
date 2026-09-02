use std::sync::Arc;

use ime_core::{Action, InputContext, InputEvent, Key, KeyEvent, Runtime};
use ime_engine_latin::LatinEngine;
use ime_engine_pinyin::PinyinEngine;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut arguments = std::env::args().skip(1);
    let requested_engine = arguments.next().unwrap_or_else(|| "latin".to_owned());
    let (engine_id, input) = if requested_engine == "pinyin" {
        (
            "pinyin.reference",
            arguments.next().unwrap_or_else(|| "nihao".to_owned()),
        )
    } else {
        ("latin", requested_engine)
    };
    let mut runtime = Runtime::new();
    runtime.register_engine(Arc::new(LatinEngine::new()));
    runtime.register_engine(Arc::new(PinyinEngine::new()));
    let session = runtime.create_session(engine_id)?;

    for character in input.chars() {
        let actions = runtime.process(
            &session,
            &InputEvent::Key(KeyEvent::press(Key::Character(character))),
            &InputContext::default(),
        )?;
        for action in actions.0 {
            match action {
                Action::UpdateComposition(composition) => {
                    println!("composition: {}", composition.text());
                }
                Action::ShowCandidates(candidates) if !candidates.is_empty() => {
                    println!(
                        "candidates: {}",
                        candidates
                            .into_iter()
                            .map(|candidate| candidate.display_text)
                            .collect::<Vec<_>>()
                            .join(", ")
                    );
                }
                _ => {}
            }
        }
    }

    let result = runtime.process(
        &session,
        &InputEvent::Key(KeyEvent::press(Key::Space)),
        &InputContext::default(),
    )?;
    println!("commit: {}", result.committed_text().unwrap_or(""));
    Ok(())
}
