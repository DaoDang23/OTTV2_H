# OTTV2 load test

Install k6 on the load generator, then provision 3,000 unique API users or run with `REGISTER_USERS=true`. Set `BASE_URL`, `TEST_PASSWORD`, `USERS`, and the unique `EMAIL_PREFIX` / `STUDENT_PREFIX` values for the environment. `SUBMIT_CODE=true` makes each virtual user submit one small Python bot and automatically join ladder when compilation succeeds.

Example PowerShell:

```powershell
$env:BASE_URL = 'http://localhost:8080'
$env:TEST_PASSWORD = 'Use-a-dedicated-load-test-password'
$env:REGISTER_USERS = 'true'
$env:SUBMIT_CODE = 'true'
k6 run .\loadtest\k6\ottv2.js
```

The default profile ramps to 3,000 virtual users over two minutes, holds for five minutes, then ramps down. k6 reports request latency/error rate; Prometheus/Grafana provide queue, worker, and match metrics. Run against a disposable environment: registration and submission calls create persistent test data and submissions have normal rate limits.
