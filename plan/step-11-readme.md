# Step 11 — Submission README

**Goal:** a top-level `README.md` that lets graders run, verify and understand the service quickly.
**Serves:** Deliverables 2, 3, 4.

## Structure (top-down for a reviewer)
1. What it is, plus one line with the live URL, the burst command and the plan.
2. **Live deployment:** URL, health/metrics/log links, the burst command against the live URL, the AWS architecture, measured results, and the embedded recording of logs under load.
3. **What it does,** with an explicit note that reserve returns `held` (time-boxed hold) rather than the brief's sample `confirmed`.
4. **How correctness is guaranteed:** one table mapping each rule to its database mechanism.
5. **Running locally:** full Docker, or from source.
6. **API:** auth, shows, reserve, confirm, errors (with outcome tables).
7. **Observability:** metrics, logs, health.
8. **Burst test:** what it fires and checks; local and live sample results.
9. **Tests:** how to run them, coverage, and the planted-bug check.
10. Tech stack, repository layout, progress.

## Done when
Someone new can go from clone to running the burst (locally or against the live URL) using only the README. ✅
