//! Safe, portable reader/scorer for the Zinnia v1 single-character model format.
//! Feature extraction adapted from Zinnia, Copyright (C) 2008 Taku Kudo.
//! Redistribution under BSD-3-Clause; full notice: ../third-party/ZINNIA-COPYING.
//! No downloaded executable code, unsafe pointers, network, or personal data storage.

use crate::Point;
use sha2::{Digest, Sha256};
use std::fmt::Write;

pub const MODEL_SHA256: &str = "e16153d1ff267cd479aea260d6f71a3edda8b4ba06db2d121513adda65a4449e";
pub const MODEL_SIZE: usize = 26_834_816;
const MAGIC: u32 = 0x0ef7_1821;

struct Class {
    label: String,
    bias: f32,
    weights: Vec<(u32, f32)>,
}

pub struct Model {
    classes: Vec<Class>,
}

#[derive(Debug, serde::Serialize)]
pub struct Candidate {
    pub text: String,
    /// Uncalibrated SVM margin, not a probability.
    pub score: f32,
}

struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
}
impl Reader<'_> {
    fn take<const N: usize>(&mut self) -> Result<[u8; N], String> {
        let end = self
            .position
            .checked_add(N)
            .ok_or("model offset overflow")?;
        let bytes = self
            .bytes
            .get(self.position..end)
            .ok_or("truncated model")?;
        self.position = end;
        bytes.try_into().map_err(|_| "invalid model field".into())
    }
    fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_le_bytes(self.take()?))
    }
    fn float(&mut self) -> Result<f32, String> {
        let value = f32::from_le_bytes(self.take()?);
        if value.is_finite() {
            Ok(value)
        } else {
            Err("non-finite model weight".into())
        }
    }
}

impl Model {
    /// Only the audited bundled model is accepted. Invalid cached data can be replaced from assets.
    pub fn bundled(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() != MODEL_SIZE {
            return Err("bundled handwriting model size mismatch".into());
        }
        let mut digest = String::with_capacity(64);
        for byte in Sha256::digest(bytes) {
            let _ = write!(digest, "{byte:02x}");
        }
        if digest != MODEL_SHA256 {
            return Err("bundled handwriting model checksum mismatch".into());
        }
        Self::parse(bytes)
    }

    fn parse(bytes: &[u8]) -> Result<Self, String> {
        let mut reader = Reader { bytes, position: 0 };
        if usize::try_from(reader.u32()? ^ MAGIC).ok() != Some(bytes.len()) || reader.u32()? != 1 {
            return Err("invalid Zinnia header".into());
        }
        let count = reader.u32()? as usize;
        if count == 0 || count > 65_536 {
            return Err("invalid class count".into());
        }
        let mut classes = Vec::with_capacity(count);
        for _ in 0..count {
            let raw = reader.take::<16>()?;
            let end = raw
                .iter()
                .position(|c| *c == 0)
                .ok_or("unterminated label")?;
            let label = std::str::from_utf8(&raw[..end])
                .map_err(|_| "invalid UTF-8 label")?
                .to_owned();
            if label.chars().count() != 1 || label.chars().any(char::is_control) {
                return Err("invalid label".into());
            }
            let bias = reader.float()?;
            let mut weights = Vec::new();
            let mut previous = None;
            loop {
                let index = reader.u32()?;
                let weight = reader.float()?;
                if index == u32::MAX {
                    break;
                }
                if index > 2_000_128 || previous.is_some_and(|p| index <= p) {
                    return Err("invalid feature order".into());
                }
                previous = Some(index);
                weights.push((index, weight));
            }
            classes.push(Class {
                label,
                bias,
                weights,
            });
        }
        if reader.position != bytes.len() {
            return Err("unexpected model trailer".into());
        }
        Ok(Self { classes })
    }

    #[must_use]
    pub fn class_count(&self) -> usize {
        self.classes.len()
    }

    pub fn recognize(
        &self,
        strokes: &[Vec<Point>],
        width: f32,
        height: f32,
        limit: usize,
    ) -> Result<Vec<Candidate>, String> {
        let features = features(strokes, width, height)?;
        let mut scored: Vec<_> = self
            .classes
            .iter()
            .map(|class| {
                let (mut i, mut j, mut dot) = (0, 0, 0f64);
                while i < class.weights.len() && j < features.len() {
                    let (wi, weight) = class.weights[i];
                    let (fi, value) = features[j];
                    match wi.cmp(&fi) {
                        std::cmp::Ordering::Equal => {
                            dot += f64::from(weight * value);
                            i += 1;
                            j += 1;
                        }
                        std::cmp::Ordering::Less => i += 1,
                        std::cmp::Ordering::Greater => j += 1,
                    }
                }
                // Upstream accumulates in double and stores the final score as float.
                #[allow(clippy::cast_possible_truncation)]
                let score = (f64::from(class.bias) + dot) as f32;
                (class, score)
            })
            .collect();
        scored.sort_unstable_by(|(a, sa), (b, sb)| {
            sb.total_cmp(sa).then_with(|| b.label.cmp(&a.label))
        });
        Ok(scored
            .into_iter()
            .take(limit.min(100))
            .map(|(class, score)| Candidate {
                text: class.label.clone(),
                score,
            })
            .collect())
    }

