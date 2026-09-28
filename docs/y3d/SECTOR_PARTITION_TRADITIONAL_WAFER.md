# Sector Partition: TRADITIONAL | WAFER（战役几何双模式）

**Status**: documentation-only (J6)  
**Date**: 2026-09-20  
**Evidence tiers**: 已证实 (file:line) / 已见 (path list) / 未见 (named path searched, absent) / 设计 (plan only, no product code this turn) / 未执行 (no compile/test/script this turn)  
**Companion**: `docs/y3d/CURRENT_STATUS.md`, `docs/y3d/FEATURE_LEDGER_H_I.md`, `docs/y3d/J0_J6_PLAN_VS_SOURCE.md`  
**Requirements**: **R196**, **R197** (cited below; CSV completion state **not** written)

This file is a design/status note only. It does **not** modify `HISTORICAL_REQUIREMENTS_RAW.csv`, does **not** edit `mc-y3d-system-mapping.json`. J1 skeleton sources were **observed landing in parallel** and are cited with `file:line`; WAFER multi-map loading is still not implemented (enum present ≠ multi-die load).

---

## 0. Scope and non-goals

| In scope | Out of scope (this turn) |
|---|---|
| Dual-mode partition design (TRADITIONAL default / WAFER staged) | Product code under `core/src`, `tests`, `ui-tests` |
| Canonical baselines: R196 / R197 + Y端19 wafer quote | Changing CSV completion semantics |
| Contrast vs Y3D authority `Region` | Full-map logistics, journey tests (J4/J5) |
| File-backed spot-checks for J1 landing points | Compile / test / gradle / git / network |

**Default product behavior remains TRADITIONAL.** WAFER is opt-in. Configured behavior (J1 sources): null/blank `Planet.sectorPartitionMode` → TRADITIONAL (`SectorPartitionMode.java:26-41`); unknown non-empty name → `IllegalArgumentException` (fail-closed, `:33-34`); mode `WAFER` before J3 → `UnsupportedOperationException` on `plan()` (`MapPlanProvider.java:30-39`), never silent traditional fallback.

---

## 1. Requirements anchors (read-only citation)

Source: `docs/mindustryy/requirements/HISTORICAL_REQUIREMENTS_RAW.csv` (not edited).

| ID | Title (abbrev.) | CSV body focus | CSV line (this tree) |
|---|---|---|---|
| **R196** | Y端19：… Sector 内进一步细分的方形地图区域 | Polygon Sector 与地图关系；地图加载/卸载；无缝单位跨图；跨图/跨 Sector 物流；边界拼接 | `HISTORICAL_REQUIREMENTS_RAW.csv:197` |
| **R197** | Y端19：… 否定“立方体展开图” | 多面体/球状逻辑；Sector 平面地图只是投影；同 Sector 多图不相互旋转；特殊边界格处理曲率；仅高空建筑明显畸变；重做设计 | `HISTORICAL_REQUIREMENTS_RAW.csv:198` |

**Ledger rule (J6):** this document only **references** R196/R197. It does **not** append IMPLEMENTED/PARTIAL columns to the CSV, does **not** rewrite requirement text, and does **not** treat a future enum/package name as CSV completion.

Related CSV rows (context only, not claimed): R194 Sector 连接; R195 无火箭跨 Sector 运输.

---

## 2. Product baseline (spot-check, this turn)

### 2.1 `World.loadSector` — plan hook present; traditional still one map (**已证实**)

```text
core/src/mindustry/core/World.java
  :289  public void loadSector(Sector sector)
  :293  public void loadSector(Sector sector, WorldParams params)
  :306  private void loadSectorInternal(Sector sector, WorldParams params)
  :312  MapPlan plan = MapPlanProvider.forSector(sector).plan(sector);
  :313  loadGenerator(plan.width, plan.height, tiles -> …)
```

- J1 insertion: plan is resolved before `loadGenerator`; TRADITIONAL plan is `width==height==sector.getSize()` (single square).
- WAFER multi-plan loop is **not** implemented: provider returns a fail-closed stub that throws `UnsupportedOperationException` (see `MapPlanProvider.forSector` case `WAFER`).
- Generator callback still preset / `sector.planet.generator` as before (`:314-321`).

### 2.2 Campaign partition package (**已落地** for J1 skeleton; WAFER provider **未见**)

| Named path / symbol | Result | file:line (this recheck) |
|---|---|---|
| `core/src/mindustry/y/campaign/partition/**` | **已落地** (4 types) | see rows below |
| `SectorPartitionMode` | **已落地** — `TRADITIONAL`/`WAFER`; null/blank → traditional; unknown non-empty → `IllegalArgumentException` | `partition/SectorPartitionMode.java:15-41` |
| `MapPlanProvider` + `forSector` | **已落地** — mode switch; WAFER stub throws (no silent fallback) | `partition/MapPlanProvider.java:14-45` |
| `TraditionalMapPlanProvider` | **已落地** — `MapPlan.square(sector.getSize())` | `partition/TraditionalMapPlanProvider.java:13-20` |
| `MapPlan` | **已落地** — rectangular dimensions only | `partition/MapPlan.java:8-27` |
| `Planet.sectorPartitionMode` | **已落地** — `@Nullable String` config field | `type/Planet.java:190` |
| `WaferMapPlanProvider` | **未见** (J3) | Glob `**/WaferMapPlanProvider.java` → 0 |
| `MapRegionId*` | **未见** (J3) | Glob → 0 |
| Traditional equivalence tests (source) | **已落地** source only — **未执行** | `tests/.../partition/TraditionalPartitionEquivalenceTests.java:15-119` |

