# /// script
# requires-python = ">=3.11"
# dependencies = ["aiohttp>=3.9"]
# ///
"""
On-sale stampede against a running booking-service.

Creates a fresh show, fires a concurrent mix of legitimate and adversarial requests, then checks
every correctness rule from the API's responses, the final show state and the Prometheus metrics.
Checks are time-aware: holds expire (HOLD_TTL), so "one owner per seat" and "max 4 seats per user"
are judged at every moment, not over the whole run.

    ADMIN_SECRET=... uv run burst/burst.py http://localhost:8080
"""

import argparse
import asyncio
import json
import os
import random
import resource
import statistics
import sys
import time
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from email.utils import parsedate_to_datetime

import aiohttp

# The Date header has 1-second resolution; allow for it when comparing server times.
CLOCK_SLACK = timedelta(seconds=1.5)
FOREVER = datetime.max.replace(tzinfo=None)


# ---------------------------------------------------------------------------------------------
# HTTP plumbing
# ---------------------------------------------------------------------------------------------

@dataclass
class Result:
    scenario: str
    status: int | None  # None = client-side failure (timeout, connection reset, ...)
    body: object
    latency_ms: float
    server_time: datetime | None = None  # from the Date header
    error: str | None = None

    @property
    def code(self) -> str | None:
        return self.body.get("error") if isinstance(self.body, dict) else None


class Api:
    def __init__(self, base_url: str, concurrency: int):
        self.base = base_url.rstrip("/")
        self.concurrency = concurrency
        self.sem = asyncio.Semaphore(concurrency)
        self.results: list[Result] = []
        self.session: aiohttp.ClientSession | None = None

    async def __aenter__(self):
        self.session = aiohttp.ClientSession(
            connector=aiohttp.TCPConnector(limit=self.concurrency, limit_per_host=self.concurrency),
            timeout=aiohttp.ClientTimeout(total=60))
        return self

    async def __aexit__(self, *exc):
        await self.session.close()

    async def call(self, scenario: str, method: str, path: str, *, token: str | None = None,
                   json_body=None, content: str | None = None, headers: dict | None = None,
                   record: bool = True) -> Result:
        hdrs = dict(headers or {})
        if token:
            hdrs["Authorization"] = f"Bearer {token}"
        async with self.sem:
            start = time.perf_counter()
            try:
                async with self.session.request(method, self.base + path, headers=hdrs,
                                                json=json_body if content is None else None,
                                                data=content) as resp:
                    text = await resp.text()
                    try:
                        body = json.loads(text) if text else None
                    except ValueError:
                        body = text
                    date = resp.headers.get("Date")
                    result = Result(scenario, resp.status, body, (time.perf_counter() - start) * 1000,
                                    parsedate_to_datetime(date).replace(tzinfo=None) if date else None)
            except (aiohttp.ClientError, asyncio.TimeoutError) as e:
                result = Result(scenario, None, None, (time.perf_counter() - start) * 1000, error=type(e).__name__)
        if record:
            self.results.append(result)
        return result

    async def token(self, user_id: str, admin_secret: str | None = None) -> str:
        headers = {"X-Admin-Secret": admin_secret} if admin_secret else None
        body = {"user_id": user_id, "role": "admin"} if admin_secret else {"user_id": user_id}
        r = await self.call("setup", "POST", "/auth/token", json_body=body, headers=headers, record=False)
        if r.status != 200:
            raise SystemExit(f"Could not get a token for {user_id}: HTTP {r.status} {r.body or r.error}")
        return r.body["token"]

    async def metrics(self) -> dict[str, float] | None:
        r = await self.call("metrics", "GET", "/actuator/prometheus", record=False)
        if r.status != 200 or not isinstance(r.body, str):
            return None
        out = {}
        for line in r.body.splitlines():
            if line.startswith(("reservations_", "seats_available")):
                name, _, value = line.rpartition(" ")
                out[name] = float(value)
        return out


