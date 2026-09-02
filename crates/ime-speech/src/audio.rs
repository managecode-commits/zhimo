//! Provider-facing audio normalization and lightweight endpoint detection.

use crate::{AudioFrame, SpeechError};

/// Converts interleaved input to mono and linearly resamples it.
#[allow(
    clippy::cast_possible_truncation,
    clippy::cast_precision_loss,
    clippy::cast_sign_loss
)]
pub fn normalize_mono(frame: &AudioFrame, target_rate_hz: u32) -> Result<Vec<f32>, SpeechError> {
    if frame.sample_rate_hz == 0 || frame.channels == 0 || target_rate_hz == 0 {
        return Err(SpeechError("invalid audio format".to_owned()));
    }
    let channels = usize::from(frame.channels);
    if frame.samples.len() % channels != 0 {
        return Err(SpeechError(
            "interleaved audio does not contain complete frames".to_owned(),
        ));
    }
    let mono: Vec<f32> = frame
        .samples
        .chunks_exact(channels)
        .map(|values| values.iter().sum::<f32>() / f32::from(frame.channels))
        .collect();
    if frame.sample_rate_hz == target_rate_hz || mono.is_empty() {
        return Ok(mono);
    }

    let output_len_u64 = u64::try_from(mono.len())
        .unwrap_or(u64::MAX)
        .saturating_mul(u64::from(target_rate_hz))
        / u64::from(frame.sample_rate_hz);
    let output_len = usize::try_from(output_len_u64).unwrap_or(usize::MAX);
    let scale = f64::from(frame.sample_rate_hz) / f64::from(target_rate_hz);
    Ok((0..output_len)
        .map(|index| {
            let position = (index as f64) * scale;
            let left = position.floor() as usize;
            let right = (left + 1).min(mono.len() - 1);
            let fraction = (position - left as f64) as f32;
            mono[left] * (1.0 - fraction) + mono[right] * fraction
        })
        .collect())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VadEvent {
    Silence,
    SpeechStarted,
    Speech,
    SpeechEnded,
}

/// Small energy VAD used for endpointing and as a fallback when a provider has no VAD.
pub struct EnergyVad {
    rms_threshold: f32,
    end_silence_frames: usize,
    speaking: bool,
    silent_frames: usize,
}

impl EnergyVad {
    #[must_use]
    pub fn new(rms_threshold: f32, end_silence_frames: usize) -> Self {
        Self {
            rms_threshold,
            end_silence_frames: end_silence_frames.max(1),
            speaking: false,
            silent_frames: 0,
        }
    }

    #[allow(clippy::cast_precision_loss)]
    pub fn accept(&mut self, mono_samples: &[f32]) -> VadEvent {
        let energy = if mono_samples.is_empty() {
            0.0
        } else {
            (mono_samples.iter().map(|value| value * value).sum::<f32>()
                / mono_samples.len() as f32)
                .sqrt()
        };
        if energy >= self.rms_threshold {
            self.silent_frames = 0;
            if self.speaking {
                VadEvent::Speech
            } else {
                self.speaking = true;
                VadEvent::SpeechStarted
            }
        } else if self.speaking {
            self.silent_frames += 1;
            if self.silent_frames >= self.end_silence_frames {
                self.speaking = false;
                self.silent_frames = 0;
                VadEvent::SpeechEnded
            } else {
                VadEvent::Speech
            }
        } else {
            VadEvent::Silence
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mixes_stereo_and_resamples() {
        let output = normalize_mono(
            &AudioFrame {
                samples: vec![1.0, -1.0, 0.5, 0.5, -0.5, -0.5, 1.0, 1.0],
                sample_rate_hz: 4,
                channels: 2,
            },
            2,
        )
        .expect("normalize");
        assert_eq!(output, vec![0.0, -0.5]);
    }

    #[test]
    fn vad_reports_an_endpoint_after_configured_silence() {
        let mut vad = EnergyVad::new(0.1, 2);
        assert_eq!(vad.accept(&[0.5; 16]), VadEvent::SpeechStarted);
        assert_eq!(vad.accept(&[0.0; 16]), VadEvent::Speech);
        assert_eq!(vad.accept(&[0.0; 16]), VadEvent::SpeechEnded);
    }
}
