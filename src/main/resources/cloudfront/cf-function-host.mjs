// CloudFront Functions runtime host.
//
// Floci launches this as a long-lived child process (see
// CloudFrontFunctionRuntime.java) and talks to it over stdin/stdout with
// newline-delimited JSON. Every CloudFront Function — whether invoked by the
// emulated edge or by the TestFunction API — runs here, so local behavior and
// `aws cloudfront test-function` results come from the same engine.
//
// The sandbox is a bare `node:vm` context. A fresh V8 context has ONLY the
// ECMAScript intrinsics: no fetch, no timers, no require/import, no process,
// no Buffer, no console, no URL, no TextEncoder. That is exactly the shape of
// the CloudFront Functions runtime, which is not Node. Dynamic code generation
// (eval / new Function) is disabled, matching CloudFront. The only globals
// added back are `console` (CloudFront provides it, and its output becomes
// FunctionExecutionLogs) and the binding for the built-in `cloudfront` module.
//
// The guiding rule is that the emulator must never be MORE permissive than
// CloudFront: code that runs here but would fail on deploy is the failure mode
// this exists to prevent.
//
// Frames (one JSON object per line):
//   in   {"t":"exec","id","code","event","kvsIds":[..],"timeoutMs","maxCodeBytes"}
//   out  {"t":"kvs","id","cid","op":"get"|"exists"|"meta","kvsId","key","format"}
//   in   {"t":"kvs.result","id","cid","ok",("value"|"error")}
//   out  {"t":"result","id","ok","output"?,"error"?,"logs":[..],"micros"}
//   out  {"t":"ready","protocol":1}

import { createInterface } from "node:readline";
import vm from "node:vm";

const PROTOCOL = 1;
/** CloudFront's hard limit on function source size. */
const DEFAULT_MAX_CODE_BYTES = 10240;
const DEFAULT_TIMEOUT_MS = 5000;

/** Globals a CloudFront Function cannot have; used only to explain errors. */
const ABSENT_GLOBALS = new Set([
  "fetch",
  "setTimeout",
  "setInterval",
  "setImmediate",
  "clearTimeout",
  "clearInterval",
  "queueMicrotask",
  "require",
  "process",
  "Buffer",
  "XMLHttpRequest",
  "WebSocket",
  "URL",
  "URLSearchParams",
  "TextEncoder",
  "TextDecoder",
  "structuredClone",
  "atob",
  "btoa",
  "window",
  "document",
  "localStorage",
  "importScripts",
]);

const pending = new Map();
let nextCid = 0;

const send = (frame) => {
  process.stdout.write(JSON.stringify(frame) + "\n");
};

/** Ask the Java side to resolve a key-value-store operation. */
const callHost = (id, payload) =>
  new Promise((resolve, reject) => {
    const cid = String(++nextCid);
    pending.set(cid, { resolve, reject });
    send({ t: "kvs", id, cid, ...payload });
  });

/**
 * Strip the built-in `cloudfront` module import (the only import CloudFront
 * Functions allow) and reject every other module reference. Line count is
 * preserved so error line numbers still point at the user's source.
 */
