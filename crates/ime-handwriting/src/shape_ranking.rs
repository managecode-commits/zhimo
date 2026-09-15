// Copyright © 2026 立方田 <managecode@gmail.com>
//! Conservative Android-derived 天/夫 tie-break. Never invents model candidates.
use crate::{zinnia::Candidate, Point};

// Geometric coordinates use x/y and bounds; keep conservative guards together.
#[allow(clippy::many_single_char_names, clippy::too_many_lines)]
pub(crate) fn rank(strokes: &[Vec<Point>], choices: &mut Vec<Candidate>) {
    if strokes.len() != 4
        || strokes.iter().any(|s| s.len() < 2)
        || !choices
            .iter()
            .take(3)
            .any(|c| c.text == "天" || c.text == "夫")
    {
        return;
    }
    let bounds = |s: &[Point]| {
        s.iter().fold(
            (
                f32::INFINITY,
                f32::INFINITY,
                f32::NEG_INFINITY,
                f32::NEG_INFINITY,
            ),
            |(x, y, r, b), p| (x.min(p.x), y.min(p.y), r.max(p.x), b.max(p.y)),
        )
    };
    let all: Vec<_> = strokes.iter().flatten().copied().collect();
    let (_, y, _, b) = bounds(&all);
    let h = b - y;
    if h <= 0.0 {
        return;
    }
    let mut bars: Vec<_> = strokes
        .iter()
        .enumerate()
        .filter(|(_, s)| {
            let (x, y, r, b) = bounds(s);
            r - x > h * 0.45 && b - y < (r - x) * 0.24
        })
        .collect();
    bars.sort_by(|a, b| {
        let mean = |s: &[Point]| {
            let (sum, count) = s.iter().fold((0.0_f32, 0.0_f32), |(sum, count), p| {
                (sum + p.y, count + 1.0)
            });
            sum / count
        };
        mean(a.1).total_cmp(&mean(b.1))
    });
    if bars.len() != 2 {
        return;
    }
    let (tx, _, tr, _) = bounds(bars[0].1);
    let (bx, _, br, _) = bounds(bars[1].1);
    if tr - tx > (br - bx) * 0.95 {
        return;
    }
    let legs: Vec<_> = strokes
        .iter()
        .enumerate()
        .filter(|(i, _)| *i != bars[0].0 && *i != bars[1].0)
        .map(|(_, s)| s)
        .collect();
    let lefts: Vec<_> = legs
        .iter()
        .filter(|s| {
            s.last().unwrap().x < s[0].x - h * 0.18 && s.last().unwrap().y > s[0].y + h * 0.45
        })
        .collect();
    if lefts.len() != 1 {
        return;
    }
    let left = *lefts[0];
    let right = if std::ptr::eq(left, legs[0]) {
        legs[1]
    } else {
        legs[0]
    };
    if right.last().unwrap().x < right[0].x + h * 0.25
        || right.last().unwrap().y < right[0].y + h * 0.10
    {
        return;
    }
    let start = left[0];
    if start.x < tx || start.x > tr {
        return;
    }
    let bar_y = |s: &[Point]| {
        let l = s.iter().min_by(|a, b| a.x.total_cmp(&b.x)).unwrap();
        let r = s.iter().max_by(|a, b| a.x.total_cmp(&b.x)).unwrap();
        l.y + (r.y - l.y) * (start.x - l.x) / (r.x - l.x)
    };
    let top = bar_y(bars[0].1);
    let bottom = bar_y(bars[1].1);
    if bottom - top < h * 0.2
        || left.last().unwrap().y < bottom + h * 0.2
        || right[0].y < bottom - h * 0.15
    {
        return;
    }
    let preferred = if start.y > top + h * 0.025 && start.y < bottom - h * 0.1 {
        "天"
    } else if start.y < top - h * 0.10 {
        "夫"
    } else {
        return;
    };
    let Some(index) = choices.iter().position(|c| c.text == preferred) else {
        return;
    };
    let anchor = choices
        .iter()
        .position(|c| c.text == "天" || c.text == "夫")
        .unwrap();
    if index > anchor {
        let value = choices.remove(index);
        choices.insert(anchor, value);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn ink(start: f32) -> Vec<Vec<Point>> {
        [
            [(25., 20.), (65., 20.)],
            [(10., 50.), (90., 50.)],
            [(45., start), (20., 100.)],
            [(50., 55.), (90., 90.)],
        ]
        .into_iter()
        .map(|s| {
            s.into_iter()
                .map(|(x, y)| Point { x, y, time_ms: 0 })
                .collect()
        })
        .collect()
    }
    fn choices() -> Vec<Candidate> {
        ["夫", "天", "大"]
            .into_iter()
            .map(|t| Candidate {
                text: t.into(),
                score: 1.,
            })
            .collect()
    }
    #[test]
    fn distinguishes_without_forcing_uncertain_ink() {
        let mut c = choices();
        rank(&ink(28.), &mut c);
        assert_eq!(c[0].text, "天");
        let mut c = choices();
        rank(&ink(0.), &mut c);
        assert_eq!(c[0].text, "夫");
        let mut c = choices();
        rank(&ink(20.), &mut c);
        assert_eq!(c[0].text, "夫");
        let mut c = choices();
        c.retain(|v| v.text != "天");
        rank(&ink(28.), &mut c);
        assert_eq!(c.len(), 2);
        let mut c = choices();
        rank(&ink(28.)[..3], &mut c);
        assert_eq!(c[0].text, "夫");
    }
}
