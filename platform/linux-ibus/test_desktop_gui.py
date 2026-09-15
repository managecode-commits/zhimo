#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Run under xvfb-run. Does not open the microphone or load speech models."""
import ctypes
import sys
import cairo
from desktop_panel import SpeechPanel
from handwriting_panel import HandwritingPanel
from gi.repository import Gtk

panel=SpeechPanel(None,None,lambda text: results.append(text))
results=[]
panel.set_devices([("test", "测试麦克风")], "ready")
panel.next_device()
assert panel.devices[panel.device_index][0] == "test"
panel.confirm()
assert results==[]
panel.finish("测试结果","请确认")
panel.confirm()
assert results==["测试结果"]
panel.busy=True
panel.next_device()
assert panel.devices[panel.device_index][0] == "test"
panel.confirm()
assert len(results)==1
panel.close()
panel.finish("late","late")
assert panel.text=="测试结果"
commits=[]
ink=HandwritingPanel(ctypes.CDLL(sys.argv[1]),lambda text: commits.append(text) or True)
assert isinstance(ink.choices.get_parent(),Gtk.Viewport)
assert isinstance(ink.choices,Gtk.Grid)
surface=cairo.ImageSurface(cairo.FORMAT_ARGB32,380,260)
ink.strokes=[[dict(x=15,y=20),dict(x=110,y=150)]]
ink.draw(ink.canvas,cairo.Context(surface))
ink.pending=[dict(x=30,y=40)]
ink.draw(ink.canvas,cairo.Context(surface))
ink.clear()
assert not ink.pending and not ink.strokes
ink.select_punctuation("，")
assert commits == ["，"]
while Gtk.events_pending(): Gtk.main_iteration_do(False)
ink.close()
ink.select_punctuation("。")
assert commits == ["，"]
print("Desktop GUI smoke passed: preview, explicit confirmation, busy/closed guards, scrollable ink panel")
