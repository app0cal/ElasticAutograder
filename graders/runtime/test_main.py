import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


RUNTIME_MAIN = Path(__file__).with_name("main.py")
REPO_ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("runtime_main", RUNTIME_MAIN)
runtime_main = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime_main)


class RuntimeManifestValidationTest(unittest.TestCase):

    def test_legacy_manifest_validates_as_executable_python_function(self):
        manifest = {
            "entry_function": "fib",
            "comparison": {"mode": "exact"},
            "test_cases": [
                {"name": "case_0", "args": [0], "expected": 0}
            ],
        }

        runtime_manifest = runtime_main.validate_manifest(manifest)

        self.assertEqual(1, runtime_manifest.manifest_version)
        self.assertEqual("function_cases", runtime_manifest.problem_type)
        self.assertEqual("python", runtime_manifest.selected_language)
        self.assertEqual("function", runtime_manifest.adapter_mode)
        self.assertEqual("fib", runtime_manifest.entry_function)
        self.assertTrue(runtime_manifest.executable)
        self.assertEqual(manifest["test_cases"], runtime_manifest.test_cases)

    def test_v2_manifest_validates_selected_language_and_adapter(self):
        manifest = v2_manifest()

        with patch.dict(os.environ, {"GRADER_LANGUAGE": "java"}):
            runtime_manifest = runtime_main.validate_manifest(manifest)

        self.assertEqual(2, runtime_manifest.manifest_version)
        self.assertEqual("stdio_cases", runtime_manifest.problem_type)
        self.assertEqual("java", runtime_manifest.selected_language)
        self.assertEqual("stdio", runtime_manifest.adapter_mode)
        self.assertTrue(runtime_manifest.executable)
        self.assertEqual(["javac", "Main.java"], runtime_manifest.compile_command)
        self.assertEqual(["java", "Main"], runtime_manifest.run_command)
        self.assertEqual("Main.java", runtime_manifest.submission_file_name)

    def test_v2_manifest_missing_selected_language_fails_validation(self):
        manifest = v2_manifest()

        with patch.dict(os.environ, {"GRADER_LANGUAGE": "cpp"}):
            payload = validate_and_read_failure(manifest)

        self.assertEqual("FAILED", payload["status"])
        self.assertFalse(payload["validation_passed"])
        self.assertIn("selected language 'cpp'", payload["error_message"])

    def test_v2_manifest_rejects_adapter_that_does_not_match_problem_type(self):
        manifest = v2_manifest()
        manifest["languages"]["java"]["adapter"]["mode"] = "function"
        manifest["languages"]["java"]["adapter"]["entryFunction"] = "fib"

        with patch.dict(os.environ, {"GRADER_LANGUAGE": "java"}):
            payload = validate_and_read_failure(manifest)

        self.assertEqual("FAILED", payload["status"])
        self.assertIn("does not support problemType 'stdio_cases'", payload["error_message"])

    def test_v2_function_cases_normalize_to_legacy_case_shape(self):
        manifest = v2_python_function_manifest()

        runtime_manifest = runtime_main.validate_manifest(manifest)

        self.assertEqual(
            [{"name": "case_0", "args": [0], "expected": 0}],
            runtime_manifest.test_cases,
        )
        self.assertEqual("fib", runtime_manifest.entry_function)
        self.assertTrue(runtime_manifest.executable)

    def test_v2_project_cases_validates_as_executable_project_adapter(self):
        manifest = v2_python_project_manifest()

        runtime_manifest = runtime_main.validate_manifest(manifest)

        self.assertEqual("project_cases", runtime_manifest.problem_type)
        self.assertEqual("project", runtime_manifest.adapter_mode)
        self.assertTrue(runtime_manifest.executable)
        self.assertEqual([sys.executable, "-m", "py_compile", "main.py"], runtime_manifest.compile_command)
        self.assertEqual([sys.executable, "main.py"], runtime_manifest.run_command)

    def test_main_v2_python_function_passing_submission_succeeds(self):
        payload = run_runtime(
            submission_source="""
def fib(n):
    if n <= 1:
        return n
    previous, current = 0, 1
    for _ in range(n):
        previous, current = current, previous + current
    return previous
""",
            manifest=v2_python_function_manifest(),
        )

        self.assertEqual("SUCCEEDED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(1, payload["tests_passed"])
        self.assertEqual(1, payload["tests_total"])
        self.assertEqual(100.0, payload["score"])

    def test_main_v2_python_function_wrong_answer_fails_normally(self):
        payload = run_runtime(
            submission_source="""
def fib(n):
    return -1
""",
            manifest=v2_python_function_manifest(),
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertEqual(1, payload["tests_total"])
        self.assertEqual("No test cases passed.", payload["error_message"])

    def test_main_v2_python_function_missing_entry_function_returns_failed_result(self):
        payload = run_runtime(
            submission_source="""
def other(n):
    return n
""",
            manifest=v2_python_function_manifest(),
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertFalse(payload["validation_passed"])
        self.assertIn("missing callable function 'fib'", payload["error_message"])

    def test_main_v2_stdio_compile_success_and_passing_stdout_succeeds(self):
        payload = run_runtime(
            submission_source="""
import sys
n = int(sys.stdin.read())
print(n)
""",
            manifest=v2_python_stdio_manifest(),
            language="python",
        )

        self.assertEqual("SUCCEEDED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(1, payload["tests_passed"])
        self.assertEqual(1, payload["tests_total"])

    def test_main_v2_stdio_wrong_stdout_fails_test_result(self):
        payload = run_runtime(
            submission_source="""
print("wrong")
""",
            manifest=v2_python_stdio_manifest(),
            language="python",
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertIn("Expected stdout", payload["results"][1]["message"])

    def test_main_v2_stdio_compile_command_nonzero_returns_build_failure(self):
        payload = run_runtime(
            submission_source="""
def broken(
""",
            manifest=v2_python_stdio_manifest(),
            language="python",
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertFalse(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertEqual(1, payload["tests_total"])
        self.assertIn("compileCommand exited with code", payload["error_message"])

    def test_main_v2_stdio_run_command_nonzero_fails_case(self):
        payload = run_runtime(
            submission_source="""
import sys
print("before exit")
print("boom", file=sys.stderr)
sys.exit(7)
""",
            manifest=v2_python_stdio_manifest(),
            language="python",
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertIn("runCommand exited with code 7", payload["results"][1]["message"])
        self.assertIn("boom", payload["results"][1]["message"])

    def test_main_v2_stdio_run_timeout_fails_case(self):
        payload = run_runtime(
            submission_source="""
import time
time.sleep(1)
""",
            manifest=v2_python_stdio_manifest(compile_command=False),
            language="python",
            extra_env={"GRADER_COMMAND_TIMEOUT_SECONDS": "0.05"},
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertIn("runCommand timed out", payload["results"][1]["message"])

    def test_main_v2_project_compile_success_and_passing_stdout_succeeds(self):
        payload = run_project_runtime(
            files={
                "main.py": """
import sys
print(sys.stdin.read().strip())
""",
            },
            manifest=v2_python_project_manifest(),
        )

        self.assertEqual("SUCCEEDED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(1, payload["tests_passed"])
        self.assertEqual(1, payload["tests_total"])

    def test_main_v2_project_wrong_stdout_fails_test_result(self):
        payload = run_project_runtime(
            files={"main.py": "print('wrong')\n"},
            manifest=v2_python_project_manifest(),
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertIn("Expected stdout", payload["results"][1]["message"])

    def test_main_v2_project_compile_command_nonzero_returns_build_failure(self):
        payload = run_project_runtime(
            files={"main.py": "def broken(\n"},
            manifest=v2_python_project_manifest(),
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertFalse(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertEqual(1, payload["tests_total"])
        self.assertIn("compileCommand exited with code", payload["error_message"])

    def test_main_v2_project_run_command_nonzero_fails_case(self):
        payload = run_project_runtime(
            files={
                "main.py": """
import sys
print("before exit")
print("boom", file=sys.stderr)
sys.exit(7)
""",
            },
            manifest=v2_python_project_manifest(compile_command=False),
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertIn("runCommand exited with code 7", payload["results"][1]["message"])

    def test_main_v2_project_run_timeout_fails_case(self):
        payload = run_project_runtime(
            files={
                "main.py": """
import time
time.sleep(1)
""",
            },
            manifest=v2_python_project_manifest(compile_command=False),
            extra_env={"GRADER_COMMAND_TIMEOUT_SECONDS": "0.05"},
        )

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertIn("runCommand timed out", payload["results"][1]["message"])

    def test_v2_project_missing_expected_stdout_fails_validation(self):
        manifest = v2_python_project_manifest()
        manifest["cases"][0]["expected"] = {}

        payload = validate_and_read_failure(manifest)

        self.assertEqual("FAILED", payload["status"])
        self.assertFalse(payload["validation_passed"])
        self.assertIn("expected is missing valid 'stdout'", payload["error_message"])

    def test_sample_java_project_passing_fixture_succeeds(self):
        payload = run_project_fixture_runtime("fib_project_pass.zip")

        self.assertEqual("SUCCEEDED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(4, payload["tests_passed"])
        self.assertEqual(4, payload["tests_total"])

    def test_sample_java_project_read_only_source_fixture_succeeds(self):
        payload = run_read_only_project_fixture_runtime("fib_project_pass.zip")

        self.assertEqual("SUCCEEDED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(4, payload["tests_passed"])
        self.assertEqual(4, payload["tests_total"])

    def test_sample_java_project_wrong_fixture_fails_stdout_cases(self):
        payload = run_project_fixture_runtime("fib_project_wrong.zip")

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertIn("Expected stdout", payload["results"][1]["message"])

    def test_sample_java_project_compile_error_fixture_returns_build_failure(self):
        payload = run_project_fixture_runtime("fib_project_compile_error.zip")

        self.assertEqual("FAILED", payload["status"])
        self.assertFalse(payload["validation_passed"])
        self.assertIn("compileCommand exited with code", payload["error_message"])

    def test_sample_java_project_runtime_error_fixture_fails_cases(self):
        payload = run_project_fixture_runtime("fib_project_runtime_error.zip")

        self.assertEqual("FAILED", payload["status"])
        self.assertTrue(payload["validation_passed"])
        self.assertEqual(0, payload["tests_passed"])
        self.assertIn("runCommand exited with code", payload["results"][1]["message"])


def v2_manifest():
    return {
        "manifestVersion": 2,
        "assignmentKey": "fib",
        "problemType": "stdio_cases",
        "comparison": {"mode": "exact"},
        "cases": [
            {
                "name": "case_0",
                "input": {"stdin": "0\n"},
                "expected": {"stdout": "0\n"},
            }
        ],
        "languages": {
            "java": {
                "adapter": {
                    "mode": "stdio",
                    "compileCommand": ["javac", "Main.java"],
                    "runCommand": ["java", "Main"],
                },
                "submission": {"fileName": "Main.java"},
            }
        },
    }


def v2_python_function_manifest():
    return {
        "manifestVersion": 2,
        "assignmentKey": "fib",
        "problemType": "function_cases",
        "comparison": {"mode": "exact"},
        "cases": [
            {
                "name": "case_0",
                "input": {"args": [0]},
                "expected": {"value": 0},
            }
        ],
        "languages": {
            "python": {
                "adapter": {
                    "mode": "function",
                    "entryFunction": "fib",
                }
            }
        },
    }


def v2_python_stdio_manifest(compile_command=None):
    adapter = {
        "mode": "stdio",
        "runCommand": [sys.executable, "Main.py"],
    }
    if compile_command is None:
        adapter["compileCommand"] = [sys.executable, "-m", "py_compile", "Main.py"]
    elif compile_command:
        adapter["compileCommand"] = compile_command

    return {
        "manifestVersion": 2,
        "assignmentKey": "fib",
        "problemType": "stdio_cases",
        "comparison": {"mode": "exact"},
        "cases": [
            {
                "name": "case_0",
                "input": {"stdin": "0\n"},
                "expected": {"stdout": "0\n"},
            }
        ],
        "languages": {
            "python": {
                "adapter": adapter,
                "submission": {
                    "fileName": "Main.py",
                },
            }
        },
    }


def v2_python_project_manifest(compile_command=None):
    adapter = {
        "mode": "project",
        "runCommand": [sys.executable, "main.py"],
    }
    if compile_command is None:
        adapter["compileCommand"] = [sys.executable, "-m", "py_compile", "main.py"]
    elif compile_command:
        adapter["compileCommand"] = compile_command

    return {
        "manifestVersion": 2,
        "assignmentKey": "project-fib",
        "problemType": "project_cases",
        "comparison": {"mode": "exact"},
        "cases": [
            {
                "name": "case_0",
                "input": {"stdin": "0\n"},
                "expected": {"stdout": "0\n"},
            }
        ],
        "languages": {
            "python": {
                "adapter": adapter,
            }
        },
    }


def validate_and_read_failure(manifest):
    output = io.StringIO()
    with redirect_stdout(output):
        with unittest.TestCase().assertRaises(SystemExit):
            runtime_main.validate_manifest(manifest)
    return json.loads(output.getvalue())


def run_runtime(submission_source, manifest, language=None, expected_exit_code=0, extra_env=None):
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_path = Path(temp_dir)
        submission_path = temp_path / "submission.py"
        manifest_path = temp_path / "manifest.json"
        submission_path.write_text(submission_source, encoding="utf-8")
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        output = io.StringIO()
        original_argv = sys.argv
        env_patch = {"GRADER_LANGUAGE": language} if language else {}
        if extra_env:
            env_patch.update(extra_env)
        try:
            with patch.object(sys, "argv", ["main.py", str(submission_path), str(manifest_path)]):
                with patch.dict(os.environ, env_patch, clear=False):
                    with redirect_stdout(output):
                        with unittest.TestCase().assertRaises(SystemExit) as context:
                            runtime_main.main()
            unittest.TestCase().assertEqual(expected_exit_code, context.exception.code)
            return json.loads(output.getvalue())
        finally:
            sys.argv = original_argv


def run_project_runtime(files, manifest, language=None, expected_exit_code=0, extra_env=None):
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_path = Path(temp_dir)
        project_path = temp_path / "project"
        manifest_path = temp_path / "manifest.json"
        project_path.mkdir()
        for relative_path, source in files.items():
            file_path = project_path / relative_path
            file_path.parent.mkdir(parents=True, exist_ok=True)
            file_path.write_text(source, encoding="utf-8")
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        output = io.StringIO()
        original_argv = sys.argv
        env_patch = {"GRADER_LANGUAGE": language} if language else {}
        if extra_env:
            env_patch.update(extra_env)
        try:
            with patch.object(sys, "argv", ["main.py", str(project_path), str(manifest_path)]):
                with patch.dict(os.environ, env_patch, clear=False):
                    with redirect_stdout(output):
                        with unittest.TestCase().assertRaises(SystemExit) as context:
                            runtime_main.main()
            unittest.TestCase().assertEqual(expected_exit_code, context.exception.code)
            return json.loads(output.getvalue())
        finally:
            sys.argv = original_argv


def run_project_fixture_runtime(fixture_name):
    fixture_path = REPO_ROOT / "mocksubmission" / "fib-java-project" / fixture_name
    manifest_path = REPO_ROOT / "graders" / "fib-java-project" / "manifest.json"

    with tempfile.TemporaryDirectory() as temp_dir:
        project_path = Path(temp_dir) / "project"
        project_path.mkdir()
        with zipfile.ZipFile(fixture_path) as fixture_zip:
            fixture_zip.extractall(project_path)

        return run_existing_project_runtime(
            project_path,
            runtime_main.load_json_file(str(manifest_path)),
            language="java",
        )


def run_read_only_project_fixture_runtime(fixture_name):
    fixture_path = REPO_ROOT / "mocksubmission" / "fib-java-project" / fixture_name
    manifest_path = REPO_ROOT / "graders" / "fib-java-project" / "manifest.json"

    with tempfile.TemporaryDirectory() as temp_dir:
        project_path = Path(temp_dir) / "project"
        project_path.mkdir()
        with zipfile.ZipFile(fixture_path) as fixture_zip:
            fixture_zip.extractall(project_path)

        make_tree_read_only(project_path)
        try:
            return run_existing_project_runtime(
                project_path,
                runtime_main.load_json_file(str(manifest_path)),
                language="java",
            )
        finally:
            make_tree_user_writable(project_path)


def make_tree_read_only(root_path):
    for current_root, directories, files in os.walk(root_path):
        for file_name in files:
            os.chmod(Path(current_root) / file_name, 0o444)
        for directory in directories:
            os.chmod(Path(current_root) / directory, 0o555)
    os.chmod(root_path, 0o555)


def make_tree_user_writable(root_path):
    os.chmod(root_path, 0o755)
    for current_root, directories, files in os.walk(root_path):
        os.chmod(current_root, 0o755)
        for directory in directories:
            os.chmod(Path(current_root) / directory, 0o755)
        for file_name in files:
            os.chmod(Path(current_root) / file_name, 0o644)


def run_existing_project_runtime(project_path, manifest, language=None, expected_exit_code=0, extra_env=None):
    with tempfile.TemporaryDirectory() as temp_dir:
        manifest_path = Path(temp_dir) / "manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        output = io.StringIO()
        original_argv = sys.argv
        env_patch = {"GRADER_LANGUAGE": language} if language else {}
        if extra_env:
            env_patch.update(extra_env)
        try:
            with patch.object(sys, "argv", ["main.py", str(project_path), str(manifest_path)]):
                with patch.dict(os.environ, env_patch, clear=False):
                    with redirect_stdout(output):
                        with unittest.TestCase().assertRaises(SystemExit) as context:
                            runtime_main.main()
            unittest.TestCase().assertEqual(expected_exit_code, context.exception.code)
            return json.loads(output.getvalue())
        finally:
            sys.argv = original_argv


if __name__ == "__main__":
    unittest.main()
