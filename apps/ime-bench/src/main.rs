// Copyright © 2026 立方田 <managecode@gmail.com>
use std::sync::Arc;
use std::time::Instant;

use ime_core::{InputContext, InputEvent, Key, KeyEvent, Runtime};
use ime_data::LearningModel;
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

    let code = std::env::args()
        .nth(2)
        .unwrap_or_else(|| "nihao".to_owned());
    if code.is_empty()
        || code.len() > 128
        || !code
            .chars()
            .all(|ch| ch.is_ascii_lowercase() || ('2'..='9').contains(&ch) || ch == '\'')
    {
        return Err("code must be 1..128 pinyin/T9 characters".into());
    }
    let learning_count = std::env::args()
        .nth(3)
        .map_or(Ok(0_u32), |value| value.parse())?;
    if learning_count > 100_000 || iterations > 1_000_000 {
        return Err("benchmark size exceeds safety limit".into());
    }
    let mut learning = LearningModel::new("learned-lexicon", "zh-CN", "synthetic-benchmark");
    for index in 0..learning_count {
        let first = char::from_u32(0x4e00 + index / 20_000).ok_or("invalid synthetic word")?;
        let second = char::from_u32(0x4e00 + index % 20_000).ok_or("invalid synthetic word")?;
        learning.selected("ce'shi", &format!("测试{first}{second}"));
    }
    let mut runtime = Runtime::new();
    runtime.register_engine(Arc::new(PinyinEngine::with_learning(learning)));
    let session = runtime.create_session("pinyin.reference")?;
    let context = InputContext::default();

    let cold_started = Instant::now();
    for character in code.chars() {
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

    let cold_ns = cold_started.elapsed().as_nanos();
    let mut samples = Vec::with_capacity(iterations * (code.len() + 1));
    let started = Instant::now();
    for _ in 0..iterations {
        for character in code.chars() {
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
            "code": code,
            "synthetic_learning_records": learning_count,
            "cold_sequence_ns": cold_ns,
            "scope": "core reference engine only; excludes UI, IPC, disk and real user data",
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
