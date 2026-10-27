"""校验设计契约和合成样本，不处理医疗文书文件。

需要 jsonschema 和 rfc3339-validator。本脚本用于设计契约验证，
不承担 OFD-H 文件检查，也不是 Java SDK 的运行依赖。
"""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from datetime import datetime
from importlib.metadata import version
import json
from pathlib import Path
import sys


class DesignValidationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise DesignValidationError(message)


def set_at(document, path, value):
    target = document
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = value


def load_json(path):
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"JSON 字段重复：{key}")
            result[key] = value
        return result

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_keys)


def verify_local_refs(value, root):
    if isinstance(value, dict):
        if "$ref" in value:
            ref = value["$ref"]
            require(ref.startswith("#/"), "设计 Schema 必须使用本地引用")
            target = root
            for segment in ref[2:].split("/"):
                target = target[segment.replace("~1", "/").replace("~0", "~")]
        for child in value.values():
            verify_local_refs(child, root)
    elif isinstance(value, list):
        for child in value:
            verify_local_refs(child, root)


def verify_clinical_consistency(request, clinical):
    require(request["documentId"] == clinical["identifier"]["documentId"], "documentId 不一致")
    for key in ("documentType", "confidentiality"):
        require(request[key] == clinical["identifier"][key], f"{key} 不一致")
    for key in ("patient", "encounter"):
        require(request.get(key) == clinical.get(key), f"{key} 不一致")
    require(request["responsibleParty"] == clinical["meta"]["creator"], "责任者信息不一致")
    require(request["keywords"] == clinical["meta"]["keywords"], "关键词不一致")
    require(request["sourceVersion"] == clinical["head"]["documentVersion"], "来源版本不一致")
    require(request["createdAt"] == clinical["head"]["createdAt"], "创建时间不一致")
    require(request["clinicalData"]["schemaId"] == clinical["head"]["schemaId"], "Schema 标识不一致")
    require(request["clinicalData"]["schemaVersion"] == clinical["head"]["version"], "Schema 版本不一致")
    for key in ("sourceSystem", "sourceRecordId"):
        require(request[key] == clinical["meta"]["properties"][key], f"{key} 不一致")
    created = datetime.fromisoformat(clinical["head"]["createdAt"].replace("Z", "+00:00"))
    modified = datetime.fromisoformat(clinical["head"]["modifiedAt"].replace("Z", "+00:00"))
    require(created.tzinfo is not None and modified.tzinfo is not None, "临床数据时间必须包含时区")
    require(modified >= created, "修改时间不能早于创建时间")


def verify_report_semantics(report):
    results = report["results"]
    coverage = report["coverage"]
    counts = Counter(row["result"] for row in results)
    require(len({row["ruleId"] for row in results}) == len(results), "规则标识重复")
    require(coverage["catalogRuleCount"] == len(results), "规则目录数量不一致")
    require(sum(coverage["resultCounts"].values()) == len(results), "检查结果总数不一致")
    require(all(coverage["resultCounts"][key] == counts[key] for key in coverage["resultCounts"]), "分类检查结果数量不一致")
    evaluated = sum(counts[key] for key in ("PASS", "FAIL", "NOT_APPLICABLE"))
    require(coverage["evaluatedRuleCount"] == evaluated, "已执行规则数量不一致")
    require(evaluated <= coverage["implementedRuleCount"] <= coverage["catalogRuleCount"], "已实现规则数量不一致")
    require(coverage["scopeComplete"] == (len(coverage["uncoveredAreas"]) == 0), "规则覆盖情况与说明不一致")
    for row in results:
        if row["ruleSource"]["kind"] == "STANDARD":
            require(isinstance(row["clause"], str) and row["clause"].strip(), "标准规则必须指明对应条款")

    signatures = report["signatureVerification"]
    complete = signatures["enumeration"] == "COMPLETE"
    require(signatures["signatureCount"] == len(signatures["signatures"]) if complete else signatures["signatureCount"] is None, "签名枚举数量不一致")
    signature_bad = any(
        item["cryptographicVerification"] == "FAIL"
        or item["certificateTrust"] == "UNTRUSTED"
        or item["revocation"] == "REVOKED"
        or item["timestamp"] == "INVALID"
        for item in signatures["signatures"]
    )
    signature_unknown = not complete or any(
        item["cryptographicVerification"] == "NOT_CHECKED"
        or item["certificateTrust"] in ("UNKNOWN", "NOT_CHECKED")
        or item["revocation"] in ("UNKNOWN", "NOT_CHECKED")
        or item["timestamp"] == "NOT_CHECKED"
        for item in signatures["signatures"]
    )
    has_failure = counts["FAIL"] > 0 or signature_bad
    incomplete = counts["NOT_CHECKED"] > 0 or not coverage["scopeComplete"] or signature_unknown
    expected = "ISSUES_FOUND" if has_failure else "INCOMPLETE" if incomplete else "NO_ISSUES_IN_EXECUTED_CHECKS"
    require(report["assessment"] == expected, "总体结论与检查证据不一致")


