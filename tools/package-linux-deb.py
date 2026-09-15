#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Build distro-native debs from a CMake DESTDIR; never installs or switches an IME."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def version_tuple(value):
    if not re.fullmatch(r"\d+\.\d+", value):
        raise ValueError("glibc baseline must be MAJOR.MINOR")
    return tuple(map(int, value.split(".")))


def required_glibc(path):
    values = re.findall(r"\bGLIBC_(\d+\.\d+)\b", run("readelf", "--version-info", str(path)))
    return max(map(version_tuple, values), default=(0, 0))


def dependencies(paths, private, work):
    (work / "debian").mkdir(exist_ok=True)
    (work / "debian/control").write_text("Source: zhimo\nSection: utils\nPriority: optional\nMaintainer: 立方田 <managecode@gmail.com>\n\nPackage: zhimo-core\nArchitecture: any\nDescription: Zhimo input method\n")
    result = run("dpkg-shlibdeps", "--ignore-missing-info", "-O", "-l" + str(private),
                 *("-e" + str(path) for path in paths), cwd=work)
    match = re.search(r"^shlibs:Depends=(.*)$", result, re.M)
    if not match:
        raise RuntimeError("dpkg-shlibdeps returned no dependencies")
    return match.group(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", default="0.1.0~beta.20260914")
    parser.add_argument("--max-glibc", required=True, help="release baseline, e.g. 2.36 for Debian 12")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9][a-zA-Z0-9.+~]*", args.version):
        parser.error("Invalid package version")
    baseline = version_tuple(args.max_glibc)
    stage = args.stage.resolve(strict=True)
    triplet = run("dpkg-architecture", "-qDEB_HOST_MULTIARCH")
    arch = run("dpkg-architecture", "-qDEB_HOST_ARCH")
    library = stage / f"usr/lib/{triplet}/zhimo/libime_ffi.so"
    addon = stage / f"usr/lib/{triplet}/fcitx5/libzhimo.so"
    speech = stage / f"usr/lib/{triplet}/zhimo/libzhimo_speech.so"
    for binary in (library, addon, speech):
        if required_glibc(binary) > baseline:
            parser.error(f"{binary.name} needs glibc {required_glibc(binary)}, above {baseline}; rebuild inside target distro")
        dynamic = run("readelf", "-d", str(binary))
        for entry in re.findall(r"\((?:RUNPATH|RPATH)\).*?\[(.*?)\]", dynamic):
            if any(part and not part.startswith("$ORIGIN") for part in entry.split(":")):
                parser.error(f"Unsafe build-tree RPATH: {entry}; use cmake --install with DESTDIR")
    runtime = ctypes.CDLL(str(library))
    runtime.ime_runtime_capabilities.restype = ctypes.c_uint64
    if not runtime.ime_runtime_capabilities() & 16:
        parser.error("Distribution packages require --features native-librime")
    output = args.output.absolute()
    if output.exists():
        raise FileExistsError("Refusing to replace an existing package output")
    output.mkdir(parents=True)
    roots = {name: output / name for name in ("zhimo-core", "zhimo-ibus", "fcitx5-zhimo")}
    for path in roots.values():
        path.mkdir()
        (path / "DEBIAN").mkdir()
    def copy(source, package, relative):
        target = roots[package] / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        target.chmod(0o644)
        return target
    core = roots["zhimo-core"]
    core_library = copy(library, "zhimo-core", f"usr/lib/{triplet}/zhimo/libime_ffi.so")
    speech_library = copy(speech, "zhimo-core", f"usr/lib/{triplet}/zhimo/libzhimo_speech.so")
    for source in ("desktop_panel.py", "handwriting_panel.py", "desktop_support.py", "diagnose_desktop.py"):
        copy(ROOT / "platform/linux-ibus" / source, "zhimo-core", "usr/lib/zhimo/bin/" + source)
    shutil.copytree(ROOT / "models/speech", core / "usr/share/zhimo/models/speech")
    copy(ROOT / "assets/branding/zhimo.svg", "zhimo-core", "usr/share/icons/hicolor/scalable/apps/zhimo.svg")
    for size in (16, 24, 32, 48, 64, 128, 256, 512):
        copy(ROOT / f"assets/branding/zhimo-{size}.png", "zhimo-core", f"usr/share/icons/hicolor/{size}x{size}/apps/zhimo.png")
    plugin = copy(addon, "fcitx5-zhimo", f"usr/lib/{triplet}/fcitx5/libzhimo.so")
    for path in stage.glob("usr/share/fcitx5/*/zhimo.conf"):
        copy(path, "fcitx5-zhimo", str(path.relative_to(stage)))
    if len(list(roots["fcitx5-zhimo"].glob("usr/share/fcitx5/*/zhimo.conf"))) != 2:
        raise RuntimeError("Both Fcitx addon and inputmethod descriptors are required")
    shutil.copytree(ROOT / "models/handwriting", core / "usr/share/zhimo/models/handwriting")
    shutil.copytree(ROOT / "platform/android-ime/app/src/main/assets/rime", core / "usr/share/zhimo/rime")
    for source, target in (("zhimo_ibus.py", "zhimo-ibus"), ("desktop_client.py", "desktop_client.py")):
        path = copy(ROOT / "platform/linux-ibus" / source, "zhimo-ibus", "usr/lib/zhimo/bin/" + target)
        if target == "zhimo-ibus":
            # Use the distro interpreter that owns the python3-gi dependency.
            path.write_text("#!/usr/bin/python3\n" + path.read_text().split("\n", 1)[1])
            path.chmod(0o755)
    (roots["zhimo-ibus"] / "usr/lib/zhimo/models").symlink_to("../../share/zhimo/models")
    tree = ET.parse(ROOT / "platform/linux-ibus/zhimo.xml")
    tree.find("exec").text = "/usr/lib/zhimo/bin/zhimo-ibus --ibus"
    component = roots["zhimo-ibus"] / "usr/share/ibus/component/zhimo.xml"
    component.parent.mkdir(parents=True)
    tree.write(component, encoding="utf-8", xml_declaration=True)
    with tempfile.TemporaryDirectory(prefix="zhimo-shlibs-", dir=output) as temporary:
        work = Path(temporary)
        deps = {"zhimo-core": dependencies([core_library, speech_library], core_library.parent, work) + ", python3, python3-gi, python3-gi-cairo, gir1.2-gtk-3.0, pulseaudio-utils",
                "fcitx5-zhimo": dependencies([plugin], core_library.parent, work) + f", zhimo-core (= {args.version}), fcitx5",
                "zhimo-ibus": f"zhimo-core (= {args.version}), python3 (>= 3.10), python3-gi, gir1.2-ibus-1.0, gir1.2-gtk-3.0, ibus"}
    report = {"version": args.version, "architecture": arch, "glibc_ceiling": args.max_glibc,
              "distribution": Path("/etc/os-release").read_text(), "engine": "rime", "desktop_session_tested": False,
              "source_head": os.environ.get("ZHIMO_SOURCE_HEAD", "unavailable"),
              "base_image": os.environ.get("ZHIMO_BASE_IMAGE_USED", "unavailable"),
              "source_dirty": os.environ.get("ZHIMO_SOURCE_DIRTY", "unknown"), "files": {}}
    for name, root in roots.items():
        copy(ROOT / "LICENSE", name, f"usr/share/doc/{name}/copyright")
        copy(ROOT / "THIRD_PARTY_NOTICES.md", name, f"usr/share/doc/{name}/THIRD_PARTY_NOTICES.md")
        control = root / "DEBIAN"
        control.mkdir(exist_ok=True)
        size = sum(p.stat().st_size for p in root.rglob("*") if p.is_file() and not p.is_symlink()) // 1024
        (control / "control").write_text(f"Package: {name}\nVersion: {args.version}\nArchitecture: {arch}\nSection: utils\nPriority: optional\nMaintainer: 立方田 <managecode@gmail.com>\nHomepage: https://github.com/managecode-commits/zhimo\nDepends: {deps[name]}\nInstalled-Size: {size}\nDescription: Zhimo offline input method ({name})\n Rime Pinyin and local learning. Enable manually after installation.\n")
        (control / "md5sums").write_text("".join(f"{hashlib.md5(p.read_bytes()).hexdigest()}  {p.relative_to(root)}\n" for p in sorted(root.rglob("*")) if p.is_file() and not p.is_symlink() and control not in p.parents))
        deb = output / f"{name}_{args.version}_{arch}.deb"
        subprocess.run(["dpkg-deb", "--root-owner-group", "--build", str(root), str(deb)], check=True)
        report["files"][deb.name] = {"sha256": hashlib.sha256(deb.read_bytes()).hexdigest(), "depends": deps[name]}
    (output / "BUILD-INFO.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(output)


if __name__ == "__main__":
    main()
