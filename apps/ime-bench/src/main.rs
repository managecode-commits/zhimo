// Copyright © 2026 立方田 <managecode@gmail.com>
use std::sync::Arc;
use std::time::Instant;

use ime_core::{InputContext, InputEvent, Key, KeyEvent, Runtime};
use ime_engine_pinyin::PinyinEngine;

fn percentile(sorted: &[u128], numerator: usize, denominator: usize) -> u128 {
    let index = sorted
        .len()
        .saturating_mul(numerator)
        .div_ceil(denominator)
        .saturating_sub(1)
        .min(sorted.len().saturating_sub(1));
    sorted[index]
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let iterations = std::env::args()
        .nth(1)
        .map_or(Ok(20_000_usize), |value| value.parse())?;
    if iterations == 0 {
        return Err("iterations must be greater than zero".into());
    }

    let mut runtime = Runtime::new();
    runtime.register_engine(Arc::new(PinyinEngine::new()));
    let session = runtime.create_session("pinyin.reference")?;
    let context = InputContext::default();

    for character in "nihao".chars() {
        runtime.process(
            &session,
            &InputEvent::Key(KeyEvent::press(Key::Character(character))),
            &context,
        )?;
    }
    runtime.process(
        &session,
        &InputEvent::Key(KeyEvent::press(Key::Escape)),
        &context,
    )?;

    let mut samples = Vec::with_capacity(iterations * 6);
    let started = Instant::now();
    for _ in 0..iterations {
        for character in "nihao".chars() {
            let event_started = Instant::now();
            runtime.process(
                &session,
                &InputEvent::Key(KeyEvent::press(Key::Character(character))),
                &context,
            )?;
            samples.push(event_started.elapsed().as_nanos());
        }
        let event_started = Instant::now();
        runtime.process(
            &session,
            &InputEvent::Key(KeyEvent::press(Key::Escape)),
            &context,
        )?;
        samples.push(event_started.elapsed().as_nanos());
    }
    let elapsed = started.elapsed();
    samples.sort_unstable();
    let events_per_second = u128::try_from(samples.len())
        .unwrap_or(u128::MAX)
        .saturating_mul(1_000_000_000)
        / elapsed.as_nanos().max(1);
    println!(
        "{}",
        serde_json::json!({
            "iterations": iterations,
            "events": samples.len(),
            "elapsed_ms": elapsed.as_millis(),
            "events_per_second": events_per_second,
            "latency_ns": {
                "p50": percentile(&samples, 50, 100),
                "p95": percentile(&samples, 95, 100),
                "p99": percentile(&samples, 99, 100),
                "max": samples.last().copied().unwrap_or_default(),
            }
        })
    );
    Ok(())
}
