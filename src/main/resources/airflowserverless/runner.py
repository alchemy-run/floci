"""Runs one MWAA Serverless workflow run in Apache Airflow, for Floci.

Builds the workflow's DAG from its YAML definition (the DAG Factory format: a DAG id mapping to default_args,
schedule, params and tasks, each task naming an operator by import path, its arguments and its dependencies), then
runs it with ``DAG.test()`` against Floci's endpoint. Each task's state, timing, XCom value and log lines are reported
as JSON lines on stdout, prefixed with ``@@floci@@``; everything else on stdout and stderr is Airflow's own output.

Usage: python runner.py <run directory>, where the directory holds run.json (the definition, run id, override
parameters and logical date) and, optionally, code/ (the workflow's code package, put on sys.path).
"""

import datetime
import importlib
import json
import os
import sys
import threading
import traceback

PREFIX = "@@floci@@"
_lock = threading.Lock()


def emit(event):
    with _lock:
        sys.__stdout__.write(PREFIX + json.dumps(event, default=str) + "\n")
        sys.__stdout__.flush()


def resolve(path):
    module, _, name = path.rpartition(".")
    if not module:
        raise ValueError("%s is not a module path" % path)
    return getattr(importlib.import_module(module), name)


def build_dag(spec):
    import yaml
    from airflow.sdk import DAG

    document = yaml.safe_load(spec["definition"]) or {}
    shared = document.pop("default", {}) or {}
    dags = [(key, value) for key, value in document.items() if isinstance(value, dict)]
    if len(dags) != 1:
        raise ValueError("The definition must define exactly one DAG; it defines %d." % len(dags))
    dag_id, config = dags[0]
    default_args = dict(shared.get("default_args", {}) or {})
    default_args.update(config.get("default_args", {}) or {})
    for key in ("start_date", "end_date"):
        default_args.pop(key, None)
    for key in ("retry_delay", "execution_timeout"):
        if isinstance(default_args.get(key), (int, float)):
            default_args[key] = datetime.timedelta(seconds=default_args[key])
    params = dict(shared.get("params", {}) or {})
    params.update(config.get("params", {}) or {})
    dag = DAG(
        dag_id=dag_id,
        schedule=None,
        start_date=datetime.datetime(2024, 1, 1, tzinfo=datetime.timezone.utc),
        catchup=False,
        default_args=default_args,
        params=params,
        description=config.get("description"),
        tags=config.get("tags") or [],
    )
    tasks = config.get("tasks") or {}
    if isinstance(tasks, list):
        tasks = {task.pop("task_id"): task for task in tasks}
    created = {}
    dependencies = {}
    for task_id, task_config in tasks.items():
        arguments = dict(task_config or {})
        operator = arguments.pop("operator", None)
        if not operator:
            raise ValueError("Task %s names no operator." % task_id)
        dependencies[task_id] = arguments.pop("dependencies", []) or []
        arguments.pop("task_group_name", None)
        if isinstance(arguments.get("python_callable"), str):
            arguments["python_callable"] = resolve(arguments["python_callable"])
        if "python_callable_name" in arguments:
            name = arguments.pop("python_callable_name")
            location = arguments.pop("python_callable_file", None)
            if location:
                namespace = {}
                with open(location) as source:
                    exec(compile(source.read(), location, "exec"), namespace)  # noqa: S102 - the user's code
                arguments["python_callable"] = namespace[name]
            else:
                arguments["python_callable"] = resolve(name)
        for key in ("retry_delay", "execution_timeout"):
            if isinstance(arguments.get(key), (int, float)):
                arguments[key] = datetime.timedelta(seconds=arguments[key])
        created[task_id] = resolve(operator)(task_id=task_id, dag=dag, **arguments)
    for task_id, upstream in dependencies.items():
        for parent in upstream:
            if parent not in created:
                raise ValueError("Task %s depends on %s, which isn't defined." % (task_id, parent))
            created[parent] >> created[task_id]
    return dag


def instrument(dag):
    """Reports each task as it starts; Airflow prints its logs between the start and the next task's."""

    def started(context):
        ti = context["ti"]
        emit({"type": "task", "task": ti.task_id, "state": "RUNNING", "time": now(),
              "try": getattr(ti, "try_number", 1)})

    for task in dag.tasks:
        callbacks = task.on_execute_callback
        if callbacks is None:
            callbacks = []
        elif not isinstance(callbacks, list):
            callbacks = [callbacks]
        task.on_execute_callback = [started] + list(callbacks)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def report(dag_run):
    from airflow.models.xcom import XComModel  # noqa: F401 - imported for its side effects on the session
    for ti in dag_run.get_task_instances():
        value = None
        try:
            value = ti.xcom_pull(task_ids=ti.task_id, key="return_value")
        except Exception:  # noqa: BLE001 - an XCom that can't be read is reported as absent
            value = None
        state = str(ti.state.value if hasattr(ti.state, "value") else ti.state or "none").upper()
        emit({
            "type": "task",
            "task": ti.task_id,
            "state": state,
            "operator": ti.operator,
            "started": ti.start_date.isoformat() if ti.start_date else None,
            "ended": ti.end_date.isoformat() if ti.end_date else None,
            "try": ti.try_number,
            "xcom": None if value is None else (value if isinstance(value, str) else json.dumps(value, default=str)),
        })
    state = str(dag_run.state.value if hasattr(dag_run.state, "value") else dag_run.state).upper()
    emit({"type": "run", "state": state, "time": now()})


def load(directory):
    """The run's spec, with its code package on the Python path."""
    code = os.path.join(directory, "code")
    if os.path.isdir(code) and code not in sys.path:
        sys.path.insert(0, code)
    with open(os.path.join(directory, "run.json")) as source:
        return json.load(source)


def register(directory):
    """Writes the DAG file Airflow's DAG bundle parses, so DAG.test() finds the DAG serialized."""
    dags = os.environ.get("AIRFLOW__CORE__DAGS_FOLDER", os.path.join(directory, "home", "dags"))
    os.makedirs(dags, exist_ok=True)
    with open(os.path.join(dags, "floci_workflow.py"), "w") as out:
        out.write("# An Airflow DAG built from the MWAA Serverless workflow definition (airflow, dag).\n"
                  "import sys\n"
                  "sys.path.insert(0, %r)\n"
                  "import runner as _runner\n"
                  "dag = _runner.build_dag(_runner.load(%r))\n" % (directory, directory))


def main():
    directory = sys.argv[1]
    spec = load(directory)
    try:
        dag = build_dag(spec)
    except Exception as error:  # noqa: BLE001 - a definition that doesn't load fails the run
        emit({"type": "run", "state": "FAILED", "error": "The workflow definition could not be loaded: %s" % error,
              "trace": traceback.format_exc()})
        return
    for task in dag.tasks:
        emit({"type": "task", "task": task.task_id, "state": "QUEUED", "operator": type(task).__name__,
              "time": now()})
    register(directory)
    instrument(dag)
    emit({"type": "run", "state": "RUNNING", "time": now()})
    logical = spec.get("logicalDate")
    logical_date = datetime.datetime.fromisoformat(logical) if logical else datetime.datetime.now(
        datetime.timezone.utc)
    try:
        dag_run = dag.test(logical_date=logical_date, run_conf=spec.get("overrides") or None)
    except Exception as error:  # noqa: BLE001 - a run that can't start fails
        emit({"type": "run", "state": "FAILED", "error": str(error), "trace": traceback.format_exc()})
        return
    report(dag_run)


if __name__ == "__main__":
    main()
