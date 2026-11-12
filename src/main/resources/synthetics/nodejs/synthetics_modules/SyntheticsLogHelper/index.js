'use strict';

// Redacts query values and headers a canary marks as sensitive before it logs a URL or headers.
let restrictedUrlParameters = [];
let restrictedHeaders = [];

function getSanitizedUrl(url, stepConfig) {
  const parameters = (stepConfig && stepConfig.restrictedUrlParameters) || restrictedUrlParameters;
  try {
    const parsed = new URL(url);
    for (const key of [...parsed.searchParams.keys()]) {
      if (parameters.includes('*') || parameters.map((p) => p.toLowerCase()).includes(key.toLowerCase())) {
        parsed.searchParams.set(key, 'REDACTED');
      }
    }
    return parsed.toString();
  } catch (e) {
    return url;
  }
}

function getSanitizedHeaders(headers, stepConfig) {
  const names = ((stepConfig && stepConfig.restrictedHeaders) || restrictedHeaders).map((h) => h.toLowerCase());
  const out = {};
  for (const [key, value] of Object.entries(headers || {})) {
    out[key] = names.includes(key.toLowerCase()) ? 'REDACTED' : value;
  }
  return out;
}

module.exports = {
  getSanitizedUrl,
  getSanitizedHeaders,
  getSanitizedErrorMessage: (message) => String(message),
  setRestrictedUrlParameters: (values) => {
    restrictedUrlParameters = values || [];
  },
  setRestrictedHeaders: (values) => {
    restrictedHeaders = values || [];
  },
};
