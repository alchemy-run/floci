'use strict';

// One canary run: the steps it executed and the lines it logged, reset at the start of every run.
const state = { steps: [], log: [] };

const original = {
  log: console.log.bind(console),
  info: console.info.bind(console),
  warn: console.warn.bind(console),
  error: console.error.bind(console),
  debug: console.debug.bind(console),
};

function format(value) {
  if (typeof value === 'string') return value;
  if (value instanceof Error) return value.stack || value.message;
  try {
    return JSON.stringify(value);
  } catch (e) {
    return String(value);
  }
}

function record(level, args) {
  state.log.push(`${new Date().toISOString()} ${level}: ${args.map(format).join(' ')}`);
}

for (const [method, level] of [['log', 'INFO'], ['info', 'INFO'], ['warn', 'WARN'], ['error', 'ERROR'],
  ['debug', 'DEBUG']]) {
  console[method] = (...args) => {
    record(level, args);
    original[method](...args);
  };
}

function reasonOf(error) {
  if (error && typeof error.message === 'string' && error.message.length > 0) return error.message;
  return String(error);
}

/**
 * Runs the canary body within its timeout. A run passes when the body returns and no step failed; a step that
 * failed but was allowed to continue still fails the run, as it does on AWS.
 */
async function run(body, timeoutMs) {
  state.steps = [];
  state.log = [];
  const startTime = new Date().toISOString();
  let status = 'PASSED';
  let reason;
  let timer;
  record('INFO', ['Start Canary']);
  try {
    await Promise.race([
      Promise.resolve().then(body),
      new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`Canary timed out after ${timeoutMs / 1000} seconds`)),
          timeoutMs);
      }),
    ]);
    const failed = state.steps.find((step) => step.status === 'FAILED');
    if (failed) {
      status = 'FAILED';
      reason = `Failed step: ${failed.stepName}. ${failed.failureReason}`;
    }
  } catch (error) {
    status = 'FAILED';
    reason = reasonOf(error);
    record('ERROR', [`Canary error: ${reason}`]);
  } finally {
    clearTimeout(timer);
  }
  for (const step of state.steps) {
    if (step.status === 'RUNNING') {
      step.status = 'FAILED';
      step.failureReason = reason || 'The step did not finish before the canary ended';
      step.endTime = new Date().toISOString();
    }
  }
  record(status === 'PASSED' ? 'INFO' : 'ERROR', [`Canary ${status === 'PASSED' ? 'successful' : 'failed'}`]);
  return {
    status,
    reason,
    steps: state.steps,
    log: state.log.join('\n'),
    startTime,
    endTime: new Date().toISOString(),
  };
}

module.exports = { state, record, run, reasonOf };
