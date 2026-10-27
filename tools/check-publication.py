"""检查 Git 候选文件、文档链接及公开错误码目录，不读取被忽略的业务文件。"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
MAX_FILE_BYTES = 10 * 1024 * 1024
BLOCKED_SUFFIXES = {".p12", ".pfx", ".key", ".pem", ".jks", ".keystore", ".pdf", ".ofd", ".log"}
BLOCKED_DIRECTORIES = {"target", "node_modules", ".idea", ".vscode"}
SENSITIVE_PATTERNS = (
    re.compile(r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
)
LOCAL_PATHS = re.compile(r"(?:E:[\\/]+Super_Emp|C:[\\/]+Users[\\/]+Administrator)", re.I)


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(ROOT), *args], stderr=subprocess.PIPE)


def inspect(staged: bool = False) -> tuple[int, list[str]]:
    indexed = {}
    for entry in git("ls-files", "--stage", "-z").decode("utf-8").split("\0"):
        if entry:
            metadata, name = entry.split("\t", 1)
            indexed[name] = metadata.split()
    names = sorted(indexed) if staged else sorted(set(name for name in git(
        "ls-files", "--cached", "--others", "--exclude-standard", "-z"
    ).decode("utf-8").split("\0") if name))
    issues: list[str] = []
    texts: dict[str, str] = {}
    for name in names:
        path = ROOT / name
        if name in indexed and (indexed[name][0] not in {"100644", "100755"}
                                or indexed[name][2] != "0"):
            issues.append(f"{name}：Git 索引包含链接、子模块或未解决的合并记录")
            continue
        if not staged and not path.exists():
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            issues.append(f"{name}：不能包含链接或工作区外文件")
            continue
        if (path.suffix.lower() in BLOCKED_SUFFIXES
                or BLOCKED_DIRECTORIES.intersection(Path(name).parts)
                or Path(name).parts[0] == "dist"
                or path.name == ".env" or path.name.startswith(".env.")):
            issues.append(f"{name}：包含不应提交的文档、身份材料或构建产物")
            continue
        size = int(git("cat-file", "-s", indexed[name][1])) if staged else path.stat().st_size
        if size > MAX_FILE_BYTES:
            issues.append(f"{name}：超过源码提交的单文件大小限额")
            continue
        if staged:
            # 以索引快照的对象 ID 读取，避免检查期间修改索引造成两次读取不一致。
            content = git("cat-file", "blob", indexed[name][1])
        else:
            with path.open("rb") as source:
                content = source.read(MAX_FILE_BYTES + 1)
        if len(content) > MAX_FILE_BYTES:
            issues.append(f"{name}：文件在检查期间增长，超过大小限额")
            continue
        try:
            value = content.decode("utf-8")
        except UnicodeDecodeError:
            continue
        texts[name] = value
        if any(pattern.search(value) for pattern in SENSITIVE_PATTERNS):
            issues.append(f"{name}：疑似包含私钥或访问凭据，请在本地检查")
        if LOCAL_PATHS.search(value):
            issues.append(f"{name}：包含开发机器的私有绝对路径")

    if not staged:
        for name, value in texts.items():
            if not name.endswith(".md"):
                continue
            for target in re.findall(r"\]\(([^\n)]+)\)", value):
                target = target.strip().strip("<>").split("#", 1)[0]
                if not target or re.match(r"[a-z][a-z0-9+.-]*:", target, re.I):
                    continue
                destination = (ROOT / name).parent / unquote(target)
                if not destination.resolve().is_relative_to(ROOT) or not destination.exists():
                    issues.append(f"{name}：本地文档链接不存在或超出仓库：{target}")
        source = "\n".join(value for name, value in texts.items() if "/src/main/java/" in name)
        codes = set(re.findall(r'new\s+OfdhException\(\s*"([A-Z][A-Z0-9_]+)"', source))
        documented = set(re.findall(r"`([A-Z][A-Z0-9_]+)`", texts.get("docs/errors.md", "")))
        for code in sorted(codes - documented):
            issues.append(f"docs/errors.md：缺少错误码 {code}")
    return len(names), issues


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true", help="检查暂存区中的实际字节")
    args = parser.parse_args()
    try:
        count, issues = inspect(args.staged)
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"无法检查发布文件：{type(error).__name__}；请确认已在项目中初始化 Git。", file=sys.stderr)
        return 1
    for issue in issues:
        print(issue, file=sys.stderr)
    if issues:
        return 1
    print(f"发布文件检查通过：{count} 个候选文件。此检查不代替权属确认和人工内容审查。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
