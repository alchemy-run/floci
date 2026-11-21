"""The entry point of a Python canary's backing function.

The canary's code is the function's layer, mounted at /opt as AWS mounts it. This launcher puts the Synthetics
library on the import path, runs the canary's handler and reports the run (status, steps and log) back to Synthetics.
"""
import importlib.util
import os
import sys

LIBRARY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "synthetics_modules")
if LIBRARY not in sys.path:
    sys.path.insert(0, LIBRARY)
for root in ("/opt/python", "/opt"):
    if root not in sys.path:
        sys.path.append(root)

from aws_synthetics.common import _runtime  # noqa: E402

ROOTS = ("/opt/python", "/opt")


def _load(spec):
    module_path, _, name = spec.rpartition(".")
    if not module_path:
        raise Exception(f"The canary handler {spec} must be file.function")
    for root in ROOTS:
        candidate = os.path.join(root, module_path + ".py")
        if os.path.exists(candidate):
            module_spec = importlib.util.spec_from_file_location(
                "canary_" + module_path.replace("/", "_"), candidate)
            module = importlib.util.module_from_spec(module_spec)
            module_spec.loader.exec_module(module)
            fn = getattr(module, name, None)
            if not callable(fn):
                raise Exception(f"The canary handler {spec} is not a function")
            return fn
    raise Exception(f"Cannot find the canary script {module_path} in the canary code "
                    f"(looked under {', '.join(ROOTS)})")


def handler(event, context):
    spec = os.environ["SYNTHETICS_HANDLER"]
    timeout = float(os.environ.get("SYNTHETICS_TIMEOUT_SECONDS", "840"))
    payload = event.get("payload", {}) if isinstance(event, dict) else {}
    return _runtime.run(lambda: _load(spec)(payload, context), timeout)
