# Amazon FinSpace (end of support)

**Endpoint:** requests signed for `finspace-api` (the FinSpace data API).

Amazon FinSpace reached end of support on 2026-10-07. Its control plane (`finspace`) is gone from AWS's DNS, so
floci serves none of it, and no account can create a FinSpace environment. The data API still answers on AWS, and
refuses every call made without an environment; floci answers each request signed for `finspace-api` the same
way, with a plain-text 403, `Failed to retrieve environment`, and `x-amzn-ErrorType: AccessDeniedException`.
