'use strict';

// Lines a canary logs go to the run's log artifact and to the backing function's CloudWatch Logs.
module.exports = {
  log: (...args) => console.info(...args),
  info: (...args) => console.info(...args),
  warn: (...args) => console.warn(...args),
  error: (...args) => console.error(...args),
  debug: (...args) => console.debug(...args),
};
