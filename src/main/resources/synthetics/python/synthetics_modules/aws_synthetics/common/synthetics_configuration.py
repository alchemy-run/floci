"""Canary configuration (step failure handling and reporting options)."""

_config = {
    "continue_on_step_failure": False,
    "screenshot_on_step_start": False,
    "screenshot_on_step_success": False,
    "screenshot_on_step_failure": False,
}


def set_config(options):
    _config.update(options or {})


def get_config():
    return dict(_config)


def get_continue_on_step_failure():
    return bool(_config.get("continue_on_step_failure"))