    /// Center the complete character in a square with a 10% margin. Uniform scaling
    /// preserves the character's aspect ratio; never normalize individual strokes.
    pub fn recognize_normalized(
        &self,
        strokes: &[Vec<Point>],
        width: f32,
        height: f32,
        limit: usize,
    ) -> Result<Vec<Candidate>, String> {
        // Validate the original input before computing bounds or allocating copies.
        features(strokes, width, height)?;
        let mut bounds = (
            f32::INFINITY,
            f32::INFINITY,
            f32::NEG_INFINITY,
            f32::NEG_INFINITY,
        );
        for p in strokes.iter().flatten() {
            bounds.0 = bounds.0.min(p.x);
            bounds.1 = bounds.1.min(p.y);
            bounds.2 = bounds.2.max(p.x);
            bounds.3 = bounds.3.max(p.y);
        }
        let span = (bounds.2 - bounds.0).max(bounds.3 - bounds.1);
        // A dot must not become an enormous noisy character.
        let scale = if span > 0.0 { 800.0 / span } else { 1.0 };
        let cx = bounds.0 + (bounds.2 - bounds.0) * 0.5;
        let cy = bounds.1 + (bounds.3 - bounds.1) * 0.5;
        let normalized: Vec<Vec<Point>> = strokes
            .iter()
            .map(|stroke| {
                stroke
                    .iter()
                    .map(|p| Point {
                        x: (p.x - cx) * scale + 500.0,
                        y: (p.y - cy) * scale + 500.0,
                        time_ms: p.time_ms,
                    })
                    .collect()
            })
            .collect();
        self.recognize(&normalized, 1000.0, 1000.0, limit)
    }
}

#[derive(Clone, Copy)]
struct Node {
    x: f32,
    y: f32,
}

fn basic(output: &mut Vec<(u32, f32)>, offset: u32, a: Node, b: Node) {
    let dx = b.x - a.x;
    let dy = b.y - a.y;
    let values = [
        10.0 * (dx * dx + dy * dy).sqrt(),
        dy.atan2(dx),
        10.0 * (a.x - 0.5),
        10.0 * (a.y - 0.5),
        10.0 * (b.x - 0.5),
        10.0 * (b.y - 0.5),
        (a.y - 0.5).atan2(a.x - 0.5),
        (b.y - 0.5).atan2(b.x - 0.5),
        10.0 * ((a.x - 0.5).powi(2) + (a.y - 0.5).powi(2)).sqrt(),
        10.0 * ((b.x - 0.5).powi(2) + (b.y - 0.5).powi(2)).sqrt(),
        5.0 * dx,
        5.0 * dy,
    ];
    for (i, value) in (1..=12).zip(values) {
        output.push((offset + i, value));
    }
}

fn vertices(nodes: &[Node], id: u32, sid: u32, output: &mut Vec<(u32, f32)>) {
    // Upstream only consumes heap-indexed vertices 0..=50. Prune the rest before allocation.
    if id > 50 {
        return;
    }
    let first = nodes[0];
    let last = nodes[nodes.len() - 1];
    basic(output, sid * 1000 + 20 * id, first, last);
    let a = last.x - first.x;
    let b = last.y - first.y;
    let denominator = a * a + b * b;
    if denominator == 0.0 || nodes.len() < 3 {
        return;
    }
    let c = last.y * first.x - last.x * first.y;
    let (mut best, mut maximum) = (0, -1f32);
    for (i, node) in nodes[..nodes.len() - 1].iter().enumerate() {
        let distance = (a * node.y - b * node.x + c).abs();
        if distance > maximum {
            maximum = distance;
            best = i;
        }
    }
    if maximum * maximum / denominator > 0.001 && best > 0 {
        vertices(&nodes[..=best], id * 2 + 1, sid, output);
        vertices(&nodes[best..], id * 2 + 2, sid, output);
    }
}

