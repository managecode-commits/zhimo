use std::path::{Path, PathBuf};
use std::time::Duration;

use ime_speech::whisper_cpp::WhisperCppProvider;
use ime_speech::{AudioFrame, SpeechEvent, SpeechProvider, SpeechSessionConfig};

fn main() {
    if let Err(error) = run() {
        eprintln!("transcription failed: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let arguments = std::env::args_os().skip(1).collect::<Vec<_>>();
    if arguments.len() < 3 || arguments.len() > 4 {
        return Err(
            "usage: transcribe-cli <whisper-cli> <model.bin> <audio.wav> [language|auto]"
                .to_owned(),
        );
    }
    let executable = PathBuf::from(&arguments[0]);
    let model = PathBuf::from(&arguments[1]);
    let audio = PathBuf::from(&arguments[2]);
    let language = arguments
        .get(3)
        .and_then(|value| value.to_str())
        .unwrap_or("auto")
        .to_owned();
    let languages = (language != "auto")
        .then_some(language)
        .into_iter()
        .collect();
    let (samples, sample_rate_hz, channels) = read_pcm16_wav(&audio)?;
    let mut provider = WhisperCppProvider::new(
        executable,
        model,
        std::env::temp_dir().join("shurufa-transcribe"),
    );
    let handle = provider
        .start(&SpeechSessionConfig {
            languages,
            interim_results: false,
            offline_required: true,
            hotwords: Vec::new(),
        })
        .map_err(|error| error.to_string())?;
    provider
        .push_audio(
            handle,
            AudioFrame {
                samples,
                sample_rate_hz,
                channels,
            },
        )
        .map_err(|error| error.to_string())?;
    provider.finish(handle).map_err(|error| error.to_string())?;
    loop {
        for event in provider.poll(handle).map_err(|error| error.to_string())? {
            if let SpeechEvent::Final(result) = event {
                println!("{}", result.text);
                return Ok(());
            }
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

fn read_pcm16_wav(path: &Path) -> Result<(Vec<f32>, u32, u16), String> {
    let bytes = std::fs::read(path).map_err(|error| format!("cannot read WAV: {error}"))?;
    if bytes.len() < 44 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err("input is not a RIFF/WAVE file".to_owned());
    }
    let channels = u16::from_le_bytes([bytes[22], bytes[23]]);
    let sample_rate = u32::from_le_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]);
    let bits = u16::from_le_bytes([bytes[34], bytes[35]]);
    if bits != 16 {
        return Err("only PCM16 WAV input is currently supported".to_owned());
    }
    let data_offset = bytes
        .windows(4)
        .position(|window| window == b"data")
        .map(|index| index + 8)
        .ok_or_else(|| "WAV has no data chunk".to_owned())?;
    let samples = bytes[data_offset..]
        .chunks_exact(2)
        .map(|value| f32::from(i16::from_le_bytes([value[0], value[1]])) / 32_768.0)
        .collect();
    Ok((samples, sample_rate, channels))
}
