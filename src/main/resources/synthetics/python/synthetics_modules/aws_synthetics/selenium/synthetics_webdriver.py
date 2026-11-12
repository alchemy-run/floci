"""The Synthetics webdriver library. Steps run for real; floci runs canaries without a browser, so the Selenium
browser APIs fail the run with a clear reason."""
import inspect
import os

from aws_synthetics.common import _runtime, synthetics_configuration

BROWSER_UNAVAILABLE = ("floci runs canaries without a browser: use execute_step, "
                       "or run browser canaries against AWS")


def _finish(step, error=None):
    step["endTime"] = _runtime.now()
    if error is None:
        step["status"] = "PASSED"
        _runtime.record("INFO", f"Step {step['stepName']} passed")
    else:
        step["status"] = "FAILED"
        step["failureReason"] = _runtime.reason_of(error)
        _runtime.record("ERROR", f"Step {step['stepName']} failed: {step['failureReason']}")


def execute_step(step_name, function_to_execute, step_config=None):
    """Runs one step; a coroutine function makes this awaitable."""
    options = {**synthetics_configuration.get_config(), **(step_config or {})}
    step = {"stepName": step_name, "status": "RUNNING", "startTime": _runtime.now()}
    _runtime.state["steps"].append(step)
    _runtime.record("INFO", f"Executing step: {step_name}")

    def failed(error):
        _finish(step, error)
        if not options.get("continue_on_step_failure"):
            raise error

    if inspect.iscoroutinefunction(function_to_execute):
        async def run_async():
            try:
                result = await function_to_execute()
            except Exception as error:  # noqa: BLE001
                failed(error)
                return None
            _finish(step)
            return result
        return run_async()
    try:
        result = function_to_execute()
    except Exception as error:  # noqa: BLE001
        failed(error)
        return None
    _finish(step)
    return result


def get_canary_name():
    return os.environ.get("SYNTHETICS_CANARY_NAME")


def get_canary_arn():
    return os.environ.get("SYNTHETICS_CANARY_ARN")


def get_runtime_version():
    return os.environ.get("SYNTHETICS_RUNTIME_VERSION")


def add_user_agent(*_args, **_kwargs):
    return None


def Chrome(*_args, **_kwargs):  # noqa: N802 - the AWS library's name
    raise RuntimeError(BROWSER_UNAVAILABLE)


def Firefox(*_args, **_kwargs):  # noqa: N802 - the AWS library's name
    raise RuntimeError(BROWSER_UNAVAILABLE)
