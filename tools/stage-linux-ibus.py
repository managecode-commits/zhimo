#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Stage a relocatable IBus payload without changing the desktop session.

Build the chosen Runtime first; this tool never downloads dependencies or builds
an implicit replacement. The staging directory must not already exist.
"""
import argparse
import pathlib
import shlex
import shutil
import xml.etree.ElementTree as ET


def stage(repository, library, destination, prefix):
    repository = pathlib.Path(repository).resolve()
    library = pathlib.Path(library).resolve(strict=True)
    destination = pathlib.Path(destination).absolute()
    prefix = pathlib.PurePosixPath(prefix)
    if not prefix.is_absolute() or ".." in prefix.parts or str(prefix) == "/":
        raise ValueError("prefix must be a non-root absolute path without '..'")
    if destination.exists():
        raise FileExistsError("refusing to overwrite staging directory")
    if library.read_bytes()[:4] != b"\x7fELF":
        raise ValueError("Runtime library must be an ELF shared object")
    payload = destination / prefix.relative_to("/") / "share" / "zhimo"
    binary = payload / "bin"
    binary.mkdir(parents=True)
    (payload / "lib").mkdir()
    shutil.copy2(library, payload / "lib" / "libime_ffi.so")
    for source, target in [("zhimo_ibus.py", "zhimo-ibus"),
                           ("handwriting_panel.py", "handwriting_panel.py")]:
        shutil.copy2(repository / "platform/linux-ibus" / source, binary / target)
    (binary / "zhimo-ibus").chmod(0o755)
    shutil.copytree(repository / "models/handwriting", payload / "models/handwriting")
    for name in ["LICENSE", "THIRD_PARTY_NOTICES.md"]:
        shutil.copy2(repository / name, payload / name)
    component = destination / prefix.relative_to("/") / "share/ibus/component/zhimo.xml"
    component.parent.mkdir(parents=True)
    tree = ET.parse(repository / "platform/linux-ibus/zhimo.xml")
    tree.getroot().find("exec").text = shlex.quote(str(prefix / "share/zhimo/bin/zhimo-ibus")) + " --ibus"
    tree.write(component, encoding="utf-8", xml_declaration=True)
    return component


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=pathlib.Path)
    parser.add_argument("--library", required=True, type=pathlib.Path)
    parser.add_argument("--prefix", default="/usr")
    args = parser.parse_args()
    component = stage(pathlib.Path(__file__).resolve().parents[1], args.library,
                      args.destination, args.prefix)
    print(f"Staged component: {component}; no installation or IBus restart performed.")
