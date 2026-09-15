#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Exercise the shipped dictionary through IBus Runtime, without registering IBus."""
import argparse
import importlib.util
from importlib.machinery import SourceFileLoader
import os
import signal
import sys
from pathlib import Path
import tempfile

root = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library', type=Path)
parser.add_argument('--shared', type=Path)
parser.add_argument('--installed', action='store_true')
args = parser.parse_args()
if not args.installed and not (args.library and args.shared):
    parser.error('Use --installed or provide --library and --shared')
with tempfile.TemporaryDirectory(prefix='zhimo-rime-smoke-') as directory:
    os.environ['ZHIMO_USER_DATA_DIR'] = directory
    if args.installed:
        for key in ['ZHIMO_IME_LIBRARY', 'ZHIMO_RIME_SHARED_DIR', 'ZHIMO_RIME_USER_DIR']:
            os.environ.pop(key, None)
        source = Path('/usr/lib/zhimo/bin/zhimo-ibus')
    else:
        source = root / 'platform/linux-ibus/zhimo_ibus.py'
        os.environ.update(ZHIMO_IME_LIBRARY=str(args.library.resolve()),
                      ZHIMO_RIME_SHARED_DIR=str(args.shared.resolve()),
                      ZHIMO_RIME_USER_DIR=str(Path(directory) / 'rime'))
    spec = importlib.util.spec_from_loader('zhimo_ibus', SourceFileLoader('zhimo_ibus', str(source)))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    signal_source = module.unix_signal_add(module.GLib.PRIORITY_DEFAULT, signal.SIGUSR1, lambda: False)
    module.GLib.source_remove(signal_source)
    runtime = module.Runtime()
    try:
        assert runtime.pinyin_engine == 'rime'
        for pinyin, expected in [('nihao', '你好'), ('lvdai', '履带'), ('wulianwang', '物联网'), ('zhimo', '知墨'), ("zhi'mo", '知墨'), ('nh', '你好'), ('wm', '我们'), ('zg', '中国'), ('nih', '你好'), ('nhao', '你好'), ('wulw', '物联网'), ("n'h", '你好')]:
            runtime.library.ime_runtime_send_command(runtime.handle, 3)
            for char in pinyin:
                assert runtime.library.ime_runtime_feed_utf8(runtime.handle, char.encode()) == 0
            actions = runtime.actions()
            candidates = [candidate for action in actions for candidate in action.get('ShowCandidates', [])]
            match = next((candidate for candidate in candidates if candidate['commit_text'] == expected), None)
            assert match, (pinyin, actions)
            if pinyin in ('nh', 'nih', 'nhao', "n'h"):
                assert 'ni' in (match.get('annotation') or '') and 'hao' in match['annotation'], match
            assert runtime.library.ime_runtime_select_candidate(runtime.handle, match['id'].encode()) == 0
            assert any(action.get('CommitText') == expected for action in runtime.actions()), pinyin
            print(f'{pinyin}: {expected} candidate and commit OK')
    finally:
        runtime.library.ime_runtime_free(runtime.handle)
        runtime.handle = None
