"""Lines a canary logs go to the run's log artifact and to the backing function's CloudWatch Logs."""
from aws_synthetics.common import _runtime

_LEVELS = {"DEBUG": 10, "INFO": 20, "WARN": 30, "WARNING": 30, "ERROR": 40, "CRITICAL": 50}
_level = 20


def set_level(level):
    global _level
    _level = _LEVELS.get(str(level).upper(), level if isinstance(level, int) else 20)


def _log(level, message, *args):
    if _LEVELS[level] >= _level:
        _runtime.record(level, str(message) % args if args else str(message))


def debug(message, *args):
    _log("DEBUG", message, *args)


def info(message, *args):
    _log("INFO", message, *args)


def warn(message, *args):
    _log("WARN", message, *args)


warning = warn


def error(message, *args):
    _log("ERROR", message, *args)


def critical(message, *args):
    _log("CRITICAL", message, *args)


exception = error
