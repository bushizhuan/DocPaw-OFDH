"""收集已构建的发行制品并生成 SHA-256 清单，不覆盖已有的版本目录。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
MODULES = ("ofdh-core", "ofdh-pdf", "ofdh-cli")


def validate_artifact(path: Path, version: str) -> None:
    if path.suffix in {".zip", ".jar"}:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            for name in names:
                member = PurePosixPath(name.replace("\\", "/"))
                if (member.is_absolute() or ".." in member.parts
                        or member.suffix.lower() in {".pdf", ".ofd", ".p12", ".pfx", ".pem", ".key", ".jks", ".keystore", ".log"}
                        or member.name.lower() == ".env" or member.name.lower().startswith(".env.")):
                    raise ValueError(f"归档包含不允许分发的文件：{path.name} / {name}")
            if len(names) != len(set(names)):
                raise ValueError(f"归档包含重复成员：{path.name}")
            if not names or archive.testzip() is not None:
                raise ValueError(f"归档为空或成员校验失败：{path.name}")
            for module in MODULES:
                if path.name == f"{module}-{version}.jar":
                    descriptor = f"META-INF/maven/org.openofdh/{module}/pom.properties"
                    if descriptor not in archive.namelist():
                        raise ValueError(f"运行 JAR 缺少 Maven 身份：{path.name}")
                    properties = dict(line.split("=", 1) for line in
                                      archive.read(descriptor).decode("utf-8").splitlines()
                                      if line and not line.startswith("#") and "=" in line)
                    if (properties.get("groupId") != "org.openofdh"
                            or properties.get("artifactId") != module
                            or properties.get("version") != version):
                        raise ValueError(f"运行 JAR 的模块或版本不一致：{path.name}")
    elif path.suffix == ".json":
        with path.open(encoding="utf-8") as source:
            bom = json.load(source)
        metadata = bom.get("metadata") if isinstance(bom, dict) else None
        component = metadata.get("component") if isinstance(metadata, dict) else None
        if (not isinstance(bom, dict) or bom.get("bomFormat") != "CycloneDX"
                or not isinstance(component, dict) or component.get("version") != version):
            raise ValueError("JSON 依赖清单的格式或版本与项目不一致")
    else:
        document = ET.parse(path).getroot()
        if path.suffix == ".pom":
            declared = document.findtext("m:version", namespaces=NS) or document.findtext(
                "m:parent/m:version", namespaces=NS)
            group = document.findtext("m:groupId", namespaces=NS) or document.findtext(
                "m:parent/m:groupId", namespaces=NS)
            artifact = document.findtext("m:artifactId", namespaces=NS)
            if (document.tag != "{" + NS["m"] + "}project" or declared != version
                    or group != "org.openofdh" or artifact not in (*MODULES, "ofdh-parent")
                    or path.name != f"{artifact}-{version}.pom"):
                raise ValueError(f"POM 模块坐标或版本与项目不一致：{path.name}")
        else:
            namespace, separator, element = document.tag.partition("}")
            if (not separator or element != "bom"
                    or not namespace.startswith("{http://cyclonedx.org/schema/bom/")):
                raise ValueError("XML 依赖清单不是 CycloneDX 格式")
            declared = document.findtext("b:metadata/b:component/b:version",
                                         namespaces={"b": namespace[1:]})
            if declared != version:
                raise ValueError("XML 依赖清单的版本与项目不一致")


def validate_bundled_licenses(path: Path, root: Path) -> None:
    required = {f"META-INF/{name}": root / name for name in ("LICENSE", "NOTICE", "THIRD-PARTY.md")}
    for source in (root / "licenses").rglob("*"):
        relative = source.relative_to(root / "licenses")
        # 与 Maven 资源和发行 ZIP 的许可白名单一致，不要求收进误放的本地文件。
        included = (relative.as_posix() in {"README.md", "SHA256SUMS"}
                    or relative.name in {"LICENSE", "LICENSE.txt", "NOTICE", "NOTICE-DOCS"})
        if source.is_file() and included:
            required["META-INF/licenses/" + relative.as_posix()] = source
    with zipfile.ZipFile(path) as archive:
        for name, source in required.items():
            if name not in archive.namelist():
                raise ValueError(f"运行 JAR 缺少许可材料：{path.name} / {name}")
            with archive.open(name) as bundled, source.open("rb") as original:
                if (hashlib.file_digest(bundled, "sha256").digest()
                        != hashlib.file_digest(original, "sha256").digest()):
                    raise ValueError(f"运行 JAR 的许可材料与原文不一致：{path.name} / {name}")


def validate_distribution(output: Path, version: str) -> None:
    """核对 ZIP 与独立制品，阻止不同构建留下的同版本文件混装。"""
    members = {f"lib/{module}-{version}.jar": f"{module}-{version}.jar" for module in MODULES}
    members.update({"sbom/bom.json": "bom.json", "sbom/bom.xml": "bom.xml"})
    with zipfile.ZipFile(output / f"ofdh-{version}.zip") as archive:
        for member, name in members.items():
            packed = f"ofdh-{version}/{member}"
            if packed not in archive.namelist():
                raise ValueError(f"CLI 发行包缺少必要成员：{member}")
            with archive.open(packed) as incoming, (output / name).open("rb") as standalone:
                if (hashlib.file_digest(incoming, "sha256").digest()
                        != hashlib.file_digest(standalone, "sha256").digest()):
                    raise ValueError(f"CLI 发行包与独立制品不一致：{name}；请重新执行完整构建")
        with (output / "bom.json").open(encoding="utf-8") as source:
            dependencies = required_dependencies(json.load(source), version)
        prefix = f"ofdh-{version}/lib/"
        expected = {prefix + f"{module}-{version}.jar" for module in MODULES}
        expected.update(prefix + name for name in dependencies)
        actual = {name for name in archive.namelist() if name.startswith(prefix) and not name.endswith("/")}
        if actual != expected:
            raise ValueError("CLI 运行依赖清单与 SBOM 不一致，存在缺失或未声明的成员")
        for name, expected_digest in dependencies.items():
            with archive.open(prefix + name) as incoming:
                actual_digest = hashlib.file_digest(incoming, "sha256").hexdigest()
            if actual_digest != expected_digest:
                raise ValueError(f"CLI 运行依赖摘要与 SBOM 不一致：{name}")


def required_dependencies(bom: dict, version: str) -> dict[str, str]:
    """当前发行仅支持无 classifier 的必需 Maven JAR，不推断其他包类型。"""
    if not isinstance(bom, dict):
        raise ValueError("SBOM 运行依赖清单必须是 JSON 对象")
    components = bom.get("components")
    if not isinstance(components, list) or not components:
        raise ValueError("SBOM 缺少运行组件清单")
    own = set()
    dependencies = {}
    for component in components:
        if not isinstance(component, dict):
            raise ValueError("SBOM 运行组件格式不正确")
        group, name, component_version = (component.get(key) for key in ("group", "name", "version"))
        if not all(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]+", value)
                   for value in (group, name, component_version)):
            raise ValueError("SBOM 运行组件的 Maven 坐标不正确")
        if group == "org.openofdh" and name in MODULES:
            if name in own or component_version != version:
                raise ValueError("SBOM 中的项目模块重复或版本不一致")
            own.add(name)
            continue
        if (component.get("scope") != "required" or component.get("type") != "library"
                or component.get("purl") != f"pkg:maven/{group}/{name}@{component_version}?type=jar"):
            raise ValueError("SBOM 包含非受支持的必需 Maven JAR 组件")
        hashes = component.get("hashes")
        if not isinstance(hashes, list):
            raise ValueError("SBOM 运行组件缺少 SHA-256 摘要")
        sha256 = [item.get("content") for item in hashes
                  if isinstance(item, dict) and item.get("alg") == "SHA-256"]
        if (len(sha256) != 1 or not isinstance(sha256[0], str)
                or not re.fullmatch(r"[0-9a-fA-F]{64}", sha256[0])):
            raise ValueError("SBOM 运行组件的 SHA-256 摘要缺失或不正确")
        filename = f"{name}-{component_version}.jar"
        if filename in dependencies or filename in {f"{module}-{version}.jar" for module in MODULES}:
            raise ValueError("SBOM 运行组件的文件名存在冲突")
        dependencies[filename] = sha256[0].lower()
    if own != set(MODULES):
        raise ValueError("SBOM 缺少项目运行模块")
    return dependencies


def collect(root: Path, destination: Path) -> Path:
    version = ET.parse(root / "pom.xml").getroot().findtext("m:version", namespaces=NS)
    if not version or not all(character.isalnum() or character in ".-" for character in version):
        raise ValueError("项目版本不合法")
    files = {
        f"ofdh-{version}.zip": root / "ofdh-cli" / "target" / f"ofdh-{version}.zip",
        f"ofdh-parent-{version}.pom": root / "pom.xml",
        "bom.json": root / "target" / "bom.json",
        "bom.xml": root / "target" / "bom.xml",
    }
    for module in MODULES:
        files[f"{module}-{version}.pom"] = root / module / "pom.xml"
        for classifier in ("", "-sources", "-javadoc"):
            name = f"{module}-{version}{classifier}.jar"
            files[name] = root / module / "target" / name
    for name, source in files.items():
        if not source.is_file() or source.is_symlink():
            raise ValueError(f"缺少发行制品：{name}；请先完成 Maven install")
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / f"ofdh-{version}"
    output.mkdir()
    # 版本目录使用独占创建；失败时留下目录供排查，不改写已冻结的制品。
    checksums = []
    for name, source in sorted(files.items()):
        target = output / name
        with source.open("rb") as incoming, target.open("xb") as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        validate_artifact(target, version)
        if name in {f"{module}-{version}.jar" for module in MODULES}:
            validate_bundled_licenses(target, root)
        with target.open("rb") as payload:
            digest = hashlib.file_digest(payload, "sha256").hexdigest()
        checksums.append(f"{digest}  {name}")
        if name.endswith(".zip"):
            (output / (name + ".sha256")).write_text(checksums[-1] + "\n", encoding="utf-8")
    validate_distribution(output, version)
    with (output / "SHA256SUMS").open("x", encoding="utf-8", newline="\n") as manifest:
        manifest.write("\n".join(checksums) + "\n")
    return output


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist",
                        help="存放版本目录的父目录")
    args = parser.parse_args()
    try:
        output = collect(ROOT, args.output.resolve())
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"发行制品收集失败：{error}", file=sys.stderr)
        return 1
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
