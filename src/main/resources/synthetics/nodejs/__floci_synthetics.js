'use strict';

// The entry point of a canary's backing function. The canary's code is the function's layer, mounted at /opt as
// AWS mounts it; this launcher puts the Synthetics library on the module path, runs the canary's handler and
// reports the run (status, steps and log) back to Synthetics.
const fs = require('fs');
const path = require('path');
const Module = require('module');

const LIBRARY = path.join(__dirname, 'synthetics_modules');
process.env.NODE_PATH = [LIBRARY, process.env.NODE_PATH].filter(Boolean).join(path.delimiter);
Module._initPaths();

const runtime = require('./synthetics_modules/Synthetics/runtime.js');

const ROOTS = ['/opt/nodejs/node_modules', '/opt/nodejs', '/opt'];
const EXTENSIONS = ['.js', '.mjs', '.cjs'];

function resolveHandler(handler) {
  const dot = handler.lastIndexOf('.');
  if (dot <= 0) throw new Error(`The canary handler ${handler} must be file.function`);
  const file = handler.slice(0, dot);
  const name = handler.slice(dot + 1);
  for (const root of ROOTS) {
    for (const extension of EXTENSIONS) {
      const candidate = path.join(root, file + extension);
      if (fs.existsSync(candidate)) return { file: candidate, name };
    }
  }
  throw new Error(`Cannot find the canary script ${file} in the canary code (looked under ${ROOTS.join(', ')})`);
}

exports.handler = async (event) => {
  const handler = process.env.SYNTHETICS_HANDLER;
  const timeoutMs = Number(process.env.SYNTHETICS_TIMEOUT_SECONDS || 840) * 1000;
  return runtime.run(async () => {
    const { file, name } = resolveHandler(handler);
    const loaded = file.endsWith('.mjs') ? await import(file) : require(file);
    const fn = loaded[name] || (loaded.default && loaded.default[name]);
    if (typeof fn !== 'function') throw new Error(`The canary handler ${handler} is not a function`);
    return fn((event && event.payload) || {}, {});
  }, timeoutMs);
};
