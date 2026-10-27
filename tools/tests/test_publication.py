"""验证发行收集和暂存区检查的失败边界。"""

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import warnings
import zipfile


TOOLS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build_artifacts(root):
    dependency = io.BytesIO()
    with zipfile.ZipFile(dependency, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    dependency_bytes = dependency.getvalue()
    for name in ("LICENSE", "NOTICE", "THIRD-PARTY.md"):
        (root / name).write_text("发行工具测试材料：" + name, encoding="utf-8")
    supplemental = root / "licenses/fixture/LICENSE"
    supplemental.parent.mkdir(parents=True)
    supplemental.write_text("用于核对原字节的许可测试材料", encoding="utf-8")
    (root / "pom.xml").write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>org.openofdh</groupId>'
        '<artifactId>ofdh-parent</artifactId><version>0.2.0</version></project>',
        encoding="utf-8")
    for artifact in ("ofdh-core", "ofdh-pdf", "ofdh-cli"):
        target = root / artifact / "target"
        target.mkdir(parents=True)
        (target.parent / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><parent>'
            '<groupId>org.openofdh</groupId><artifactId>ofdh-parent</artifactId>'
            '<version>0.2.0</version></parent>'
            f'<artifactId>{artifact}</artifactId></project>', encoding="utf-8")
        for classifier in ("", "-sources", "-javadoc"):
            with zipfile.ZipFile(target / f"{artifact}-0.2.0{classifier}.jar", "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
                if not classifier:
                    archive.writestr(f"META-INF/maven/org.openofdh/{artifact}/pom.properties",
                                     f"groupId=org.openofdh\nartifactId={artifact}\nversion=0.2.0\n")
                    for name in ("LICENSE", "NOTICE", "THIRD-PARTY.md"):
                        archive.write(root / name, "META-INF/" + name)
                    archive.write(supplemental, "META-INF/licenses/fixture/LICENSE")
    (root / "target").mkdir()
    (root / "target/bom.json").write_text(json.dumps({
        "bomFormat": "CycloneDX", "metadata": {"component": {"version": "0.2.0"}},
        "components": [
            {"group": "org.openofdh", "name": name, "version": "0.2.0"}
            for name in ("ofdh-core", "ofdh-pdf", "ofdh-cli")
        ] + [{"group": "org.example", "name": "runtime", "version": "1.0", "scope": "required",
              "type": "library", "purl": "pkg:maven/org.example/runtime@1.0?type=jar",
              "hashes": [{"alg": "SHA-256", "content": hashlib.sha256(dependency_bytes).hexdigest()}]}]
    }), encoding="utf-8")
    (root / "target/bom.xml").write_text(
        '<bom xmlns="http://cyclonedx.org/schema/bom/1.6"><metadata><component>'
        '<version>0.2.0</version></component></metadata></bom>', encoding="utf-8")
    with zipfile.ZipFile(root / "ofdh-cli/target/ofdh-0.2.0.zip", "w") as archive:
        archive.writestr("ofdh-0.2.0/lib/runtime-1.0.jar", dependency_bytes)
        for artifact in ("ofdh-core", "ofdh-pdf", "ofdh-cli"):
            name = f"{artifact}-0.2.0.jar"
            archive.write(root / artifact / "target" / name, f"ofdh-0.2.0/lib/{name}")
        for name in ("bom.json", "bom.xml"):
            archive.write(root / "target" / name, f"ofdh-0.2.0/sbom/{name}")


class ReleaseCollectionTest(unittest.TestCase):
    def test_missing_build_does_not_create_a_version_directory(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.2.0</version></project>',
                encoding="utf-8")
            output = root / "release"
            with self.assertRaisesRegex(ValueError, "缺少发行制品"):
                module.collect(root, output)
            self.assertFalse(output.exists())

    def test_checksums_bind_each_artifact_and_existing_version_is_not_overwritten(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_artifacts(root)
            for name in ("local.jks", "local.keystore", "local.json"):
                (root / "licenses" / name).write_text("非敏感本地输入标记", encoding="utf-8")
            output = module.collect(root, root / "release")
            manifest = (output / "SHA256SUMS").read_text(encoding="utf-8")
            for line in manifest.splitlines():
                digest, name = line.split("  ", 1)
                self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())
            with self.assertRaises(FileExistsError):
                module.collect(root, root / "release")
            self.assertEqual(manifest, (output / "SHA256SUMS").read_text(encoding="utf-8"))

    def test_rejects_mixed_runtime_or_sbom_without_success_manifest(self):
        module = load("collect-release")
        for changed in ("jar", "bom"):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                build_artifacts(root)
                if changed == "jar":
                    with zipfile.ZipFile(root / "ofdh-core/target/ofdh-core-0.2.0.jar", "a") as archive:
                        archive.writestr("revision.txt", "另一次构建")
                else:
                    bom = root / "target/bom.json"
                    data = json.loads(bom.read_text(encoding="utf-8"))
                    data["version"] = 2
                    bom.write_text(json.dumps(data), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "发行包与独立制品不一致"):
                    module.collect(root, root / "release")
                self.assertFalse((root / "release/ofdh-0.2.0/SHA256SUMS").exists())

    def test_rejects_a_runtime_jar_built_for_another_version(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "ofdh-core-0.2.0.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/maven/org.openofdh/ofdh-core/pom.properties",
                                 "groupId=org.openofdh\nartifactId=ofdh-core\nversion=0.1.0\n")
            with self.assertRaisesRegex(ValueError, "模块或版本不一致"):
                module.validate_artifact(jar, "0.2.0")

    def test_rejects_missing_replaced_and_undeclared_runtime_dependencies(self):
        module = load("collect-release")
        for change in ("missing", "replaced", "extra"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                build_artifacts(root)
                distribution = root / "ofdh-cli/target/ofdh-0.2.0.zip"
                with zipfile.ZipFile(distribution) as original:
                    entries = {name: original.read(name) for name in original.namelist()}
                dependency = "ofdh-0.2.0/lib/runtime-1.0.jar"
                if change == "missing":
                    del entries[dependency]
                elif change == "replaced":
                    entries[dependency] += b"different build"
                else:
                    entries["ofdh-0.2.0/lib/undeclared-1.0.jar"] = entries[dependency]
                with zipfile.ZipFile(distribution, "w") as modified:
                    for name, data in entries.items():
                        modified.writestr(name, data)
                with self.assertRaisesRegex(ValueError, "运行依赖"):
                    module.collect(root, root / "release")
                self.assertFalse((root / "release/ofdh-0.2.0/SHA256SUMS").exists())

    def test_rejects_missing_or_malformed_runtime_descriptions_with_a_controlled_error(self):
        module = load("collect-release")
        dependency = {"group": "org.example", "name": "runtime", "version": "1.0", "scope": "required",
                      "type": "library", "purl": "pkg:maven/org.example/runtime@1.0?type=jar"}
        for value in (None, {}, {"components": {}}, {"components": [None]},
                      {"components": [dependency]},
                      {"components": [dict(dependency, hashes={})]},
                      {"components": [dict(dependency, hashes=[{"alg": "SHA-256", "content": []}])]}):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    module.required_dependencies(value, "0.2.0")

    def test_rejects_nonobject_json_bom_with_a_controlled_error(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            bom = Path(directory) / "bom.json"
            for value in ([], None, {"bomFormat": "CycloneDX", "metadata": []}):
                with self.subTest(value=value):
                    bom.write_text(json.dumps(value), encoding="utf-8")
                    with self.assertRaisesRegex(ValueError, "格式或版本"):
                        module.validate_artifact(bom, "0.2.0")

    def test_rejects_poms_swapped_between_modules(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_artifacts(root)
            (root / "ofdh-core/pom.xml").write_bytes((root / "ofdh-pdf/pom.xml").read_bytes())
            with self.assertRaisesRegex(ValueError, "POM 模块坐标"):
                module.collect(root, root / "release")
            self.assertFalse((root / "release/ofdh-0.2.0/SHA256SUMS").exists())

    def test_rejects_missing_supplemental_licenses_before_publishing_checksums(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_artifacts(root)
            jar = root / "ofdh-core/target/ofdh-core-0.2.0.jar"
            with zipfile.ZipFile(jar) as original:
                entries = {name: original.read(name) for name in original.namelist()
                           if name != "META-INF/licenses/fixture/LICENSE"}
            with zipfile.ZipFile(jar, "w") as incomplete:
                for name, data in entries.items():
                    incomplete.writestr(name, data)
            with self.assertRaisesRegex(ValueError, "缺少许可材料"):
                module.collect(root, root / "release")
            self.assertFalse((root / "release/ofdh-0.2.0/SHA256SUMS").exists())

    def test_rejects_ambiguous_duplicate_archive_members(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "distribution.zip"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(archive, "w") as contents:
                    contents.writestr("lib/core.jar", b"old build")
                    contents.writestr("lib/core.jar", b"current build")
            with self.assertRaisesRegex(ValueError, "重复成员"):
                module.validate_artifact(archive, "0.2.0")

    def test_rejects_business_files_identity_material_and_environment_files_in_archives(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "distribution.zip"
            for name in ("examples/java-sdk/input/record.ofd", "docs/original.pdf",
                         "examples/java-sdk/.env", "examples/java-sdk/identity.p12",
                         "META-INF/licenses/identity.jks", "META-INF/licenses/identity.KEYSTORE",
                         "../outside.txt"):
                with self.subTest(name=name):
                    with zipfile.ZipFile(archive, "w") as contents:
                        contents.writestr(name, "禁止进入发行包的格式验收标记")
                    with self.assertRaisesRegex(ValueError, "不允许分发"):
                        module.validate_artifact(archive, "0.2.0")

    def test_rejects_broken_archive_and_wrong_bom_version(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "broken.zip"
            archive.write_bytes(b"invalid archive")
            with self.assertRaises(zipfile.BadZipFile):
                module.validate_artifact(archive, "0.2.0")
            bom = root / "bom.json"
            bom.write_text('{"bomFormat":"CycloneDX"}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "版本"):
                module.validate_artifact(bom, "0.2.0")

    def test_rejects_missing_or_inconsistent_xml_bom_version(self):
        module = load("collect-release")
        with tempfile.TemporaryDirectory() as directory:
            bom = Path(directory) / "bom.xml"
            for element, version in (("bom", ""), ("bom", "0.1.0"), ("component", "0.2.0")):
                with self.subTest(element=element, version=version):
                    bom.write_text(
                        f'<{element} xmlns="http://cyclonedx.org/schema/bom/1.6">'
                        f'<metadata><component><version>{version}</version></component></metadata>'
                        f'</{element}>', encoding="utf-8")
                    with self.assertRaises(ValueError):
                        module.validate_artifact(bom, "0.2.0")


class PublicationInspectionTest(unittest.TestCase):
    def test_rejects_indexed_link_even_when_worktree_contains_a_regular_file(self):
        module = load("check-publication")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            module.ROOT = root.resolve()
            (root / "entry.txt").write_text("普通工作区文件", encoding="utf-8")
            blob = subprocess.check_output(
                ["git", "-C", str(root), "hash-object", "-w", "--stdin"], input=b"outside.txt").decode().strip()
            subprocess.run(["git", "-C", str(root), "update-index", "--add", "--cacheinfo",
                            f"120000,{blob},entry.txt"], check=True)
            self.assertTrue(any("Git 索引包含链接" in issue for issue in module.inspect(True)[1]))

    def test_rejects_oversized_file_before_content_read(self):
        module = load("check-publication")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            module.ROOT = root.resolve()
            module.MAX_FILE_BYTES = 8
            (root / "large.txt").write_bytes(b"123456789")
            self.assertTrue(any("单文件大小限额" in issue for issue in module.inspect()[1]))

    def test_staged_bytes_cannot_be_hidden_by_clean_worktree_content(self):
        module = load("check-publication")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            module.ROOT = root.resolve()
            key = root / "unexpected.txt"
            key.write_text("-----BEGIN " + "PRIVATE KEY-----\n测试标记", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "add", "unexpected.txt"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            key.write_text("已修改的工作区内容", encoding="utf-8")
            _, staged_issues = module.inspect(staged=True)
            _, working_issues = module.inspect(staged=False)
            self.assertEqual(1, len(staged_issues))
            self.assertIn("疑似包含私钥", staged_issues[0])
            self.assertEqual([], working_issues)

    def test_ignored_documents_are_not_read_but_forced_addition_is_rejected(self):
        module = load("check-publication")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            module.ROOT = root.resolve()
            (root / ".gitignore").write_text("*.ofd\n", encoding="utf-8")
            (root / "document.ofd").write_bytes(b"")
            self.assertEqual([], module.inspect()[1])
            subprocess.run(["git", "-C", str(root), "add", "-f", "document.ofd"], check=True)
            self.assertTrue(any("document.ofd" in issue for issue in module.inspect()[1]))


if __name__ == "__main__":
    unittest.main()