def reserve(api: Api, scenario: str, show_id: str, token: str | None, seats, key: str, extra=None):
    return api.call(scenario, "POST", f"/shows/{show_id}/reserve", token=token,
                    json_body={"seats": seats, **(extra or {})}, headers={"Idempotency-Key": key})


def parse_ts(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).replace(tzinfo=None)


# ---------------------------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------------------------

class Checks:
    def __init__(self):
        self.items: list[tuple[str, str, str]] = []  # (name, PASS|FAIL|SKIP, detail)

    def check(self, name: str, ok: bool, detail: str = ""):
        self.items.append((name, "PASS" if ok else "FAIL", detail))

    def skip(self, name: str, reason: str):
        self.items.append((name, "SKIP", reason))

    @property
    def all_passed(self) -> bool:
        return all(state != "FAIL" for _, state, _ in self.items)


# ---------------------------------------------------------------------------------------------
# The run
# ---------------------------------------------------------------------------------------------

async def run(args) -> int:
    rng = random.Random(args.seed)
    checks = Checks()
    seats = [f"S{i:04d}" for i in range(1, args.seats + 1)]
    hot = seats[:args.hot_seats]
    base = args.hot_seats
    contested = seats[base:base + 50]          # overlapping multi-seat zone
    limit_block = seats[base + 50:base + 550]  # per-user limit attack
    retry_block = seats[base + 550:base + 750]  # idempotent retry storm
    spoof_block = seats[base + 750:base + 800]  # identity spoofing
    normal_block = seats[base + 800:]
    if len(normal_block) < 10:
        raise SystemExit(f"--seats must be at least {base + 810}")

    print(f"Target: {args.base_url}")
    print(f"Plan:   ~{args.requests:,} reserve requests, {args.concurrency} concurrent, "
          f"{args.hot_seats} hot seats, {args.seats:,}-seat show (seed {args.seed})\n")

    async with Api(args.base_url, args.concurrency) as api, Api(args.base_url, 2) as side:
        # ---- Phase 0: setup -------------------------------------------------------------------
        ready = await api.call("setup", "GET", "/actuator/health/readiness", record=False)
        if ready.status != 200:
            raise SystemExit(f"Service not ready: HTTP {ready.status} {ready.body or ready.error}")
        admin = await api.token("burst-admin", args.admin_secret)
        created = await api.call("setup", "POST", "/shows", token=admin, record=False, json_body={
            "name": f"burst-{uuid.uuid4().hex[:8]}", "seats": seats, "price_paise": 25000})
        if created.status != 201:
            raise SystemExit(f"Could not create show: HTTP {created.status} {created.body}")
        show_id = created.body["id"]
        print(f"Created show {show_id}")

        run_id = uuid.uuid4().hex[:6]
        groups = {"hot": 2000, "multi": 500, "retry": 200, "limit": 50, "spoof": 50, "normal": 1000}
        users = {g: [f"{g}-{run_id}-{i}" for i in range(n)] for g, n in groups.items()}
        everyone = [u for g in users.values() for u in g]
        t0 = time.perf_counter()
        tokens = dict(zip(everyone, await asyncio.gather(*(api.token(u) for u in everyone))))
        print(f"Issued {len(tokens):,} user tokens in {time.perf_counter() - t0:.1f}s")
        metrics_before = await api.metrics()

        # ---- Phase 1: the stampede ------------------------------------------------------------
        calls = []  # (factory, info)

        def add(factory, **info):
            calls.append((factory, info))

        # 1. Hot-seat storm: 2000 users, each tries 4 different hot seats (stays within the limit).
        per_seat = len(users["hot"]) * 4 // len(hot)
        for i, seat in enumerate(hot):
            for j in range(per_seat):
                u = users["hot"][(i * per_seat + j) % len(users["hot"])]
                add(lambda u=u, seat=seat: reserve(api, "hot-seat", show_id, tokens[u], [seat], uuid.uuid4().hex))

        # 2. Overlapping multi-seat requests in random order (all-or-nothing, deadlock bait).
        for u in users["multi"]:
            for _ in range(3):
                k = rng.choice([2, 3])
                start = rng.randrange(0, len(contested) - k)
                pick = contested[start:start + k] + ([rng.choice(hot)] if rng.random() < 0.3 else [])
                pick = list(dict.fromkeys(pick))
                rng.shuffle(pick)
                add(lambda u=u, pick=pick: reserve(api, "multi-seat", show_id, tokens[u], pick, uuid.uuid4().hex))

        # 3. Idempotent retry storm: the same request (same key) 5-10 times at once.
        retry_keys = {}
        for u, seat in zip(users["retry"], retry_block):
            key = f"retry-{uuid.uuid4().hex}"
            retry_keys[u] = (key, seat)
            for _ in range(rng.randint(5, 10)):
                add(lambda u=u, seat=seat, key=key: reserve(api, "retry-storm", show_id, tokens[u], [seat], key),
                    user=u)

        # 4. Per-user limit attack: 10 parallel single-seat reserves per user.
        for i, u in enumerate(users["limit"]):
            for seat in limit_block[i * 10:(i + 1) * 10]:
                add(lambda u=u, seat=seat: reserve(api, "limit-attack", show_id, tokens[u], [seat], uuid.uuid4().hex))

        # 5. Identity spoofing: the body claims to be someone else.
        for u, seat in zip(users["spoof"], spoof_block):
            victim = rng.choice(users["hot"])
            add(lambda u=u, seat=seat, victim=victim: reserve(
                api, "spoof", show_id, tokens[u], [seat], uuid.uuid4().hex, extra={"user_id": victim}), user=u)

        # 6. Garbage: every kind of bad request, each expected to be a specific clean 4xx.
        tok = tokens[users["normal"][0]]
        forged = tok[:-4] + ("AAAA" if not tok.endswith("AAAA") else "BBBB")
        free = normal_block[0]
        reserve_path = f"/shows/{show_id}/reserve"
        garbage = {
            "malformed-json": (400, lambda: api.call("garbage", "POST", reserve_path, token=tok, content='{"seats":',
                               headers={"Idempotency-Key": uuid.uuid4().hex, "Content-Type": "application/json"})),
            "unknown-seat": (400, lambda: reserve(api, "garbage", show_id, tok, ["NOPE"], uuid.uuid4().hex)),
            "duplicate-seats": (400, lambda: reserve(api, "garbage", show_id, tok, [free, free], uuid.uuid4().hex)),
            "empty-seats": (400, lambda: reserve(api, "garbage", show_id, tok, [], uuid.uuid4().hex)),
            "seats-not-a-list": (400, lambda: reserve(api, "garbage", show_id, tok, free, uuid.uuid4().hex)),
            "missing-key": (400, lambda: api.call("garbage", "POST", reserve_path, token=tok, json_body={"seats": [free]})),
            "oversized-key": (400, lambda: reserve(api, "garbage", show_id, tok, [free], "k" * 300)),
            "no-token": (401, lambda: reserve(api, "garbage", show_id, None, [free], uuid.uuid4().hex)),
            "forged-token": (401, lambda: reserve(api, "garbage", show_id, forged, [free], uuid.uuid4().hex)),
            "user-creates-show": (403, lambda: api.call("garbage", "POST", "/shows", token=tok,
                                  json_body={"name": "x", "seats": ["A"], "price_paise": 1})),
            "unknown-show": (404, lambda: reserve(api, "garbage", str(uuid.uuid4()), tok, [free], uuid.uuid4().hex)),
            "wrong-method": (405, lambda: api.call("garbage", "DELETE", reserve_path, token=tok)),
            "wrong-content-type": (415, lambda: api.call("garbage", "POST", reserve_path, token=tok, content="S0001",
                                   headers={"Idempotency-Key": uuid.uuid4().hex, "Content-Type": "text/plain"})),
        }
        for kind, (expected, factory) in garbage.items():
            for _ in range(40):
                add(factory, garbage=kind, expected=expected)

        # 7. Normal traffic fills the rest of the budget.
        while len(calls) < args.requests:
            u = rng.choice(users["normal"])
            pick = rng.sample(normal_block, rng.choice([1, 1, 2, 3]))
            add(lambda u=u, pick=pick: reserve(api, "normal", show_id, tokens[u], pick, uuid.uuid4().hex))
        rng.shuffle(calls)

        # 8. Live invariant poller on its own connection pool, so it samples throughout.
        snapshots = []
        stop = asyncio.Event()

        async def poll():
            while not stop.is_set():
                r = await side.call("poll", "GET", f"/shows/{show_id}", token=admin, record=False)
                if r.status == 200:
                    snapshots.append((r.body["counts"], r.body["total_seats"]))
                await asyncio.sleep(0.02)

        print(f"\nPhase 1: firing {len(calls):,} requests ...")
        poller = asyncio.create_task(poll())
        t1 = time.perf_counter()

        async def run_one(factory, info):
            return await factory(), info

        phase1 = await asyncio.gather(*(run_one(f, i) for f, i in calls))
        stampede_secs = time.perf_counter() - t1
        stop.set()
        await poller
        print(f"Phase 1 done in {stampede_secs:.1f}s ({len(calls) / stampede_secs:,.0f} req/s), "
              f"{len(snapshots)} live snapshots")

        # Key reuse with different seats, then a plain replay (now that the originals exist).
        sample = users["retry"][:50]
        reuse = await asyncio.gather(*(reserve(api, "key-reuse", show_id, tokens[u], [normal_block[-1]],
                                               retry_keys[u][0]) for u in sample))
        replay = await asyncio.gather(*(reserve(api, "replay", show_id, tokens[u], [retry_keys[u][1]],
                                                retry_keys[u][0]) for u in sample))

        # Successful holds, with their server-side lifetimes.
        holds = {}  # reservation_id -> {user, seats, created, expires}
        ttl = None
        for r in api.results:
            if r.status == 201:
                expires = parse_ts(r.body["expires_at"])
                if ttl is None and r.server_time:
                    ttl = timedelta(seconds=round((expires - r.server_time).total_seconds()))
                holds[r.body["reservation_id"]] = {"user": r.body["user_id"], "seats": r.body["seats"],
                                                   "expires": expires}
        ttl = ttl or timedelta(minutes=2)
        for h in holds.values():
            h["created"] = h["expires"] - ttl

        # ---- Phase 2: confirm storm ----------------------------------------------------------
        print("Phase 2: confirm storm ...")
        rids = list(holds)
        rng.shuffle(rids)
        to_confirm = rids[: int(len(rids) * 0.6)]
        confirm_calls = []
        for idx, rid in enumerate(to_confirm):
            for _ in range(2 if idx % 2 == 0 else 1):  # half are double-confirmed concurrently
                confirm_calls.append(api.call("confirm", "POST", f"/reservations/{rid}/confirm",
                                              token=tokens[holds[rid]["user"]]))
        foreign_calls = []
        for rid in rng.sample(rids, min(100, len(rids))):
            intruder = next(u for u in users["normal"] if u != holds[rid]["user"])
            foreign_calls.append(api.call("confirm-foreign", "POST", f"/reservations/{rid}/confirm",
                                          token=tokens[intruder]))
        unknown_calls = [api.call("confirm-unknown", "POST", f"/reservations/{uuid.uuid4()}/confirm", token=tok)
                         for _ in range(50)]
        confirm_results = await asyncio.gather(*confirm_calls)
        foreign_results = await asyncio.gather(*foreign_calls)
        unknown_results = await asyncio.gather(*unknown_calls)
        confirmed = {r.body["reservation_id"] for r in confirm_results if r.status == 200}
        run_secs = time.perf_counter() - t1
        expiry_possible = run_secs >= ttl.total_seconds()

        # ---- Phase 3: reconciliation ----------------------------------------------------------
        await asyncio.sleep(1.2)  # the seats gauge is cached for one second
        metrics_after = await api.metrics()
        final = await api.call("final", "GET", f"/shows/{show_id}", token=admin, record=False)

        # ---------------- checks ----------------
        results = api.results
        n_5xx = sum(1 for r in results if r.status is not None and r.status >= 500)
        n_client = sum(1 for r in results if r.status is None)
        checks.check("Zero 5xx across the whole run", n_5xx == 0, f"{n_5xx} responses were 5xx")
        checks.check("No client-side failures (timeouts / resets)", n_client == 0,
                     f"{n_client} failed: {Counter(r.error for r in results if r.status is None)}")

        def lifetime_end(rid):
            return FOREVER if rid in confirmed else holds[rid]["expires"]

        # One owner per seat at any moment (a seat may change hands only after a hold expired).
        by_seat = defaultdict(list)
        for rid, h in holds.items():
            for s in h["seats"]:
                by_seat[s].append(rid)
        overlaps = []
        for s, rs in by_seat.items():
            rs.sort(key=lambda rid: holds[rid]["created"])
            for a, b in zip(rs, rs[1:]):
                if holds[b]["created"] + CLOCK_SLACK < lifetime_end(a):
                    overlaps.append(s)
        checks.check("No seat ever owned by two reservations at once", not overlaps,
                     f"overlapping ownership on {sorted(set(overlaps))[:5]}")
        winners = {s: len(by_seat.get(s, [])) for s in hot}
        if not expiry_possible:
            checks.check("Each hot seat has exactly one winner", all(n == 1 for n in winners.values()),
                         f"winners per hot seat: {winners}")
        else:
            checks.check("Each hot seat was won (and changed hands only after expiry)",
                         all(n >= 1 for n in winners.values()), f"winners per hot seat: {winners}")

        bad = [r for r, i in phase1 if r.scenario in ("hot-seat", "multi-seat", "limit-attack", "normal")
               and not (r.status == 201 or (r.status == 409 and r.code in ("seat-taken", "per-user-limit")))]
        checks.check("Every stampede reserve is 201 or a clean 409", not bad,
                     f"unexpected: {Counter((r.status, r.code) for r in bad).most_common(3)}")

        # Per-user limit: active seats at every moment a hold was created.
        by_user = defaultdict(list)
        for rid, h in holds.items():
            by_user[h["user"]].append(rid)
        worst = {}
        for u, rs in by_user.items():
            peak = 0
            for rid in rs:
                t = holds[rid]["created"]
                active = sum(len(holds[o]["seats"]) for o in rs
                             if holds[o]["created"] <= t and lifetime_end(o) > t + CLOCK_SLACK)
                peak = max(peak, active)
            worst[u] = peak
        over = {u: n for u, n in worst.items() if n > 4}
        checks.check("No user ever holds more than 4 active seats", not over, f"{list(over.items())[:5]}")
        limit_peak = max((worst.get(u, 0) for u in users["limit"]), default=0)
        checks.check("Limit attack (10 parallel requests per user) stays ≤ 4", limit_peak <= 4,
                     f"max active seats for a limit-attack user: {limit_peak}")

        retry_ok, retry_detail = True, ""
        for u in users["retry"]:
            rs = [r for r, i in phase1 if r.scenario == "retry-storm" and i["user"] == u]
            created_n = sum(1 for r in rs if r.status == 201)
            ids = {r.body["reservation_id"] for r in rs if r.status in (200, 201)}
            stray = [r for r in rs if r.status not in (200, 201) and r.code != "hold-expired"]
            if created_n > 1 or len(ids) > 1 or stray:
                retry_ok, retry_detail = False, f"{u}: {created_n} created, {len(ids)} ids, {len(stray)} other"
                break
        checks.check("Retry storm: one reservation per key; retries replay it (200)", retry_ok, retry_detail)
        checks.check("Same key + different seats → 409 idempotency-mismatch",
                     all(r.status == 409 and r.code == "idempotency-mismatch" for r in reuse),
                     f"{Counter((r.status, r.code) for r in reuse)}")
        checks.check("Replay after the storm → 200 with the original (or hold-expired if it lapsed)",
                     all(r.status == 200 or (r.status == 409 and r.code == "hold-expired") for r in replay),
                     f"{Counter((r.status, r.code) for r in replay)}")
        checks.check("Identity comes from the token, never the body",
                     all(r.body["user_id"] == i["user"] for r, i in phase1 if r.scenario == "spoof" and r.status == 201))

        garbage_bad = Counter((i["garbage"], r.status) for r, i in phase1
                              if r.scenario == "garbage" and r.status != i["expected"])
        checks.check("Every invalid request gets its expected 4xx", not garbage_bad, f"{dict(garbage_bad)}")

        broken = [c for c, total in snapshots if c["available"] + c["held"] + c["confirmed"] != total]
        checks.check(f"Invariant held in all {len(snapshots)} live snapshots during the burst",
                     bool(snapshots) and not broken, f"{len(broken)} snapshots broke the invariant")
        if not expiry_possible:
            avail = [c["available"] for c, _ in snapshots]
            checks.check("Available seats never increased during the burst",
                         all(b <= a for a, b in zip(avail, avail[1:])))
        else:
            checks.skip("Available seats never increased during the burst", "holds expired mid-run")

        bad_confirms = []
        confirm_rids = [rid for idx, rid in enumerate(to_confirm) for _ in range(2 if idx % 2 == 0 else 1)]
        for rid, r in zip(confirm_rids, confirm_results):
            ok = (r.status == 200 and r.body["status"] == "confirmed") or (
                r.status == 409 and r.code == "hold-expired" and r.server_time is not None
                and r.server_time + CLOCK_SLACK >= holds[rid]["expires"])
            if not ok:
                bad_confirms.append((r.status, r.code))
        checks.check("Owner confirms (incl. concurrent doubles) → 200, or hold-expired only if truly expired",
                     not bad_confirms, f"{Counter(bad_confirms)}; all: {Counter(r.status for r in confirm_results)}")
        checks.check("Confirming someone else's hold → 403", all(r.status == 403 for r in foreign_results),
                     f"{Counter(r.status for r in foreign_results)}")
        checks.check("Confirming an unknown reservation → 404", all(r.status == 404 for r in unknown_results))

        fc, total = {}, None
        if final.status == 200:
            fc, total = final.body["counts"], final.body["total_seats"]
            checks.check("Final invariant: available + held + confirmed == total",
                         fc["available"] + fc["held"] + fc["confirmed"] == total, f"{fc} total={total}")
            now = final.server_time
            taken = {s["seat"] for s in final.body["seats"] if s["status"] != "available"}
            conf = {s["seat"] for s in final.body["seats"] if s["status"] == "confirmed"}
            ambiguous = {s for rid, h in holds.items() if rid not in confirmed
                         and abs(h["expires"] - now) <= CLOCK_SLACK for s in h["seats"]}
            expected = {s for rid, h in holds.items() if lifetime_end(rid) > now for s in h["seats"]}
            checks.check("Final state = exactly the seats of active reservations (all-or-nothing)",
                         (taken - ambiguous) == (expected - ambiguous), f"{len(taken)} taken vs {len(expected)} expected")
            checks.check("Final confirmed seats = seats of confirmed reservations",
                         conf == {s for rid in confirmed for s in holds[rid]["seats"]})
        else:
            checks.check("Final show state readable", False, f"HTTP {final.status}")

        observed = Counter()
        for r in results:
            if r.scenario in ("hot-seat", "multi-seat", "retry-storm", "limit-attack", "spoof", "normal",
                              "key-reuse", "replay", "confirm"):
                if r.status == 201:
                    observed["held"] += 1
                elif r.status == 200 and r.scenario != "confirm":
                    observed["idempotent-replay"] += 1
                elif r.status == 409:
                    observed[r.code] += 1
        observed["confirmed"] = len(confirmed)
        if metrics_before is None or metrics_after is None:
            checks.check("Metrics reconcile with observed responses", False, "could not scrape /actuator/prometheus")
        else:
            def delta(name):
                return int(metrics_after.get(name, 0) - metrics_before.get(name, 0))
            got = {"held": delta("reservations_held_total"), "confirmed": delta("reservations_confirmed_total")}
            for reason in ("seat-taken", "per-user-limit", "idempotent-replay", "idempotency-mismatch", "hold-expired"):
                got[reason] = delta(f'reservations_declined_total{{reason="{reason}"}}')
            diff = {k: (v, observed.get(k, 0)) for k, v in got.items() if v != observed.get(k, 0)}
            checks.check("Metrics reconcile with observed responses (deltas over this run)", not diff,
                         f"metric vs observed: {diff} (other traffic during the run would also cause this)")
            gauge = metrics_after.get(f'seats_available{{show_id="{show_id}"}}')
            checks.check("seats_available gauge == API available count", gauge == fc.get("available"),
                         f"gauge={gauge} api={fc.get('available')}")

        # ---- Optional: wait for expiry ---------------------------------------------------------
        if args.expiry_check:
            await expiry_check(api, checks, show_id, admin, tokens, users, holds, confirmed, total)

        report(results, phase1, checks, fc, total, stampede_secs, run_secs, ttl, show_id)
        return 0 if checks.all_passed else 1


