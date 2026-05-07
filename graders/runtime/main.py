import importlib.util
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
import traceback
from dataclasses import dataclass
from typing import Any, Dict, List


MAX_VALUE_MESSAGE_LENGTH = 120
DEFAULT_COMMAND_TIMEOUT_SECONDS = 5
SUPPORTED_COMPARISON_MODES = {"exact", "unordered_exact"}
SUPPORTED_PROBLEM_TYPES = {"function_cases", "stdio_cases", "project_cases"}
ADAPTER_MODES_BY_PROBLEM_TYPE = {
    "function_cases": {"function"},
    "stdio_cases": {"stdio"},
    "project_cases": {"project"},
}


@dataclass(frozen=True)
class RuntimeManifest:
    manifest_version: int
    problem_type: str
    comparison_mode: str
    test_cases: List[Dict[str, Any]]
    selected_language: str
    adapter_mode: str
    entry_function: str | None
    compile_command: List[str] | None
    run_command: List[str] | None
    submission_file_name: str | None
    executable: bool


def load_module_from_path(module_name: str, file_path: str):
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    if spec is None or spec.loader is None:
        raise ImportError(f"Could not load spec for {file_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_json_file(file_path: str) -> Dict[str, Any]:
    with open(file_path, "r", encoding="utf-8") as f:
        return json.load(f)


def make_result(kind: str, name: str, passed: bool, message: str) -> Dict[str, Any]:
    return {
        "kind": kind,
        "name": name,
        "passed": passed,
        "message": message
    }


def emit(payload: Dict[str, Any], exit_code: int = 0) -> None:
    print(json.dumps(payload))
    sys.exit(exit_code)


def build_payload(
    status: str,
    validation_passed: bool,
    tests_passed: int,
    tests_total: int,
    error_message: str | None,
    results: List[Dict[str, Any]]
) -> Dict[str, Any]:
    score = round((tests_passed / tests_total) * 100, 2) if tests_total > 0 else 0.0
    return {
        "status": status,
        "validation_passed": validation_passed,
        "tests_passed": tests_passed,
        "tests_total": tests_total,
        "score": score,
        "error_message": error_message,
        "results": results
    }


def fail_validation(message: str, exit_code: int = 1) -> None:
    emit(
        build_payload(
            status="FAILED",
            validation_passed=False,
            tests_passed=0,
            tests_total=0,
            error_message=message,
            results=[make_result("validation", "validation_check", False, message)]
        ),
        exit_code=exit_code
    )


def compare_values(actual: Any, expected: Any, mode: str) -> bool:
    if mode == "exact":
        return actual == expected

    if mode == "unordered_exact":
        if not isinstance(actual, list) or not isinstance(expected, list):
            return False
        try:
            return sorted(actual) == sorted(expected)
        except TypeError:
            return False

    raise ValueError(f"Unsupported comparison mode: {mode}")


def summarize_value(value: Any) -> str:
    if isinstance(value, int):
        estimated_digits = max(1, int(value.bit_length() * 0.30103) + 1)
        if estimated_digits > MAX_VALUE_MESSAGE_LENGTH:
            return f"<int with about {estimated_digits} digits>"

    text = repr(value)
    if len(text) <= MAX_VALUE_MESSAGE_LENGTH:
        return text

    return text[:MAX_VALUE_MESSAGE_LENGTH] + "...<truncated>"


def fibonacci(n: int) -> int:
    current, next_value = 0, 1
    for _ in range(n):
        current, next_value = next_value, current + next_value
    return current


def build_fibonacci_generated_cases(config: Dict[str, Any], index: int) -> tuple[List[Dict[str, Any]], List[str]]:
    errors = []
    cases: List[Dict[str, Any]] = []
    name_prefix = config.get("name_prefix", f"generated_{index}")

    if not isinstance(name_prefix, str) or not name_prefix.strip():
        errors.append(f"generated_cases[{index}] is missing valid 'name_prefix'")
        name_prefix = f"generated_{index}"

    inputs = config.get("inputs")
    if inputs is not None:
        if not isinstance(inputs, list) or not inputs:
            errors.append(f"generated_cases[{index}].inputs must be a non-empty list")
            return cases, errors

        for input_index, n in enumerate(inputs):
            if not isinstance(n, int) or n < 0:
                errors.append(f"generated_cases[{index}].inputs[{input_index}] must be a non-negative integer")
                continue
            cases.append({
                "name": f"{name_prefix}_{n}",
                "args": [n],
                "expected": fibonacci(n)
            })

        return cases, errors

    count = config.get("count")
    min_n = config.get("min_n")
    max_n = config.get("max_n")
    seed = config.get("seed")

    if not isinstance(count, int) or count <= 0:
        errors.append(f"generated_cases[{index}].count must be a positive integer")
    if not isinstance(min_n, int) or min_n < 0:
        errors.append(f"generated_cases[{index}].min_n must be a non-negative integer")
    if not isinstance(max_n, int) or max_n < 0:
        errors.append(f"generated_cases[{index}].max_n must be a non-negative integer")
    if isinstance(min_n, int) and isinstance(max_n, int) and min_n > max_n:
        errors.append(f"generated_cases[{index}].min_n cannot be greater than max_n")
    if seed is not None and not isinstance(seed, int):
        errors.append(f"generated_cases[{index}].seed must be an integer")

    if errors:
        return cases, errors

    rng = random.Random(seed)
    values = [rng.randint(min_n, max_n) for _ in range(count)]

    for value_index, n in enumerate(values):
        cases.append({
            "name": f"{name_prefix}_{value_index}_{n}",
            "args": [n],
            "expected": fibonacci(n)
        })

    return cases, errors


def build_generated_cases(manifest: Dict[str, Any]) -> tuple[List[Dict[str, Any]], List[str]]:
    generated_cases = manifest.get("generated_cases", [])
    errors = []
    cases: List[Dict[str, Any]] = []

    if not isinstance(generated_cases, list):
        return cases, ["manifest.json has invalid 'generated_cases'"]

    for i, config in enumerate(generated_cases):
        if not isinstance(config, dict):
            errors.append(f"generated_cases[{i}] is not an object")
            continue

        generator_type = config.get("type")
        if generator_type == "fibonacci":
            generated, generator_errors = build_fibonacci_generated_cases(config, i)
            cases.extend(generated)
            errors.extend(generator_errors)
            continue

        errors.append(f"generated_cases[{i}] has unsupported type")

    return cases, errors


def validate_manifest(manifest: Dict[str, Any]) -> RuntimeManifest:
    if manifest.get("manifestVersion") == 2:
        return validate_v2_manifest(manifest)

    return validate_legacy_python_function_manifest(manifest)


def validate_legacy_python_function_manifest(manifest: Dict[str, Any]) -> RuntimeManifest:
    entry_function = manifest.get("entry_function")
    comparison = manifest.get("comparison", {})
    test_cases = manifest.get("test_cases")

    errors = []

    if not isinstance(entry_function, str) or not entry_function.strip():
        errors.append("manifest.json is missing a valid 'entry_function'")

    if not isinstance(comparison, dict):
        errors.append("manifest.json has invalid 'comparison'")
        comparison = {}

    comparison_mode = comparison.get("mode", "exact")
    if comparison_mode not in SUPPORTED_COMPARISON_MODES:
        errors.append("manifest.json has unsupported comparison.mode")

    if not isinstance(test_cases, list):
        errors.append("manifest.json is missing a valid 'test_cases' list")
        test_cases = []
    else:
        for i, case in enumerate(test_cases):
            if not isinstance(case, dict):
                errors.append(f"test_cases[{i}] is not an object")
                continue
            if "name" not in case or not isinstance(case["name"], str):
                errors.append(f"test_cases[{i}] is missing valid 'name'")
            if "args" not in case or not isinstance(case["args"], list):
                errors.append(f"test_cases[{i}] is missing valid 'args' list")
            if "expected" not in case:
                errors.append(f"test_cases[{i}] is missing 'expected'")

    generated_cases, generated_errors = build_generated_cases(manifest)
    errors.extend(generated_errors)

    if errors:
        fail_validation("; ".join(errors), exit_code=1)

    return RuntimeManifest(
        manifest_version=1,
        problem_type="function_cases",
        comparison_mode=comparison_mode,
        test_cases=test_cases + generated_cases,
        selected_language="python",
        adapter_mode="function",
        entry_function=entry_function,
        compile_command=None,
        run_command=None,
        submission_file_name=None,
        executable=True,
    )


def validate_v2_manifest(manifest: Dict[str, Any]) -> RuntimeManifest:
    errors = []

    assignment_key = manifest.get("assignmentKey")
    if not isinstance(assignment_key, str) or not assignment_key.strip():
        errors.append("manifest.json is missing a valid 'assignmentKey'")

    problem_type = manifest.get("problemType")
    if problem_type not in SUPPORTED_PROBLEM_TYPES:
        errors.append("manifest.json has unsupported or missing 'problemType'")

    comparison = manifest.get("comparison", {})
    if not isinstance(comparison, dict):
        errors.append("manifest.json has invalid 'comparison'")
        comparison = {}

    comparison_mode = comparison.get("mode", "exact")
    if comparison_mode not in SUPPORTED_COMPARISON_MODES:
        errors.append("manifest.json has unsupported comparison.mode")

    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        errors.append("manifest.json is missing a non-empty 'cases' list")
        cases = []
    else:
        validate_v2_cases(problem_type, cases, errors)

    languages = manifest.get("languages")
    selected_language = select_manifest_language(manifest)
    selected_adapter: Dict[str, Any] = {}
    adapter_mode = None
    entry_function = None
    compile_command = None
    run_command = None
    submission_file_name = None

    if not isinstance(languages, dict) or not languages:
        errors.append("manifest.json is missing a non-empty 'languages' object")
    else:
        language_config = languages.get(selected_language)
        if not isinstance(language_config, dict):
            errors.append(f"manifest.json does not define selected language '{selected_language}'")
        else:
            adapter = language_config.get("adapter")
            if not isinstance(adapter, dict):
                errors.append(f"languages.{selected_language} is missing an 'adapter' object")
            else:
                selected_adapter = adapter
                adapter_mode = adapter.get("mode")
                validate_v2_adapter(selected_language, problem_type, adapter, errors)
                if adapter_mode == "function":
                    entry_function = adapter.get("entryFunction")
                if adapter_mode == "stdio":
                    compile_command = adapter.get("compileCommand")
                    run_command = adapter.get("runCommand")

            submission = language_config.get("submission", {})
            if submission is None:
                submission = {}
            if not isinstance(submission, dict):
                errors.append(f"languages.{selected_language}.submission must be an object when present")
            else:
                submission_file_name = submission.get("fileName")
                if submission_file_name is not None and not is_safe_workspace_file_name(submission_file_name):
                    errors.append(f"languages.{selected_language}.submission has invalid 'fileName'")

    if errors:
        fail_validation("; ".join(errors), exit_code=1)

    return RuntimeManifest(
        manifest_version=2,
        problem_type=problem_type,
        comparison_mode=comparison_mode,
        test_cases=normalize_v2_cases(problem_type, cases),
        selected_language=selected_language,
        adapter_mode=adapter_mode,
        entry_function=entry_function,
        compile_command=compile_command,
        run_command=run_command,
        submission_file_name=submission_file_name,
        executable=(
            (problem_type == "function_cases" and selected_language == "python" and adapter_mode == "function")
            or (problem_type == "stdio_cases" and adapter_mode == "stdio")
        ),
    )


def select_manifest_language(manifest: Dict[str, Any]) -> str:
    configured_language = os.environ.get("GRADER_LANGUAGE") or manifest.get("defaultLanguage") or "python"
    return str(configured_language).strip().lower()


def validate_v2_adapter(
    selected_language: str,
    problem_type: Any,
    adapter: Dict[str, Any],
    errors: List[str],
) -> None:
    adapter_mode = adapter.get("mode")
    allowed_modes = ADAPTER_MODES_BY_PROBLEM_TYPE.get(problem_type, set())

    if adapter_mode not in {"function", "stdio", "project"}:
        errors.append(f"languages.{selected_language}.adapter has unsupported or missing 'mode'")
        return

    if adapter_mode not in allowed_modes:
        errors.append(
            f"languages.{selected_language}.adapter.mode '{adapter_mode}' does not support problemType '{problem_type}'"
        )
        return

    if adapter_mode == "function":
        entry_function = adapter.get("entryFunction")
        if not isinstance(entry_function, str) or not entry_function.strip():
            errors.append(f"languages.{selected_language}.adapter is missing a valid 'entryFunction'")

    if adapter_mode in {"stdio", "project"}:
        validate_optional_command(adapter, "compileCommand", selected_language, errors)
        validate_required_command(adapter, "runCommand", selected_language, errors)


def validate_required_command(
    adapter: Dict[str, Any],
    field_name: str,
    selected_language: str,
    errors: List[str],
) -> None:
    command = adapter.get(field_name)
    if not is_command_list(command):
        errors.append(f"languages.{selected_language}.adapter is missing a valid '{field_name}'")


def validate_optional_command(
    adapter: Dict[str, Any],
    field_name: str,
    selected_language: str,
    errors: List[str],
) -> None:
    command = adapter.get(field_name)
    if command is not None and not is_command_list(command):
        errors.append(f"languages.{selected_language}.adapter has invalid '{field_name}'")


def is_command_list(value: Any) -> bool:
    return isinstance(value, list) and bool(value) and all(isinstance(item, str) and item for item in value)


def is_safe_workspace_file_name(value: Any) -> bool:
    return (
        isinstance(value, str)
        and bool(value.strip())
        and value == value.strip()
        and os.path.basename(value) == value
        and value not in {".", ".."}
    )


def validate_v2_cases(problem_type: Any, cases: List[Any], errors: List[str]) -> None:
    for i, case in enumerate(cases):
        if not isinstance(case, dict):
            errors.append(f"cases[{i}] is not an object")
            continue

        if "name" not in case or not isinstance(case["name"], str) or not case["name"].strip():
            errors.append(f"cases[{i}] is missing valid 'name'")

        case_input = case.get("input")
        expected = case.get("expected")
        if not isinstance(case_input, dict):
            errors.append(f"cases[{i}] is missing valid 'input'")
            case_input = {}
        if not isinstance(expected, dict):
            errors.append(f"cases[{i}] is missing valid 'expected'")
            expected = {}

        if problem_type == "function_cases":
            if "args" not in case_input or not isinstance(case_input.get("args"), list):
                errors.append(f"cases[{i}].input is missing valid 'args' list")
            if "value" not in expected:
                errors.append(f"cases[{i}].expected is missing 'value'")
        elif problem_type == "stdio_cases":
            if "stdin" not in case_input or not isinstance(case_input.get("stdin"), str):
                errors.append(f"cases[{i}].input is missing valid 'stdin'")
            if "stdout" not in expected or not isinstance(expected.get("stdout"), str):
                errors.append(f"cases[{i}].expected is missing valid 'stdout'")
        elif problem_type == "project_cases":
            if not expected:
                errors.append(f"cases[{i}].expected must define at least one project assertion")


def normalize_v2_cases(problem_type: str, cases: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    if problem_type != "function_cases":
        return cases

    normalized_cases = []
    for case in cases:
        normalized_cases.append({
            "name": case["name"],
            "args": case["input"]["args"],
            "expected": case["expected"]["value"],
        })
    return normalized_cases


def command_timeout_seconds() -> float:
    raw_timeout = os.environ.get("GRADER_COMMAND_TIMEOUT_SECONDS")
    if raw_timeout is None:
        return DEFAULT_COMMAND_TIMEOUT_SECONDS
    try:
        timeout = float(raw_timeout)
    except ValueError:
        return DEFAULT_COMMAND_TIMEOUT_SECONDS
    return timeout if timeout > 0 else DEFAULT_COMMAND_TIMEOUT_SECONDS


def run_command(
    command: List[str],
    work_dir: str,
    stdin: str = "",
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        input=stdin,
        text=True,
        capture_output=True,
        cwd=work_dir,
        timeout=command_timeout_seconds(),
        check=False,
    )


def summarize_stream(value: str) -> str:
    if value == "":
        return "<empty>"
    if len(value) <= MAX_VALUE_MESSAGE_LENGTH:
        return repr(value)
    return repr(value[:MAX_VALUE_MESSAGE_LENGTH] + "...<truncated>")


def build_stdio_failure_message(prefix: str, completed: subprocess.CompletedProcess[str]) -> str:
    stderr = summarize_stream(completed.stderr)
    stdout = summarize_stream(completed.stdout)
    return f"{prefix} exited with code {completed.returncode}; stdout={stdout}; stderr={stderr}"


def emit_stdio_build_failure(message: str, tests_total: int) -> None:
    emit(
        build_payload(
            status="FAILED",
            validation_passed=False,
            tests_passed=0,
            tests_total=tests_total,
            error_message=message,
            results=[make_result("validation", "build_check", False, message)]
        ),
        exit_code=0
    )


def run_stdio_cases(submission_path: str, runtime_manifest: RuntimeManifest) -> None:
    if runtime_manifest.run_command is None:
        fail_validation("stdio runtime is missing runCommand", exit_code=1)

    results: List[Dict[str, Any]] = []
    tests_passed = 0
    tests_total = len(runtime_manifest.test_cases)

    with tempfile.TemporaryDirectory() as work_dir:
        staged_name = runtime_manifest.submission_file_name or os.path.basename(submission_path)
        staged_submission_path = os.path.join(work_dir, staged_name)
        shutil.copyfile(submission_path, staged_submission_path)

        if runtime_manifest.compile_command is not None:
            try:
                compile_result = run_command(runtime_manifest.compile_command, work_dir)
            except subprocess.TimeoutExpired:
                emit_stdio_build_failure(
                    f"compileCommand timed out after {command_timeout_seconds()} seconds",
                    tests_total,
                )

            if compile_result.returncode != 0:
                emit_stdio_build_failure(
                    build_stdio_failure_message("compileCommand", compile_result),
                    tests_total,
                )

        results.append(make_result("validation", "build_check", True, "submission build completed"))

        for case in runtime_manifest.test_cases:
            case_name = case["name"]
            expected_stdout = case["expected"]["stdout"]

            try:
                run_result = run_command(runtime_manifest.run_command, work_dir, case["input"]["stdin"])
            except subprocess.TimeoutExpired:
                results.append(
                    make_result(
                        "test",
                        case_name,
                        False,
                        f"runCommand timed out after {command_timeout_seconds()} seconds"
                    )
                )
                continue

            if run_result.returncode != 0:
                results.append(
                    make_result(
                        "test",
                        case_name,
                        False,
                        build_stdio_failure_message("runCommand", run_result)
                    )
                )
                continue

            passed = run_result.stdout == expected_stdout
            if passed:
                tests_passed += 1

            results.append(
                make_result(
                    "test",
                    case_name,
                    passed,
                    f"Expected stdout {summarize_stream(expected_stdout)}, got {summarize_stream(run_result.stdout)}"
                )
            )

    emit_final_results(tests_passed, tests_total, results)


def run_function_cases(submission_path: str, runtime_manifest: RuntimeManifest) -> None:
    entry_function = runtime_manifest.entry_function
    comparison_mode = runtime_manifest.comparison_mode
    test_cases = runtime_manifest.test_cases

    results: List[Dict[str, Any]] = []

    try:
        submission_module = load_module_from_path("submission_module", submission_path)
    except Exception as exc:
        emit(
            build_payload(
                status="FAILED",
                validation_passed=False,
                tests_passed=0,
                tests_total=0,
                error_message=f"Failed to import submission: {exc}",
                results=[
                    make_result("validation", "validation_check", False, f"submission import failed: {exc}")
                ]
            ),
            exit_code=0
        )

    submission_func = getattr(submission_module, entry_function, None)

    if not callable(submission_func):
        emit(
            build_payload(
                status="FAILED",
                validation_passed=False,
                tests_passed=0,
                tests_total=0,
                error_message=f"submission is missing callable function '{entry_function}'",
                results=[
                    make_result(
                        "validation",
                        "validation_check",
                        False,
                        f"submission is missing callable function '{entry_function}'"
                    )
                ]
            ),
            exit_code=0
        )

    results.append(
        make_result(
            "validation",
            "validation_check",
            True,
            f"submission imported successfully; found callable '{entry_function}'"
        )
    )

    tests_passed = 0
    tests_total = len(test_cases)

    for case in test_cases:
        case_name = case["name"]
        args = case["args"]
        expected = case["expected"]

        try:
            actual = submission_func(*args)
        except Exception as exc:
            results.append(
                make_result("test", case_name, False, f"submission raised exception on args {args}: {exc}")
            )
            continue

        try:
            passed = compare_values(actual, expected, comparison_mode)
        except Exception as exc:
            results.append(
                make_result("test", case_name, False, f"comparison failed for args {args}: {exc}")
            )
            continue

        if passed:
            tests_passed += 1
            results.append(
                make_result("test", case_name, True, f"Expected {summarize_value(expected)}, got {summarize_value(actual)}")
            )
        else:
            results.append(
                make_result("test", case_name, False, f"Expected {summarize_value(expected)}, got {summarize_value(actual)}")
            )

    emit_final_results(tests_passed, tests_total, results)


def emit_final_results(tests_passed: int, tests_total: int, results: List[Dict[str, Any]]) -> None:
    if tests_passed == tests_total:
        status = "SUCCEEDED"
        error_message = None
    elif tests_total > 0 and tests_passed == 0:
        status = "FAILED"
        error_message = "No test cases passed."
    else:
        status = "PARTIAL"
        error_message = None

    emit(
        build_payload(
            status=status,
            validation_passed=True,
            tests_passed=tests_passed,
            tests_total=tests_total,
            error_message=error_message,
            results=results
        ),
        exit_code=0
    )


def main():
    if len(sys.argv) != 3:
        fail_validation("Usage: python main.py <submission_path> <manifest_path>", exit_code=1)

    submission_path = sys.argv[1]
    manifest_path = sys.argv[2]

    if not os.path.exists(submission_path):
        fail_validation(f"Submission file not found: {submission_path}", exit_code=1)

    if not os.path.exists(manifest_path):
        fail_validation(f"Manifest file not found: {manifest_path}", exit_code=1)

    try:
        manifest = load_json_file(manifest_path)
    except Exception as exc:
        fail_validation(f"Failed to load manifest: {exc}", exit_code=1)

    runtime_manifest = validate_manifest(manifest)
    if not runtime_manifest.executable:
        fail_validation(
            "manifest parsed successfully, but this runtime mode is not implemented yet.",
            exit_code=1
        )

    if runtime_manifest.adapter_mode == "stdio":
        run_stdio_cases(submission_path, runtime_manifest)

    run_function_cases(submission_path, runtime_manifest)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        emit(
            build_payload(
                status="FAILED",
                validation_passed=False,
                tests_passed=0,
                tests_total=0,
                error_message=traceback.format_exc(),
                results=[
                    make_result("validation", "validation_check", False, "unexpected grader failure")
                ]
            ),
            exit_code=1
        )
