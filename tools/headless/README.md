---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_9537ab11c1a111f1a05452540064ee0f
    ReservedCode1: Vdj6FzedaSDizmpHUAcyOGuNplLqK0Pfu3eON7VATvKZZ6B91KXbPes4TSFyRsn4JrWy90BEa2VZYjIdr6uLRzUy7V3bIbnmztbUPG7qYo7kapM1qp1hs4FWjvCymnLZb5/vilzuYR804IobmvtIiKOw2lYw5RDrcbasycdCoqprEsh5EYdW0Ot8QI0=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_9537ab11c1a111f1a05452540064ee0f
    ReservedCode2: Vdj6FzedaSDizmpHUAcyOGuNplLqK0Pfu3eON7VATvKZZ6B91KXbPes4TSFyRsn4JrWy90BEa2VZYjIdr6uLRzUy7V3bIbnmztbUPG7qYo7kapM1qp1hs4FWjvCymnLZb5/vilzuYR804IobmvtIiKOw2lYw5RDrcbasycdCoqprEsh5EYdW0Ot8QI0=
---

# Headless checks (`tools/headless`)

Runs the navigation core of the mod **without Minecraft**: no game launch, no Fabric loader, no gradle daemon.
Every suite compiles the real `client/road/{model,nav,spatial}` sources against two stand-ins and then exercises the
production entry points directly.

```
./run.sh [repo-root]      # repo-root defaults to the checkout this directory lives in
```

Exit code: `0` all checks passed, `1` at least one check failed, `2` repository not found, `3` the working tree does
not compile (the checker never reports "check failed" for a broken build - in that case it prints the javac tail and
stops).
Compiled classes land in `out/` (ignored by git); `run.sh` recompiles on every invocation, so it is always run against
the current working tree.

## Layout

| Path | Role |
| --- | --- |
| `run.sh` | compile + run, the only entry point |
| `src/stub/.../WayfarerConfig.java` | headless stand-in for the malilib-backed config (nav getters + distance gate) |
| `src/stub/.../data/RoadNetworkDatabase.java` | in-memory stand-in for the real database, same read surface the router uses |
| `src/checks/Master.java` | suite registry and the pass/fail exit code |
| `src/checks/*.java` | one suite per file, each exposing `static void run(Checks)` |

## Suites (batch)

| Suite | What it pins down |
| --- | --- |
| `spatial-index-vs-brute-force` | k-nearest on `NodeSpatialIndex` against a full scan: ring pruning, `closest, then smallest id` ties, radius/cell boundaries, negatives, in-place mutations |
| `graph-search-vs-dijkstra` | `GraphSearch` (multi-source/target A*) against an independent Dijkstra on random digraphs, integer lattices, one-way streets, islands; plus path-is-a-walk and determinism |
| `router-vs-dijkstra` | the real `Router` against a re-implemented pre-refactor graph build, across snap-distance gates, with degenerate and candidate endpoint sets |
| `navigation-session-wiring` | single candidate reproduces the legacy single-point route; the trap-node case only succeeds because the whole candidate set is handed to the router; empty candidate sets keep `START_NOT_NEAR_ROAD` / `DESTINATION_NOT_NEAR_ROAD` |
| `snapshot-contract` | what `HUD`/HTTP consumers may rely on: snapshot agrees with its route, remaining distance is finite and non-negative, full distance at start, cleared by `stop()` |
| `source-structure` | the pure packages stay loadable without Minecraft, and the public API surface survives refactors |

`KnnDiffTest` also keeps a standalone `main` for ad-hoc runs; the batch calls its `run(Checks)` bridge.

## Adding a suite

1. Add `src/checks/MyChecks.java` with `public static void run(Checks c)`, reporting through `c.eq/…/closeTo`.
2. Register it in the `List<Suite>` in `Master.java`.
3. `./run.sh` - a red suite is a real regression, not a build problem.

Prefer differential checks (compare against an independent implementation on the same fixture) over golden numbers,
and keep fixtures reconstructable from the source under test.

## Known coverage gaps

* The HTTP layer (`client/road/server/...`) and its JSON payloads need gson + the JDK http server; they are not part of
  this batch.
* `client/road/xaero/**` and the screens depend on Xaero's jar and Minecraft classes.
* `record/**` and anything else touching `net.minecraft` is out of scope by design - the stubs exist precisely so the
  core can be tested without them.
*（内容由AI生成，仅供参考）*
