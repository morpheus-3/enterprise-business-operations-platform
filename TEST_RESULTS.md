# Verification results

Verified on 9 October 2026 with Python 3.10, Java 21 (operations only) and Chromium.

**1 automated Python/API tests passed.** Real Java HTTP authentication/RBAC, integer validation, malformed JSON, sale totals and idempotent retries; separate Java checks cover rollback, restock, concurrent stock protection and audit.

**Browser end-to-end scenario passed**, with desktop (1440 x 1000) and mobile (390 x 844) layouts. No JavaScript runtime errors or horizontal overflow were observed. Screenshots are included in `screenshots/`; `BROWSER_TEST_RESULTS.json` records the outcome.

The browser scenario uses temporary test role tokens and a disposable H2 database. Tests exercise real SQL transactions and HTTP requests. This project has no AI model dependency; Groq is not needed for operations processing.

Run the test command in README.md to reproduce automated checks. The GitHub Actions workflow runs these tests independently on every push and pull request.
