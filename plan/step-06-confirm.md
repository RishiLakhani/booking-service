# Step 6 — Confirm

**Goal:** `POST /reservations/{id}/confirm`: owner-only, guarded transition `held → confirmed`.
**Serves:** bar 1 ("never confirmed to two users"), bar 6 (act only on your own holds); "a release must never resurrect a confirmed seat".

## Tasks
- A single guarded `UPDATE … WHERE id AND user_id AND status='held' AND expires_at > now()`.
- On 0 rows, load the reservation to choose the decline reason.
- Return 200 with `status: confirmed`.

## Done when
- Owner confirms within the TTL → seat shows `confirmed`, and it never expires.
- Another user's token → declined; the hold is unchanged.
- Confirm after the TTL → declined; the seat is re-bookable.
- Confirm racing a lazy expiry → exactly one wins; the seat is never both confirmed and re-held.

## Open questions
- 404 vs 403 for a non-owner.
- Repeat confirm; confirm after expiry (409 vs 410).
