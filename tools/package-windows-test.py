#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Bundle a MinGW x64 TSF test build and audit every non-system DLL import."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SYSTEM = {name.lower() for name in [
    "ADVAPI32.dll", "KERNEL32.dll", "msvcrt.dll", "ole32.dll", "OLEAUT32.dll",
    "SHELL32.dll", "USER32.dll", "bcryptprimitives.dll", "ntdll.dll",
    "USERENV.dll", "WS2_32.dll", "bcrypt.dll", "GDI32.dll", "COMCTL32.dll", "WINMM.dll",
]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=pathlib.Path, required=True)
    parser.add_argument("--runtime", type=pathlib.Path, required=True)
    parser.add_argument("--mingw-root", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--speech-runtime", type=pathlib.Path, help="Include desktop companion and offline models")
    parser.add_argument("--x86-build", type=pathlib.Path, help="Also bundle x86 TSF for 32-bit applications on x64 Windows")
    parser.add_argument("--x86-runtime", type=pathlib.Path)
    parser.add_argument("--x86-mingw-root", type=pathlib.Path)
    args = parser.parse_args()
    if any((args.x86_build, args.x86_runtime, args.x86_mingw_root)) and not all((args.x86_build, args.x86_runtime, args.x86_mingw_root)):
        parser.error("All three --x86-* arguments are required together")
    destination = args.output.resolve()
    archive = destination.with_suffix(".zip")
    if destination.exists() or archive.exists():
        raise FileExistsError("Refusing to replace an existing test package")
    mingw = args.mingw_root.resolve(strict=True)
    compiler = mingw / "usr/bin/x86_64-w64-mingw32-g++-posix"
    objdump = mingw / "usr/bin/x86_64-w64-mingw32-objdump"
    sources = {
        "ZhimoTsf.dll": args.build / "libZhimoTsf.dll",
        "ime_ffi.dll": args.runtime,
        "zhimo-tsf-probe.exe": args.build / "zhimo-tsf-probe.exe",
        "libwinpthread-1.dll": mingw / "usr/x86_64-w64-mingw32/lib/libwinpthread-1.dll",
    }
    for name in ["libgcc_s_seh-1.dll", "libstdc++-6.dll"]:
        sources[name] = pathlib.Path(subprocess.check_output(
            [str(compiler), "-print-file-name=" + name], text=True).strip())
    if args.speech_runtime:
        sources[args.speech_runtime.name] = args.speech_runtime
        sources["zhimo-desktop-panel.exe"] = args.build / "zhimo-desktop-panel.exe"
    if args.x86_build:
        x86_root = args.x86_mingw_root.resolve(strict=True)
        x86_compiler = x86_root / "usr/bin/i686-w64-mingw32-g++-posix"
        x86_objdump = x86_root / "usr/bin/i686-w64-mingw32-objdump"
        sources.update({
            "x86/ZhimoTsf.dll": args.x86_build / "libZhimoTsf.dll",
            "x86/ime_ffi.dll": args.x86_runtime,
            "x86/zhimo-tsf-probe.exe": args.x86_build / "zhimo-tsf-probe.exe",
            "x86/libwinpthread-1.dll": x86_root / "usr/i686-w64-mingw32/lib/libwinpthread-1.dll",
        })
        for name in ("libgcc_s_dw2-1.dll", "libstdc++-6.dll"):
            sources["x86/" + name] = pathlib.Path(subprocess.check_output(
                [str(x86_compiler), "-print-file-name=" + name], text=True).strip())
    imports = {}
    for name, path in sources.items():
        is_x86 = name.startswith("x86/")
        names = {pathlib.PurePosixPath(item).name.lower() for item in sources if item.startswith("x86/") == is_x86}
        result = subprocess.check_output([str(x86_objdump if is_x86 else objdump), "-p", str(path.resolve(strict=True))], text=True)
        expected = "pei-i386" if is_x86 else "pei-x86-64"
        if "file format " + expected not in result:
            raise ValueError(f"Not a {expected} PE: {path}")
        dependencies = re.findall(r"DLL Name:\s*(\S+)", result)
        for dependency in dependencies:
            normalized = dependency.lower()
            if normalized not in names | SYSTEM and not normalized.startswith("api-ms-win-"):
                raise ValueError(f"Unbundled dependency: {name} -> {dependency}")
        imports[name] = dependencies
        if pathlib.PurePosixPath(name).name == "ZhimoTsf.dll":
            for symbol in ["DllRegisterServer", "DllUnregisterServer", "DllGetClassObject", "DllCanUnloadNow"]:
                if not re.search(r"^\s*\[\s*\d+\]\s+(?:\+base\[[^\]]+\]\s+[0-9a-fA-F]+\s+)?" + symbol + r"\s*$", result, re.M):
                    raise ValueError(f"Missing export {symbol}")
    destination.mkdir(parents=True)
    for name, path in sources.items():
        (destination / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination / name)
    if args.speech_runtime:
        manifest = json.loads((ROOT / "models/speech/manifest.json").read_text())
        for item in (manifest, manifest["vad"]):
            model = ROOT / "models/speech" / item["file"]
            if hashlib.sha256(model.read_bytes()).hexdigest() != item["sha256"]:
                raise ValueError("Speech model hash mismatch")
        shutil.copytree(ROOT / "models/handwriting", destination / "models/handwriting")
        shutil.copytree(ROOT / "models/speech", destination / "models/speech")
    for name in ["install.cmd", "uninstall.cmd", "install.ps1", "uninstall.ps1", "TEST-INSTALL.md",
                 "diagnose-emeditor.cmd", "diagnose-emeditor.ps1", "check-installed.cmd", "check-installed.ps1"]:
        # Package text in CRLF for Windows tools; preserve repository LF sources.
        source_name = name
        if args.x86_build and name in ("install.ps1", "uninstall.ps1"):
            source_name = name.replace(".ps1", "-dual.ps1")
        if args.x86_build and name == "TEST-INSTALL.md":
            source_name = "DUAL-INSTALL.md"
        text = (ROOT / "platform/windows-tsf" / source_name).read_text(encoding="utf-8")
        (destination / name).write_bytes(text.replace("\n", "\r\n").encode("utf-8"))
    if args.x86_build:
        shutil.copy2(ROOT / "platform/windows-tsf/dual-common.ps1", destination / "dual-common.ps1")
        shutil.copy2(ROOT / "platform/windows-tsf/DUAL-INSTALL.md", destination / "DUAL-INSTALL.md")
    for name in ["LICENSE", "THIRD_PARTY_NOTICES.md"]:
        shutil.copy2(ROOT / name, destination / name)
    licenses = destination / "licenses"
    licenses.mkdir()
    for package in ["gcc-mingw-w64-base", "mingw-w64-common"]:
        shutil.copy2(mingw / "usr/share/doc" / package / "copyright", licenses / (package + "-copyright.txt"))
    for license_name in ["GPL-3", "LGPL-3"]:
        shutil.copy2(pathlib.Path("/usr/share/common-licenses") / license_name, licenses / (license_name + ".txt"))
    metadata = {
        "architecture": "x86_64+x86" if args.x86_build else "x86_64", "profile": "release", "signed": False,
        "windows_host_tested": False, "pinyin_engine": "pinyin.reference",
        "desktop_handwriting_speech": bool(args.speech_runtime),
        "source_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "source_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
        "compiler": subprocess.check_output([str(compiler), "--version"], text=True).splitlines()[0],
        "imports": imports,
    }
    if args.x86_build:
        metadata["x86_compiler"] = subprocess.check_output([str(x86_compiler), "--version"], text=True).splitlines()[0]
        metadata["desktop_companion_architecture"] = "x86_64" if args.speech_runtime else None
    (destination / "BUILD-INFO.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    hashes = {path.relative_to(destination).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in sorted(destination.rglob("*")) if path.is_file()}
    (destination / "SHA256SUMS.json").write_text(json.dumps(hashes, indent=2) + "\n", encoding="utf-8")
    with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as package:
        for path in sorted(destination.rglob("*")):
            if path.is_file():
                package.write(path, str(path.relative_to(destination.parent)))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(".zip.sha256").write_text(f"{digest}  {archive.name}\n", encoding="ascii")
    print(f"{digest}  {archive}\n{archive.stat().st_size} bytes; all PE imports accounted for")


if __name__ == "__main__":
    main()