async def expiry_check(api, checks, show_id, admin, tokens, users, holds, confirmed, total):
    unconfirmed = [rid for rid in holds if rid not in confirmed]
    if not unconfirmed:
        checks.skip("Expiry", "no unconfirmed holds")
        return
    latest = max(holds[r]["expires"] for r in unconfirmed)
    probe = await api.call("expiry", "GET", f"/shows/{show_id}", token=admin, record=False)
    wait = (latest - probe.server_time).total_seconds() + 2
    print(f"\nExpiry check: waiting {max(wait, 0):.0f}s for unconfirmed holds to expire ...")
    await asyncio.sleep(max(wait, 0))
    after = await api.call("expiry", "GET", f"/shows/{show_id}", token=admin, record=False)
    c = after.body["counts"]
    conf_seats = sum(len(holds[r]["seats"]) for r in confirmed)
    checks.check("After expiry: no held seats remain", c["held"] == 0, f"{c}")
    checks.check("After expiry: available == total − confirmed", c["available"] == total - conf_seats, f"{c}")
    seat = holds[unconfirmed[0]]["seats"][0]
    rebook = await reserve(api, "expiry", show_id, tokens[users["normal"][-1]], [seat], uuid.uuid4().hex)
    checks.check("Expired seat is re-bookable", rebook.status == 201, f"HTTP {rebook.status} {rebook.code}")
    if confirmed:
        cseat = holds[next(iter(confirmed))]["seats"][0]
        taken = await reserve(api, "expiry", show_id, tokens[users["normal"][-2]], [cseat], uuid.uuid4().hex)
        checks.check("Confirmed seat stays taken after the TTL",
                     taken.status == 409 and taken.code == "seat-taken", f"HTTP {taken.status} {taken.code}")


