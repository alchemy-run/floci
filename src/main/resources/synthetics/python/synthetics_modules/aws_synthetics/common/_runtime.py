"""One canary run: the steps it executed and the lines it logged, reset at the start of every run."""
import asyncio
import datetime
import inspect
import threading

state = {"steps": [], "log": []}


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def record(level, message):
    state["log"].append(f"{now()} {level}: {message}")
    print(f"{level}: {message}", flush=True)


def reason_of(error):
    text = str(error)
    return text if text else error.__class__.__name__


def run(body, timeout):
    """Runs the canary body within its timeout. A step that failed but was allowed to continue still fails the run."""
    state["steps"] = []
    state["log"] = []
    start = now()
    outcome = {}
    record("INFO", "Start Canary")

    def target():
        try:
            result = body()
            if inspect.iscoroutine(result):
                asyncio.run(result)
        except BaseException as error:  # noqa: BLE001 - every failure fails the run
            outcome["error"] = reason_of(error)

    worker = threading.Thread(target=target, daemon=True)
    worker.start()
    worker.join(timeout)
    status, reason = "PASSED", None
    if worker.is_alive():
        status, reason = "FAILED", f"Canary timed out after {timeout:g} seconds"
    elif "error" in outcome:
        status, reason = "FAILED", outcome["error"]
    else:
        failed = next((step for step in state["steps"] if step["status"] == "FAILED"), None)
        if failed is not None:
            status, reason = "FAILED", f"Failed step: {failed['stepName']}. {failed.get('failureReason', '')}"
    if reason:
        record("ERROR", f"Canary error: {reason}")
    for step in state["steps"]:
        if step["status"] == "RUNNING":
            step.update(status="FAILED", failureReason=reason or "The step did not finish", endTime=now())
    record("INFO" if status == "PASSED" else "ERROR", "Canary successful" if status == "PASSED" else "Canary failed")
    return {"status": status, "reason": reason, "steps": state["steps"], "log": "\n".join(state["log"]),
            "startTime": start, "endTime": now()}
