#!/usr/bin/env python3
"""Reject non-standalone consumer POMs before any Central upload."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
MODULES = (
    "chdb-jdbc", "chdb-arrow", "chdb-native-macos-aarch64",
    "chdb-native-macos-x86_64", "chdb-native-linux-x86_64-gnu",
    "chdb-native-linux-aarch64-gnu",
)


def check(path, module, version):
    text = path.read_text(encoding="utf-8")
    pom = ET.fromstring(text)
    errors = []
    for name, expected in (("groupId", "com.clickhouse.chdb"),
                           ("artifactId", module), ("version", version)):
        if pom.findtext(f"m:{name}", namespaces=NS) != expected:
            errors.append(f"wrong {name}, expected {expected}")
    for name in ("parent", "build", "repositories", "pluginRepositories"):
        if pom.find(f"m:{name}", NS) is not None:
            errors.append(f"consumer POM must not contain {name}")
    if "${" in text:
        errors.append("unresolved Maven property")
    for name in ("name", "description", "url", "licenses/license/name",
                 "licenses/license/url", "scm/url", "developers/developer/name"):
        query = "/".join(f"m:{part}" for part in name.split("/"))
        if not (pom.findtext(query, namespaces=NS) or "").strip():
            errors.append(f"missing Central metadata: {name}")
    dependencies = pom.findall("m:dependencies/m:dependency", NS)
    jdbc = []
    for dep in dependencies:
        group = dep.findtext("m:groupId", namespaces=NS)
        artifact = dep.findtext("m:artifactId", namespaces=NS)
        dep_version = dep.findtext("m:version", namespaces=NS)
        if group == "com.clickhouse.chdb":
            if artifact not in MODULES or dep_version != version:
                errors.append(f"invalid chDB dependency: {artifact}:{dep_version}")
            if artifact == "chdb-jdbc":
                jdbc.append(dep)
        if dep.findtext("m:scope", namespaces=NS) == "test":
            errors.append("test dependency leaked into consumer POM")
    if module != "chdb-jdbc":
        if len(jdbc) != 1:
            errors.append("expected exactly one transitive JDBC dependency")
        elif (jdbc[0].findtext("m:scope", default="compile", namespaces=NS)
              not in ("compile", "runtime")
              or jdbc[0].findtext("m:optional", default="false", namespaces=NS) != "false"):
            errors.append("JDBC dependency must be non-optional and available at runtime")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--modules", nargs="+", choices=MODULES, default=MODULES)
    args = parser.parse_args()
    failed = False
    for module in args.modules:
        path = args.root / module / ".flattened-pom.xml"
        try:
            errors = check(path, module, args.version)
        except (OSError, ET.ParseError) as exc:
            errors = [str(exc)]
        for error in errors:
            print(f"{module}: {error}", file=sys.stderr)
        failed |= bool(errors)
        if not errors:
            print(f"{module}: standalone consumer POM verified")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