def report(results, phase1, checks, final_counts, total, stampede_secs, run_secs, ttl, show_id):
    line = "=" * 74
    print(f"\n{line}\nOUTCOME DISTRIBUTION (all recorded requests)\n{line}")
    dist = Counter()
    for r in results:
        if r.status is None:
            dist[f"client error ({r.error})"] += 1
        elif r.status >= 500:
            dist[f"{r.status} server error"] += 1
        elif r.status == 201:
            dist["201 held"] += 1
        elif r.status == 200:
            dist["200 replay / confirm"] += 1
        else:
            dist[f"{r.status} {r.code or ''}".strip()] += 1
    for k, v in sorted(dist.items(), key=lambda kv: -kv[1]):
        print(f"  {k:<38}{v:>10,}")
    print(f"  {'TOTAL':<38}{len(results):>10,}")
    print(f"  5xx: {sum(1 for r in results if r.status and r.status >= 500)}   "
          f"client errors: {sum(1 for r in results if r.status is None)}")

    print("\nBy scenario:")
    by_scenario = defaultdict(Counter)
    for r in results:
        label = "client-error" if r.status is None else f"{r.status}{' ' + r.code if r.code and r.status >= 400 else ''}"
        by_scenario[r.scenario][label] += 1
    for scenario, c in by_scenario.items():
        print(f"  {scenario:<16}" + ", ".join(f"{k}: {v:,}" for k, v in c.most_common()))

    lat = sorted(r.latency_ms for r, _ in phase1)
    if lat:
        def p(q):
            return lat[min(len(lat) - 1, int(q * len(lat)))]
        print(f"\nStampede: {len(lat):,} requests in {stampede_secs:.1f}s ({len(lat) / stampede_secs:,.0f} req/s); "
              f"latency p50 {p(0.5):.0f} ms, p95 {p(0.95):.0f} ms, p99 {p(0.99):.0f} ms, max {lat[-1]:.0f} ms")
    print(f"Hold TTL {ttl.total_seconds():.0f}s; stampede + confirms took {run_secs:.1f}s"
          + ("  (holds expired mid-run; checks are time-aware)" if run_secs >= ttl.total_seconds() else ""))

    print(f"\n{line}\nFINAL RECONCILIATION (show {show_id})\n{line}")
    if final_counts:
        s = final_counts["available"] + final_counts["held"] + final_counts["confirmed"]
        print(f"  available {final_counts['available']:,} + held {final_counts['held']:,} + confirmed "
              f"{final_counts['confirmed']:,} = {s:,}   total_seats {total:,}   {'OK' if s == total else 'MISMATCH'}")

    print(f"\n{line}\nCHECKS\n{line}")
    for name, state, detail in checks.items:
        print(f"  [{state}] {name}" + (f"\n         {detail}" if state != "PASS" and detail else ""))
    passed = sum(1 for _, s, _ in checks.items if s == "PASS")
    failed = sum(1 for _, s, _ in checks.items if s == "FAIL")
    print(f"\n{'ALL CHECKS PASSED' if failed == 0 else 'SOME CHECKS FAILED'}: "
          f"{passed} passed, {failed} failed, {len(checks.items) - passed - failed} skipped")


