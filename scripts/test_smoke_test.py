import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("smoke-test.py")
spec = importlib.util.spec_from_file_location("smoke_test", SCRIPT_PATH)
smoke_test = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = smoke_test
spec.loader.exec_module(smoke_test)


class SmokeTestScenarioTest(unittest.TestCase):

    def test_resolve_scenario_default_success(self):
        target = smoke_test.resolve_scenario("success")

        self.assertEqual("success", target.scenario)
        self.assertEqual("fib", target.grader)
        self.assertEqual(smoke_test.DEFAULT_FIXTURE, target.fixture)
        self.assertEqual("SUCCEEDED", target.expected_status)

    def test_resolve_target_project_override_keeps_expected_status(self):
        args = smoke_test.argparse.Namespace(
            scenario="project-java-pass",
            api_base=smoke_test.DEFAULT_API_BASE,
            grader=None,
            fixture=None,
            expect_status=None,
            institution="local",
            user="smoke-tester",
            timeout_seconds=120,
            poll_interval=2.0,
        )

        target = smoke_test.resolve_target(args)

        self.assertEqual("project-java-pass", target.scenario)
        self.assertEqual("fib-java-project", target.grader)
        self.assertEqual("SUCCEEDED", target.expected_status)

    def test_resolve_target_explicit_overrides_win(self):
        args = smoke_test.argparse.Namespace(
            scenario="project-java-build-failure",
            api_base=smoke_test.DEFAULT_API_BASE,
            grader="fib",
            fixture=Path("mocksubmission/fib/fibpass1.py"),
            expect_status="SUCCEEDED",
            institution="local",
            user="smoke-tester",
            timeout_seconds=120,
            poll_interval=2.0,
        )

        target = smoke_test.resolve_target(args)

        self.assertEqual("fib", target.grader)
        self.assertEqual(smoke_test.resolve_fixture(Path("mocksubmission/fib/fibpass1.py")), target.fixture)
        self.assertEqual("SUCCEEDED", target.expected_status)


if __name__ == "__main__":
    unittest.main()