fn features(strokes: &[Vec<Point>], width: f32, height: f32) -> Result<Vec<(u32, f32)>, String> {
    if !width.is_finite()
        || !height.is_finite()
        || width <= 0.0
        || height <= 0.0
        || strokes.is_empty()
        || strokes.len() > 64
        || strokes.iter().map(Vec::len).sum::<usize>() > 32_768
    {
        return Err("invalid ink dimensions or stroke count".into());
    }
    let mut output = vec![(0, 1.0)];
    let mut previous = None;
    for (sid, stroke) in (0u32..).zip(strokes) {
        if stroke.is_empty()
            || stroke.len() > 4096
            || stroke.iter().any(|p| {
                !p.x.is_finite()
                    || !p.y.is_finite()
                    || p.x < 0.0
                    || p.y < 0.0
                    || p.x > width
                    || p.y > height
            })
            || stroke.windows(2).any(|p| p[0].time_ms > p[1].time_ms)
        {
            return Err("invalid ink points".into());
        }
        let nodes: Vec<_> = stroke
            .iter()
            .map(|p| Node {
                x: p.x / width,
                y: p.y / height,
            })
            .collect();
        vertices(&nodes, 0, sid, &mut output);
        if let Some(prev) = previous {
            basic(&mut output, 100_000 + sid * 1000, prev, nodes[0]);
        }
        previous = nodes.last().copied();
    }
    let count = u32::try_from(strokes.len()).map_err(|_| "too many strokes")?;
    #[allow(clippy::cast_precision_loss)]
    output.push((2_000_000, count as f32));
    output.push((2_000_000 + count, 10.0));
    output.sort_by_key(|(index, _)| *index);
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn model() -> Model {
        Model::bundled(include_bytes!(
            "../../../models/handwriting/zh-cn/handwriting-zh_CN.model"
        ))
        .unwrap()
    }
    #[test]
    fn normalized_candidates_are_translation_scale_and_canvas_invariant() {
        let model = model();
        let original = vec![
            vec![
                Point {
                    x: 200.0,
                    y: 300.0,
                    time_ms: 0,
                },
                Point {
                    x: 800.0,
                    y: 300.0,
                    time_ms: 1,
                },
            ],
            vec![
                Point {
                    x: 100.0,
                    y: 700.0,
                    time_ms: 2,
                },
                Point {
                    x: 900.0,
                    y: 700.0,
                    time_ms: 3,
                },
            ],
        ];
        let expected = model
            .recognize_normalized(&original, 1000.0, 1000.0, 100)
            .unwrap();
        assert_eq!(expected.len(), 100);
        assert!(expected.iter().take(5).any(|c| c.text == "二"));
        for (scale, dx, dy, width, height) in [
            (0.125, 700.0, 50.0, 1200.0, 300.0),
            (2.0, 100.0, 200.0, 2200.0, 1800.0),
        ] {
            let changed: Vec<Vec<Point>> = original
                .iter()
                .map(|s| {
                    s.iter()
                        .map(|p| Point {
                            x: p.x * scale + dx,
                            y: p.y * scale + dy,
                            time_ms: p.time_ms,
                        })
                        .collect()
                })
                .collect();
            let actual = model
                .recognize_normalized(&changed, width, height, 100)
                .unwrap();
            assert_eq!(
                expected.iter().map(|c| &c.text).collect::<Vec<_>>(),
                actual.iter().map(|c| &c.text).collect::<Vec<_>>()
            );
        }
        let dot = vec![vec![Point {
            x: 0.0,
            y: 0.0,
            time_ms: 0,
        }]];
        assert!(model
            .recognize_normalized(&dot, 1.0, 1.0, 5)
            .unwrap()
            .iter()
            .all(|c| c.score.is_finite()));
        assert!(model.recognize_normalized(&dot, 0.0, 1.0, 5).is_err());
    }
    #[test]
    fn real_model_recognizes_basic_characters() {
        let model = model();
        assert!(model.class_count() > 6000);
        for (expected, lines) in [
            ("一", vec![vec![(100.0, 500.0), (900.0, 500.0)]]),
            (
                "二",
                vec![
                    vec![(200.0, 300.0), (800.0, 300.0)],
                    vec![(100.0, 700.0), (900.0, 700.0)],
                ],
            ),
            (
                "人",
                vec![
                    vec![(500.0, 100.0), (420.0, 550.0), (100.0, 900.0)],
                    vec![(480.0, 400.0), (650.0, 700.0), (900.0, 900.0)],
                ],
            ),
        ] {
            let strokes: Vec<_> = lines
                .into_iter()
                .map(|line| {
                    line.into_iter()
                        .map(|(x, y)| Point { x, y, time_ms: 0 })
                        .collect()
                })
                .collect();
            let candidates = model.recognize(&strokes, 1000.0, 1000.0, 5).unwrap();
            assert!(
                candidates.iter().any(|c| c.text == expected),
                "{expected}: {candidates:?}"
            );
        }
    }
    #[test]
    fn rejects_corruption_and_malformed_input() {
        assert!(Model::bundled(b"broken").is_err());
        assert!(Model::parse(&[]).is_err());
        let model = model();
        assert!(model.recognize(&[], 100.0, 100.0, 5).is_err());
        assert!(model
            .recognize(
                &[vec![Point {
                    x: f32::NAN,
                    y: 1.0,
                    time_ms: 0
                }]],
                100.0,
                100.0,
                5
            )
            .is_err());
        // Duplicate points must remain finite rather than recurse indefinitely.
        assert!(model
            .recognize(
                &[vec![
                    Point {
                        x: 50.0,
                        y: 50.0,
                        time_ms: 0
                    };
                    100
                ]],
                100.0,
                100.0,
                5
            )
            .unwrap()
            .iter()
            .all(|c| c.score.is_finite()));
    }
}