def raise_fd_limit(needed: int):
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    if soft < needed:
        try:
            resource.setrlimit(resource.RLIMIT_NOFILE, (min(needed, hard), hard))
        except (ValueError, OSError):
            print(f"Warning: open-file limit is {soft}; run `ulimit -n {needed}` for best results.")


def main():
    parser = argparse.ArgumentParser(description="On-sale stampede against booking-service.")
    parser.add_argument("base_url", help="e.g. http://localhost:8080")
    parser.add_argument("--requests", type=int, default=20_000, help="reserve requests in the stampede (default 20000)")
    parser.add_argument("--concurrency", type=int, default=500, help="max requests in flight (default 500)")
    parser.add_argument("--hot-seats", type=int, default=10, help="seats everyone fights over (default 10)")
    parser.add_argument("--seats", type=int, default=1000, help="show size (default 1000)")
    parser.add_argument("--seed", type=int, default=42, help="random seed for a reproducible mix")
    parser.add_argument("--admin-secret", default=os.environ.get("ADMIN_SECRET"),
                        help="defaults to the ADMIN_SECRET environment variable")
    parser.add_argument("--expiry-check", action="store_true",
                        help="also wait for unconfirmed holds to expire and verify release (adds ~HOLD_TTL)")
    args = parser.parse_args()
    if not args.admin_secret:
        sys.exit("ADMIN_SECRET is not set (export it or pass --admin-secret).")
    raise_fd_limit(args.concurrency * 2 + 256)
    sys.exit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