Adjacent (not partition): `y/tantros/campaign/*`, `y/oxygen/islands/campaign/*` — **已见**.

**Class presence ≠ IMPLEMENTED for WAFER loading:** enum value `WAFER` exists but `plan()` on the WAFER branch throws; no multi-die generation path.

### 2.3 Y3D Region inventory (**已见** — different concept)

`core/src/mindustry/y/y3d/lifecycle/regionizer/RegionPartition.java`, `RegionPartitionImage.java`, `RegionDataPartitioner.java`, `Regionizer3D.java`, plus many `Region*` authority types under `y/y3d/**`.  
These are **authority/lifecycle** partitions, **not** campaign MapRegion plans (see §5).

---

## 3. Dual-mode design

### 3.1 Modes

| Mode | Meaning | Default? | Geometry | Load path intent |
|---|---|---|---|---|
| **TRADITIONAL** | Vanilla-equivalent: one Sector ↔ one square map | **Yes (default)** | `sector.getSize() × size` via existing `loadGenerator` | Behaviorally equal to `loadSectorInternal` today (single plan) |
| **WAFER** | Sector subdivided into square MapRegion cells on a local plane | **No (opt-in, staged)** | Center-aligned grid + edge-clip cells; same Sector share orientation | Multi plan under one Sector; still **not** a continuous cubemap unwrap |

**Fail-closed:** unknown enum / missing provider / unparsable config → TRADITIONAL (or explicit reject), never silent WAFER.

### 3.2 Logical hierarchy

```text
TRADITIONAL (default)
  Planet → Polygon Sector → [ one square map ] → loadGenerator(size,size)

WAFER (staged)
  Planet → Polygon Sector (polyhedron face / local plane)
                → MapRegion grid (square cells, wafer-like)
                     [ center cell aligned to Sector center
                     | full interior squares
                     | edge cells clipped / irregular valid domain
                     | no inter-cell rotation within same Sector ]
                → per-cell load plan (reuse generator APIs carefully)
```

Projection rules (R197): Sector plane is a **projection** onto a sphere/polyhedron for high-level render; **same Sector cells do not rotate relative to each other**; curvature is handled at **boundary cells / high-altitude visual distortion**, not by rotating the tile grid.

### 3.3 Staging (WAFER phases — design, not shipped)

| Stage | Content | Depends on | J root (plan) |
|---|---|---|---|
| W0 | Mode enum + provider interface + TraditionalMapPlanProvider (equivalence) | — | **J1 已落地** (see §2.2) |
| W1 | `WaferMapPlanProvider`: center, same orientation, clip, MapRegionId | W0 | **J3** |
| W2 | Cross-map unit move / logistics / adjacency (WAFER-only registration) | W1 | **J4** |
| W3 | Contract tests, journeys, optional mode control UI | W0–W2 as available | **J5** |
| W Docs | This file + J0–J6 status + R196/R197 citation | parallel | **J6** |

TRADITIONAL must remain valid without W1–W2. WAFER must not become the implicit default for existing saves.

### 3.4 Y端19 source quotes (read-only)

Path used this turn: `D:\pama1234\pfp\p-2026-07\repo-for-ai\文档\云端聊天记录\Y端19.md` (repo-adjacent document tree; optional pixel-factory path under `p-2025-03` not required for citation).

| Topic | Quote (verbatim excerpt) | Location |
|---|---|---|
| Wafer analogy (R196 seed) | 「每个六边形区块里头应该再跟往硅晶圆上排列芯片格子似的放下多个正方形区域作为实际的地图模拟单位」 | `Y端19.md:1510` (and repeated `:2680`) |
| R197 redesign constraints | 「同一区块的相邻地图不该出现旋转」「把星球近似成多面体，每个区块按平面来构造，从逻辑上是投影到球形上的」 | `Y端19.md:2680` |
| Edge cells not full squares | 「边界的格子本来就不应该是完整正方形地图」 | `Y端19.md:2680` |
| Non-seam world relations (pre-wafer framing) | 「地图不无缝拼接，世界关系连续；局部地图彼此独立……」 | `Y端19.md:480` |
| Vanilla structure note | 球面六边形 Sector → 局部切平面 → 正方形 Tiles → 边缘涂黑 | `Y端19.md:2754-2763` |

Assistant-side “PlanetRegionAtlas / Region (100,200)” sketches at `:3143-3169` are **chat design discussion**, not product source and not marked IMPLEMENTED here.

