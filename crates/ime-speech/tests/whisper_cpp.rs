// Copyright © 2026 立方田 <managecode@gmail.com>
use std::path::PathBuf;
use std::time::{Duration, Instant};

use ime_speech::whisper_cpp::WhisperCppProvider;
use ime_speech::{
    AudioFrame, SpeechEvent, SpeechProvider, SpeechSessionConfig, SpeechSessionHandle,
};

#[test]
#[ignore = "requires WHISPER_CPP_BIN, WHISPER_CPP_MODEL and WHISPER_CPP_TEST_WAV"]
fn transcribes_real_audio_without_blocking_poll() {
    let executable = required_path("WHISPER_CPP_BIN");
    let model = required_path("WHISPER_CPP_MODEL");
    let wav = required_path("WHISPER_CPP_TEST_WAV");
    let language = std::env::var("WHISPER_CPP_LANGUAGE").unwrap_or_else(|_| "en".to_owned());
    let (samples, sample_rate, channels) = read_pcm16_wav(&wav);
    let mut provider = WhisperCppProvider::new(
        executable,
        model,
        std::env::temp_dir().join("zhimo-whisper-tests"),
    );
    let handle = provider
        .start(&SpeechSessionConfig {
            languages: vec![language],
            interim_results: false,
            offline_required: true,
            hotwords: Vec::new(),
        })
        .expect("start whisper.cpp");
    assert_eq!(
        provider.poll(handle).expect("ready"),
        vec![SpeechEvent::Ready]
    );
    provider
        .push_audio(
            handle,
            AudioFrame {
                samples,
                sample_rate_hz: sample_rate,
                channels,
            },
        )
        .expect("push real audio");
    provider.finish(handle).expect("start inference worker");

    let transcript = poll_until_final(&mut provider, handle);
    let expected = std::env::var("WHISPER_CPP_EXPECTED").unwrap_or_else(|_| "country".to_owned());
    assert!(
        transcript.to_lowercase().contains(&expected.to_lowercase()),
        "unexpected transcript: {transcript}"
    );
}

fn poll_until_final(provider: &mut WhisperCppProvider, handle: SpeechSessionHandle) -> String {
    let deadline = Instant::now() + Duration::from_secs(120);
    while Instant::now() < deadline {
        for event in provider.poll(handle).expect("poll inference") {
            if let SpeechEvent::Final(value) = event {
                return value.text;
            }
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    panic!("whisper.cpp inference timed out");
}

fn required_path(name: &str) -> PathBuf {
    PathBuf::from(std::env::var(name).unwrap_or_else(|_| panic!("{name} is required")))
}

fn read_pcm16_wav(path: &PathBuf) -> (Vec<f32>, u32, u16) {
    let bytes = std::fs::read(path).expect("read test WAV");
    assert_eq!(&bytes[0..4], b"RIFF");
    assert_eq!(&bytes[8..12], b"WAVE");
    let channels = u16::from_le_bytes([bytes[22], bytes[23]]);
    let sample_rate = u32::from_le_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]);
    let bits = u16::from_le_bytes([bytes[34], bytes[35]]);
    assert_eq!(bits, 16, "test helper expects PCM16 WAV");
    let data_offset = bytes
        .windows(4)
        .position(|window| window == b"data")
        .map(|index| index + 8)
        .expect("WAV data chunk");
    let samples = bytes[data_offset..]
        .chunks_exact(2)
        .map(|value| f32::from(i16::from_le_bytes([value[0], value[1]])) / 32_768.0)
        .collect();
    (samples, sample_rate, channels)
}