const prepareSource = (code) => {
  const bindings = [];
  const lines = code.split("\n");
  const prepared = lines.map((line) => {
    const cloudfront = line.match(
      /^\s*import\s+([A-Za-z_$][\w$]*)\s+from\s+['"]cloudfront['"]\s*;?\s*$/,
    );
    if (cloudfront) {
      bindings.push(cloudfront[1]);
      return "";
    }
    if (/^\s*(?:import|export)\s/.test(line) || /^\s*import\s*\(/.test(line)) {
      throw new Error(
        `CloudFront Functions can only import the built-in "cloudfront" module. Unsupported module statement: ${line.trim()}`,
      );
    }
    return line;
  });
  return { source: prepared.join("\n"), bindings };
};

/** Explain a bare ReferenceError for a global CloudFront does not provide. */
const describeError = (error) => {
  const message =
    error && error.message ? String(error.message) : String(error);
  const name = error && error.name ? String(error.name) : "";
  // Errors thrown inside the vm context belong to that realm, so `instanceof`
  // against the host realm never matches — compare by name instead.
  if (name === "ReferenceError") {
    const named = message.match(/^(\w+) is not defined/);
    if (named && ABSENT_GLOBALS.has(named[1])) {
      return `${message} — the CloudFront Functions runtime is not Node.js and does not provide "${named[1]}". Only the built-in "cloudfront" module and standard JavaScript are available.`;
    }
  }
  if (name === "EvalError" || /Code generation from strings/.test(message)) {
    return `${message} — CloudFront Functions do not allow eval() or the Function constructor.`;
  }
  return message;
};

const makeKvs = (id, kvsIds) => (requestedId) => {
  const kvsId = requestedId === undefined ? null : String(requestedId);
  if (kvsId !== null && kvsIds.length > 0 && !kvsIds.includes(kvsId)) {
    throw new Error(
      `Key value store "${kvsId}" is not associated with this function.`,
    );
  }
  if (kvsId === null && kvsIds.length === 0) {
    throw new Error("No key value store is associated with this function.");
  }
  return {
    get: (key, options) =>
      callHost(id, {
        op: "get",
        kvsId,
        key: String(key),
        format: options && options.format ? String(options.format) : "string",
      }),
    exists: (key) => callHost(id, { op: "exists", kvsId, key: String(key) }),
    meta: () => callHost(id, { op: "meta", kvsId }),
  };
};

const runExec = async (frame) => {
  const id = frame.id;
  const logs = [];
  const maxCodeBytes = frame.maxCodeBytes || DEFAULT_MAX_CODE_BYTES;
  const timeoutMs = frame.timeoutMs || DEFAULT_TIMEOUT_MS;
  const started = process.hrtime.bigint();
  const micros = () => Number((process.hrtime.bigint() - started) / 1000n);

  try {
    const code = String(frame.code == null ? "" : frame.code);
    const size = Buffer.byteLength(code, "utf8");
    if (size > maxCodeBytes) {
      throw new Error(
        `The function code is ${size} bytes, which exceeds the CloudFront maximum of ${maxCodeBytes} bytes.`,
      );
    }

    const { source, bindings } = prepareSource(code);

    const record = (level) => (...args) => {
      logs.push(
        `${level}: ` +
          args
            .map((a) => {
              if (typeof a === "string") return a;
              try {
                return JSON.stringify(a);
              } catch {
                return String(a);
              }
            })
            .join(" "),
      );
    };
    const sandbox = {
      console: {
        log: record("INFO"),
        info: record("INFO"),
        warn: record("WARN"),
        error: record("ERROR"),
        debug: record("DEBUG"),
      },
    };
    let originOverride;
    let originIdOverride;
    const cf = {
      kvs: makeKvs(id, (frame.kvsIds || []).map(String)),
      updateRequestOrigin: (origin) => {
        originOverride = origin;
      },
      selectRequestOriginById: (originId) => {
        originIdOverride = String(originId);
      },
    };
    for (const binding of bindings) {
      sandbox[binding] = cf;
    }

    // A fresh context per invocation: no state leaks between requests, and
    // no need to freeze intrinsics (which would be stricter than CloudFront).
    const context = vm.createContext(sandbox, {
      name: "cloudfront-function",
      codeGeneration: { strings: false, wasm: false },
    });

    const factory = vm.runInContext(
      `(function(){"use strict";\n${source}\n;return typeof handler === "function" ? handler : null;})`,
      context,
      { filename: "cloudfront-function.js", timeout: timeoutMs },
    );
    const handler = factory();
    if (!handler) {
      throw new Error(
        'The function does not declare a "handler" function. CloudFront Functions must export `function handler(event) { ... }`.',
      );
    }

    const event = vm.runInContext("(function(s){return JSON.parse(s);})", context)(
      JSON.stringify(frame.event ?? {}),
    );
    const stringify = vm.runInContext(
      "(function(v){return JSON.stringify(v === undefined ? null : v);})",
      context,
    );

    let timer;
    const output = await Promise.race([
      Promise.resolve(handler(event)),
      new Promise((_, reject) => {
        timer = setTimeout(
          () =>
            reject(
              new Error(
                `The function did not complete within ${timeoutMs}ms. CloudFront Functions must complete synchronously within about 1ms of CPU time.`,
              ),
            ),
          timeoutMs,
        );
      }),
    ]).finally(() => clearTimeout(timer));

    send({
      t: "result",
      id,
      ok: true,
      output: JSON.parse(stringify(output)),
      origin: originOverride === undefined ? null : JSON.parse(stringify(originOverride)),
      originId: originIdOverride === undefined ? null : originIdOverride,
      logs,
      micros: micros(),
    });
  } catch (error) {
    send({
      t: "result",
      id,
      ok: false,
      error: describeError(error),
      logs,
      micros: micros(),
    });
  }
};

createInterface({ input: process.stdin }).on("line", (line) => {
  if (!line.trim()) {
    return;
  }
  let frame;
  try {
    frame = JSON.parse(line);
  } catch (error) {
    process.stderr.write(`cf-function-host: unparseable frame: ${error}\n`);
    return;
  }
  if (frame.t === "exec") {
    void runExec(frame);
    return;
  }
  if (frame.t === "kvs.result") {
    const waiter = pending.get(frame.cid);
    if (!waiter) {
      return;
    }
    pending.delete(frame.cid);
    if (frame.ok) {
      waiter.resolve(frame.value);
    } else {
      waiter.reject(new Error(frame.error || "key value store error"));
    }
    return;
  }
  process.stderr.write(`cf-function-host: unknown frame type: ${frame.t}\n`);
});

send({ t: "ready", protocol: PROTOCOL });