---

## 4. Interface sketch (J1 names landed; multi-cell still design)

```text
SectorPartitionMode = TRADITIONAL | WAFER          // default TRADITIONAL  [已落地]
MapPlan             = width × height               // single rect          [已落地]
MapPlanProvider     = forSector(sector) → plan()   // mode switch          [已落地]
TraditionalMapPlanProvider  → square(sector.getSize())                   [已落地]
WaferMapPlanProvider        → multi cell (J3)                            [未见]
World.loadSectorInternal    → forSector.plan → loadGenerator(w,h)        [已落地 :312-313]
```

**J1 landing (file-backed):** under `core/src/mindustry/y/campaign/partition/` with `World.loadSectorInternal` calling `MapPlanProvider.forSector` at `World.java:312`. Still **设计/未见**: multi-cell wafer geometry, `MapRegionId`, cross-map load loop (J3/J4).

---

## 5. Difference from Y3D Region (authority) — must not conflate

| Axis | Campaign **MapRegion** (WAFER / R196–R197) | Y3D **Region** (existing runtime) |
|---|---|---|
| Concern | Planet–Sector–map **topology**, load/unload, logistics, projection | Authority single-writer, replication, checkpoint, distributed leases |
| Primary tree | Intended: `mindustry/y/campaign/partition/**` + `World.loadSector*` | `mindustry/y/y3d/lifecycle|authority|distributed|replication/**` |
| Identity | `MapRegionId` (sector + local cell) — design | `RegionId` / `RegionPartition` — **已见** e.g. `regionizer/RegionPartition.java` |
| Geometry source | Planet grid, sector.rect / local plane, wafer grid | Adaptive regionizer / storage cubes / space graph |
| Default relation to vanilla campaign | TRADITIONAL = current `loadSector` | Independent of planet hex sector load unless explicitly bridged |
| Anti-pattern | Renaming `Regionizer3D` output as “MapRegion” without campaign semantics | Using campaign partition to fake authority isolation |

Y3D may later **consume** multi-map loads (session per map), but campaign partition is **not** a rename of `Regionizer3D`. Spot-check this turn: partition image classes exist under `y/y3d/lifecycle/regionizer/` only — still not campaign MapRegion.

---

## 6. R196 / R197 mapping table (design coverage, not CSV status)

| Requirement clause (abbrev.) | Design home | Source status this turn |
|---|---|---|
| R196 Polygon Sector ↔ map relationship | §3.2 hierarchy | 设计 (本文) |
| R196 map load/unload | W0/W1 plan → `loadSectorInternal` loop (J1/J3) | W0 traditional plan **已落地** (`World.java:312`); multi-map loop **未见** |
| R196 seamless unit cross-map | W2 (J4) | 未见 product code |
| R196 cross-map / cross-sector logistics | W2 + existing campaign logistics (separate) | 未见 for MapRegion |
| R196 edge stitching | Edge clip + adjacency (J3/J4) | 未见 |
| R197 no cubemap unwrap; polyhedron/sphere logic | §3.1–3.2 | 设计 (R197 is a negative/redesign requirement) |
| R197 plane is projection only | §3.2 projection note | 设计 |
| R197 same Sector no mutual rotation | W1 invariant + tests (J5) | 未见 test source this turn |
| R197 boundary curvature / high-altitude only visual distortion | render projection (later) | 未见 |

**CSV completion:** unchanged / not written (**未执行** CSV status columns).

---

## 7. What “已落地” may claim (gate)

Allowed only with all of:

1. Concrete `file:line` under `core/src` (or designated tests) for the mode/provider/load path;  
2. Explicit default = TRADITIONAL and fail-closed unknown mode;  
3. Traditional equivalence argument (same size/generator call shape as `loadSectorInternal`);  
4. Not merely a class name, mapping JSON row, or this document.

**This turn (J6):** wrote this design/status doc + `J0_J6_PLAN_VS_SOURCE.md`; rechecked J1 sources after parallel landing (file-backed in §2.2). Did **not** author product/tests. CSV/mapping untouched.

---

## 8. Self-check (J6 doc)

- [x] Design includes dual mode, default traditional, WAFER stages, Y3D Region contrast  
- [x] R196/R197 ids referenced; CSV body not rewritten  
- [x] `World.loadSector` cited with file:line  
- [x] `y/campaign/partition` / `SectorPartitionMode` / `MapPlanProvider` cited with **file:line** (J1 已落地); WAFER provider **未见**  
- [x] Y端19 wafer sentence quoted read-only with path:line  
- [x] No WAFER multi-map IMPLEMENTED claim from enum/type presence  
- [x] No compile/test/gradle/git/network this turn  

---

## 9. Verification boundary

未执行编译/测试；是否验证请你点名。  
Runnable later (not executed): `./gradlew :core:compileJava`; `./gradlew :tests:test --tests "mindustry.y.campaign.partition.TraditionalPartitionEquivalenceTests"` (and later `WaferMapPlanTests` if J3 adds it).