def verify_validation_contract(validator, accept, reject):
    issue = {
        "path": "/createdAt",
        "keyword": "format",
        "message": "时间格式不正确",
        "expected": "带时区的日期时间",
        "actualType": "string",
    }
    invalid = {"valid": False, "issues": [issue], "truncated": False}
    accept("validation-valid-empty", lambda: validator.validate(
        {"valid": True, "issues": [], "truncated": False}))
    accept("validation-invalid-issue", lambda: validator.validate(invalid))
    accept("validation-truncated-with-issue", lambda: validator.validate(
        {"valid": False, "issues": [issue], "truncated": True}))
    accept("validation-truncated-without-listed-issue", lambda: validator.validate(
        {"valid": False, "issues": [], "truncated": True}))

    for pointer in ("", "/", "/createdAt", "/items/0/value", "/a~1b/~0key", "/~01", "/~10"):
        candidate = deepcopy(invalid)
        candidate["issues"][0]["path"] = pointer
        accept("validation-pointer-" + pointer, lambda c=candidate: validator.validate(c))
    for actual_type in ("missing", "null", "string", "integer", "number", "boolean", "object", "array"):
        candidate = deepcopy(invalid)
        candidate["issues"][0]["actualType"] = actual_type
        accept("validation-actual-type-" + actual_type, lambda c=candidate: validator.validate(c))

    hundred_issues = []
    for index in range(100):
        numbered = deepcopy(issue)
        numbered["path"] = f"/items/{index}/value"
        hundred_issues.append(numbered)
    for truncated in (False, True):
        candidate = {"valid": False, "issues": hundred_issues, "truncated": truncated}
        accept("validation-limit-100-" + str(truncated), lambda c=candidate: validator.validate(c))
        overflow = deepcopy(candidate)
        overflow["issues"].append(dict(issue, path="/items/100/value"))
        reject("validation-limit-101-" + str(truncated), lambda c=overflow: validator.validate(c))

    for key in ("valid", "issues", "truncated"):
        candidate = deepcopy(invalid)
        candidate.pop(key)
        reject("validation-missing-" + key, lambda c=candidate: validator.validate(c))
    for key in issue:
        candidate = deepcopy(invalid)
        candidate["issues"][0].pop(key)
        reject("validation-issue-missing-" + key, lambda c=candidate: validator.validate(c))

    for path, value in [
        (("valid",), "false"), (("valid",), 0),
        (("issues",), {}), (("issues",), None), (("issues", 0), None),
        (("truncated",), "false"), (("truncated",), 0),
        (("unexpectedField",), True),
        (("issues", 0, "path"), 42),
        (("issues", 0, "keyword"), ""), (("issues", 0, "keyword"), 42),
        (("issues", 0, "message"), ""), (("issues", 0, "message"), None),
        (("issues", 0, "expected"), ""), (("issues", 0, "expected"), []),
        (("issues", 0, "actualType"), "date-time"), (("issues", 0, "actualType"), None),
        (("issues", 0, "actualValue"), "报告不得携带实际输入值"),
    ]:
        candidate = deepcopy(invalid)
        set_at(candidate, path, value)
        reject("validation-field-" + ".".join(map(str, path)), lambda c=candidate: validator.validate(c))

    for pointer in ("createdAt", "#/createdAt", " /createdAt", "/bad~", "/bad~2escape", "/bad~~0"):
        candidate = deepcopy(invalid)
        candidate["issues"][0]["path"] = pointer
        reject("validation-invalid-pointer-" + pointer, lambda c=candidate: validator.validate(c))

    for name, candidate in [
        ("success-with-issues", {"valid": True, "issues": [issue], "truncated": False}),
        ("success-truncated", {"valid": True, "issues": [], "truncated": True}),
        ("failure-without-evidence", {"valid": False, "issues": [], "truncated": False}),
        ("root-array", []), ("root-null", None),
    ]:
        reject("validation-state-" + name, lambda c=candidate: validator.validate(c))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependency-dir", type=Path, help="独立的 Python 依赖目录")
    args = parser.parse_args()
    if args.dependency_dir:
        sys.path.insert(0, str(args.dependency_dir.resolve(strict=True)))
    try:
        from jsonschema import Draft202012Validator, FormatChecker, ValidationError
    except ImportError as exc:
        raise SystemExit("请先将 jsonschema 和 rfc3339-validator 安装到独立目录。") from exc

    checker = FormatChecker()
    require("date-time" in checker.checkers, "date-time 校验器不可用，请安装 rfc3339-validator")
    root = Path(__file__).resolve().parents[1]
    files = list((root / "contracts").rglob("*.json")) + list((root / "tests" / "contracts").rglob("*.json"))
    documents = {path.relative_to(root).as_posix(): load_json(path) for path in files}
    schema_request = documents["contracts/document.schema.json"]
    schema_report = documents["contracts/check-report.schema.json"]
    schema_validation = documents["contracts/validation-report.schema.json"]
    schemas = (schema_request, schema_report, schema_validation)
    for schema in schemas:
        Draft202012Validator.check_schema(schema)
        verify_local_refs(schema, schema)
    request_validator = Draft202012Validator(schema_request, format_checker=checker)
    report_validator = Draft202012Validator(schema_report, format_checker=checker)
    validation_validator = Draft202012Validator(schema_validation, format_checker=checker)
    request = documents["tests/contracts/admission/request.json"]
    report = documents["tests/contracts/reports/partial-check.json"]
    clinical = documents["tests/contracts/admission/clinical.json"]

    positives = []
    negatives = []

    def accept(name, callback):
        callback()
        positives.append(name)

    def reject(name, callback):
        try:
            callback()
        except (ValidationError, DesignValidationError, ValueError):
            negatives.append(name)
            return
        raise DesignValidationError(f"反例未被拒绝：{name}")

    verify_validation_contract(validation_validator, accept, reject)
    accept("request-schema", lambda: request_validator.validate(request))
    accept("report-schema", lambda: report_validator.validate(report))
    accept("clinical-cross-fields", lambda: verify_clinical_consistency(request, clinical))
    accept("report-cross-fields", lambda: verify_report_semantics(report))
    minimal = deepcopy(request)
    minimal.pop("clinicalData")
    minimal.pop("encounter")
    accept("request-without-optional-data", lambda: request_validator.validate(minimal))
    ofd = deepcopy(request)
    ofd["source"] = {"format": "OFD", "path": "admission/source.ofd", "sha256": "a" * 64}
    accept("ofd-source-contract", lambda: request_validator.validate(ofd))
    utc = deepcopy(request)
    utc["createdAt"] = "2026-10-23T01:00:00Z"
    accept("utc-source-time", lambda: request_validator.validate(utc))
    complete_report = deepcopy(report)
    for row in complete_report["results"]:
        row["result"] = "PASS"
    complete_report["coverage"].update({
        "implementedRuleCount": 7, "evaluatedRuleCount": 7,
        "resultCounts": {"PASS": 7, "FAIL": 0, "NOT_APPLICABLE": 0, "NOT_CHECKED": 0},
        "scopeComplete": True, "uncoveredAreas": [],
    })
    complete_report["signatureVerification"]["signatures"][0].update({"certificateTrust": "TRUSTED", "revocation": "GOOD"})
    complete_report["assessment"] = "NO_ISSUES_IN_EXECUTED_CHECKS"
    accept("report-no-issues-branch", lambda: (report_validator.validate(complete_report), verify_report_semantics(complete_report)))
    failure_report = deepcopy(report)
    failure_report["results"][0]["result"] = "FAIL"
    failure_report["coverage"]["resultCounts"].update({"PASS": 3, "FAIL": 1})
    failure_report["assessment"] = "ISSUES_FOUND"
    accept("report-failure-preserves-unknowns", lambda: (report_validator.validate(failure_report), verify_report_semantics(failure_report)))
    no_sign_enumeration = deepcopy(report)
    no_sign_enumeration["signatureVerification"] = {"enumeration": "NOT_CHECKED", "signatureCount": None, "signatures": []}
    accept("report-signature-enumeration-unknown", lambda: (report_validator.validate(no_sign_enumeration), verify_report_semantics(no_sign_enumeration)))

    for key in schema_request["required"]:
        candidate = deepcopy(request)
        candidate.pop(key)
        reject(f"missing-{key}", lambda c=candidate: request_validator.validate(c))
    for path, value in [
        (("contractVersion",), "1.0.0"), (("source", "format"), "DOCX"),
        (("source", "sha256"), "x" * 64), (("documentId",), "   "),
        (("patient", "id"), ""), (("patient",), {"id": "SYNTHETIC"}),
        (("responsibleParty",), {"id": "SYNTHETIC", "authority": "urn:example"}),
        (("confidentiality",), {"code": "DEMO"}), (("keywords",), []),
        (("keywords",), ["same", "same"]), (("clinicalData",), None),
        (("clinicalData",), {"path": "a.json", "schemaId": "urn:example"}),
        (("unexpectedField",), True),
    ]:
        candidate = deepcopy(request)
        set_at(candidate, path, value)
        reject("request-value-" + ".".join(path), lambda c=candidate: request_validator.validate(c))
    for bad in ("/absolute.pdf", "C:/x.pdf", "../x.pdf", "a/../x.pdf", "./x.pdf", "a/./x.pdf", "a//x.pdf", "a/x.pdf/", "https://example.org/x.pdf", "file:///x.pdf", "a\\x.pdf", "\\\\server\\x.pdf", "a\x00.pdf", "a\n.pdf"):
        candidate = deepcopy(request)
        candidate["source"]["path"] = bad
        reject("source-path-" + repr(bad), lambda c=candidate: request_validator.validate(c))
    for bad in ("2026-10-23T09:00:00", "2026-02-31T09:00:00+08:00", "2026-10-23", "2026-10-23T09:00:00+25:00", "2026-10-23T24:00:00Z"):
        candidate = deepcopy(request)
        candidate["createdAt"] = bad
        reject("source-date-" + bad, lambda c=candidate: request_validator.validate(c))
    missing_tool = deepcopy(report)
    missing_tool.pop("tool")
    reject("report-missing-tool", lambda: report_validator.validate(missing_tool))
    for path, value in [
        (("assessment",), "PASS"), (("results", 0, "result"), "SKIPPED"),
        (("tool", "name"), " "), (("tool", "version"), ""),
        (("signatureVerification", "signatures", 0, "certificateTrust"), "PASS"),
        (("generatedAt",), "2026-02-31T09:00:00+08:00"),
        (("artifact", "sha256"), ""), (("coverage", "catalogRuleCount"), -1),
        (("results", 0, "ruleSource", "kind"), "ASSUMED_STANDARD"),
    ]:
        candidate = deepcopy(report)
        set_at(candidate, path, value)
        reject("report-value-" + ".".join(map(str, path)), lambda c=candidate: report_validator.validate(c))
    for path, value in [
        (("coverage", "catalogRuleCount"), 8), (("coverage", "resultCounts", "PASS"), 5),
        (("coverage", "evaluatedRuleCount"), 7), (("coverage", "implementedRuleCount"), 3),
        (("coverage", "scopeComplete"), True), (("results", 1, "ruleId"), report["results"][0]["ruleId"]),
        (("results", 0, "clause"), None), (("signatureVerification", "signatureCount"), 0),
        (("signatureVerification", "enumeration"), "NOT_CHECKED"),
        (("assessment",), "NO_ISSUES_IN_EXECUTED_CHECKS"),
        (("signatureVerification", "signatures", 0, "cryptographicVerification"), "FAIL"),
    ]:
        candidate = deepcopy(report)
        set_at(candidate, path, value)
        reject("report-semantics-" + ".".join(map(str, path)), lambda c=candidate: verify_report_semantics(c))
    for path, value in [
        (("identifier", "documentId"), "DIFFERENT"), (("patient", "id"), "DIFFERENT"),
        (("meta", "creator", "id"), "DIFFERENT"), (("head", "documentVersion"), "2"),
        (("head", "version"), "9.0.0"), (("head", "modifiedAt"), "2026-10-22T09:00:00+08:00"),
    ]:
        candidate = deepcopy(clinical)
        set_at(candidate, path, value)
        reject("clinical-semantics-" + ".".join(path), lambda c=candidate: verify_clinical_consistency(request, c))

    print(json.dumps({
        "result": "PASS",
        "meaning": "仅验证设计契约，不证明 OFD-H 符合性或转换能力",
        "jsonFiles": len(documents),
        "metaSchemasValidated": len(schemas),
        "positiveCases": len(positives),
        "negativeCasesRejected": len(negatives),
        "jsonschemaVersion": version("jsonschema"),
        "rfc3339ValidatorVersion": version("rfc3339-validator"),
        "runtime": sys.version.split()[0],
    }, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"设计契约校验失败：{exc}", file=sys.stderr)
        raise SystemExit(1) from exc
