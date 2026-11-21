'use strict';

// The Synthetics library a canary script requires. Steps and HTTP steps run for real; floci runs canaries without a
// browser, so the Puppeteer/Playwright page APIs fail the run with a clear reason.
const http = require('http');
const https = require('https');
const runtime = require('./runtime.js');

const BROWSER_UNAVAILABLE = 'floci runs canaries without a browser: use executeStep or executeHttpStep, '
  + 'or run browser canaries against AWS';

class SyntheticsConfiguration {
  constructor() {
    this.config = {
      continueOnStepFailure: false,
      continueOnHttpStepFailure: true,
      screenshotOnStepStart: false,
      screenshotOnStepSuccess: false,
      screenshotOnStepFailure: false,
      includeRequestHeaders: false,
      includeResponseHeaders: false,
      includeRequestBody: false,
      includeResponseBody: false,
    };
    // withX(value) / disableX() / enableX() setters, as the AWS library offers them.
    return new Proxy(this, {
      get(target, property) {
        if (property in target || typeof property !== 'string') return target[property];
        const match = /^(with|enable|disable)(.+)$/.exec(property);
        if (!match) return undefined;
        const key = match[2].charAt(0).toLowerCase() + match[2].slice(1);
        return (value) => {
          target.config[key] = match[1] === 'with' ? value : match[1] === 'enable';
          return target;
        };
      },
    });
  }

  setConfig(options) {
    Object.assign(this.config, options || {});
  }

  getConfig() {
    return { ...this.config };
  }
}

const configuration = new SyntheticsConfiguration();
let logLevel = 1;

async function executeStep(stepName, functionToExecute, stepConfig) {
  const options = { ...configuration.config, ...(stepConfig || {}) };
  const step = { stepName, status: 'RUNNING', startTime: new Date().toISOString() };
  runtime.state.steps.push(step);
  runtime.record('INFO', [`Executing step: ${stepName}`]);
  try {
    const result = await functionToExecute();
    step.status = 'PASSED';
    runtime.record('INFO', [`Step ${stepName} passed`]);
    return result;
  } catch (error) {
    step.status = 'FAILED';
    step.failureReason = runtime.reasonOf(error);
    runtime.record('ERROR', [`Step ${stepName} failed: ${step.failureReason}`]);
    if (!options.continueOnStepFailure) throw error;
    return undefined;
  } finally {
    step.endTime = new Date().toISOString();
  }
}

function send(requestOptions) {
  return new Promise((resolve, reject) => {
    let options = requestOptions;
    let body;
    if (typeof requestOptions === 'string' || requestOptions instanceof URL) {
      options = new URL(String(requestOptions));
    } else {
      ({ body, ...options } = requestOptions || {});
      options.protocol = options.protocol || 'https:';
    }
    const transport = options.protocol === 'http:' ? http : https;
    const request = transport.request(options, resolve);
    request.on('error', reject);
    if (body !== undefined) request.write(body);
    request.end();
  });
}

async function executeHttpStep(stepName, requestOptions, callback, stepConfig) {
  const options = { ...configuration.config, ...(stepConfig || {}) };
  return executeStep(stepName, async () => {
    const response = await send(requestOptions);
    runtime.record('INFO', [`Response ${response.statusCode} ${response.statusMessage || ''}`.trim()]);
    if (callback) return callback(response);
    response.resume();
    if (response.statusCode < 200 || response.statusCode > 299) {
      throw new Error(`${response.statusCode} ${response.statusMessage || ''}`.trim());
    }
    return undefined;
  }, { ...options, continueOnStepFailure: options.continueOnHttpStepFailure });
}

function browserUnavailable() {
  return Promise.reject(new Error(BROWSER_UNAVAILABLE));
}

module.exports = {
  executeStep,
  executeHttpStep,
  getConfiguration: () => configuration,
  getCanaryName: () => process.env.SYNTHETICS_CANARY_NAME,
  getCanaryArn: () => process.env.SYNTHETICS_CANARY_ARN,
  getRuntimeVersion: () => process.env.SYNTHETICS_RUNTIME_VERSION,
  getCanaryUserAgentString: () => `CloudWatchSynthetics/${process.env.SYNTHETICS_CANARY_ARN}`,
  getLogLevel: () => logLevel,
  setLogLevel: (level) => {
    logLevel = level;
  },
  addUserAgent: async () => undefined,
  addExecutionError: (message) => {
    runtime.state.steps.push({ stepName: 'executionError', status: 'FAILED', failureReason: String(message),
      startTime: new Date().toISOString(), endTime: new Date().toISOString() });
  },
  getRequestResponseLogHelper: () => ({}),
  setRequestResponseLogHelper: () => undefined,
  launch: browserUnavailable,
  getPage: browserUnavailable,
  newPage: browserUnavailable,
  takeScreenshot: browserUnavailable,
  getDefaultLaunchOptions: () => ({}),
  close: async () => undefined,
};
