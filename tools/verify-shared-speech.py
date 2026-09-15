#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Opt-in native C ABI check with public PCM16 mono 16kHz speech fixtures."""
import argparse
import array
import ctypes
import pathlib
import sys
import threading
import time
import wave


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--english", required=True)
    parser.add_argument("--chinese", required=True)
    parser.add_argument("--vad", default="")
    args = parser.parse_args()
    library = ctypes.CDLL(str(pathlib.Path(args.library).resolve(strict=True)))
    library.zhimo_speech_create.restype = ctypes.c_uint64
    for name in ["cancel", "release"]:
        method = getattr(library, "zhimo_speech_" + name)
        method.argtypes = [ctypes.c_uint64]
        method.restype = None
    library.zhimo_speech_transcribe.argtypes = [ctypes.c_uint64, ctypes.c_char_p,
        ctypes.POINTER(ctypes.c_float), ctypes.c_size_t, ctypes.c_char_p,
        ctypes.POINTER(ctypes.c_void_p)]
    library.zhimo_speech_transcribe.restype = ctypes.c_int
    library.zhimo_speech_transcribe_options.argtypes = library.zhimo_speech_transcribe.argtypes[:-1] + [ctypes.c_char_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
    library.zhimo_speech_transcribe_options.restype = ctypes.c_int
    library.zhimo_speech_text_free.argtypes = [ctypes.c_void_p]
    library.zhimo_speech_text_free.restype = None
    model = str(pathlib.Path(args.model).resolve(strict=True)).encode("utf-8")

    def load(path):
        with wave.open(path, "rb") as source:
            assert (source.getnchannels(), source.getsampwidth(), source.getframerate()) == (1, 2, 16000)
            assert 1600 <= source.getnframes() <= 960000
            audio = array.array("h", source.readframes(source.getnframes()))
            if sys.byteorder != "little":
                audio.byteswap()
        return (ctypes.c_float * len(audio))(*(sample / 32768 for sample in audio))

    def transcribe(session, pcm, language):
        output = ctypes.c_void_p()
        code = library.zhimo_speech_transcribe_options(session, model, pcm, len(pcm), language,
            b"", args.vad.encode(), ctypes.byref(output))
        try:
            return code, ctypes.string_at(output).decode("utf-8") if output else None
        finally:
            library.zhimo_speech_text_free(output)

    for path, language in [(args.english, b"en"), (args.chinese, b"zh")]:
        pcm = load(path)
        session = library.zhimo_speech_create()
        assert session
        started = time.monotonic()
        try:
            code, text = transcribe(session, pcm, language)
            assert code == 0 and text
            if language == b"en":
                assert "country" in text.lower(), text
            else:
                assert any("\u4e00" <= char <= "\u9fff" for char in text), text
            print(f"Public {language.decode()} fixture passed in {time.monotonic() - started:.1f}s: {text}", flush=True)
        finally:
            library.zhimo_speech_release(session)

    session = library.zhimo_speech_create()
    pcm = load(args.english)
    result = []
    worker = threading.Thread(target=lambda: result.append(transcribe(session, pcm, b"en")))
    worker.start()
    time.sleep(0.5)
    library.zhimo_speech_cancel(session)
    worker.join(30)
    try:
        assert not worker.is_alive(), "cancellation deadline exceeded"
        assert result == [(3, None)], result
        print("Native inference cancellation passed", flush=True)
    finally:
        library.zhimo_speech_release(session)


if __name__ == "__main__":
    main()
