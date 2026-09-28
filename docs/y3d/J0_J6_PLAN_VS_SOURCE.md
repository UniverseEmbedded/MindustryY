# J0–J6 Plan vs Source Stage (documentation checkpoint)

**Status**: documentation-only (J6)  
**Date**: 2026-09-20  
**Evidence tiers**: 已落地 (concrete `file:line` this turn or cited prior ledger) / 部分 (named methods exist, product chain incomplete) / 未见 (named path/symbol searched, absent) / 规划 (batch intent only) / 未执行 (no compile/test)  
**Design doc**: `docs/y3d/SECTOR_PARTITION_TRADITIONAL_WAFER.md`

Batch IDs follow the in-session J0–J6 plan (not `mc-y3d-system-mapping.json`).  
Rule: **only rows that can point at a file path (and line where stable) may say 已落地.** Parallel J1–J5 may still be writing; absence is point-in-time. This checkpoint was **refreshed after J1 sources appeared** under `y/campaign/partition/`.

---

## 1. Batch legend (plan)

| ID | Name (plan) | Primary write areas (plan) |
|---|---|---|
| **J0** | 断点清单收口 (read-only audit) | none (docs only if parent later freezes a checklist) |
| **J1** | 分区策略 TRADITIONAL\|WAFER + MapPlanProvider (traditional equivalence) | `core/src/mindustry/y/campaign/partition/**`, minimal `World.loadSector*` hook, tests source |
| **J2** | Y3D 断点焊接: portal env inject, ContainerCommand handler, light→vertexLight host | `y/y3d/{gameplay,net,runtime,light,render}/**` + tests |
| **J3** | Wafer MapRegion array plan (center / same orientation / clip) | depends on J1 interface; `WaferMapPlanProvider`, `MapRegionId` |
| **J4** | Cross-MapRegion move/logistics (WAFER-only) | depends on J3; adjacency + transfer; traditional untouched |
| **J5** | Tests + journeys (traditional contract first; wafer after J3/J4) | `tests/**`, `ui-tests/**` sources only |
| **J6** | Docs + requirements ledger (this batch) | `docs/y3d/**`; R196/R197 citation only |

Recommended order (plan): J1 + J2 + J5(traditional) + J6 → J3 → J4 + J5(wafer).

---

## 2. Plan vs source table (this checkpoint)

| ID | Plan deliverable | Source stage (this turn) | Evidence / pointer |
|---|---|---|---|
| **J0** | Read-only breakpoint checklist (portal / container handler / light host / one-sector-one-map / J symbols / tests) | **规划/只读** — parent may hold full checklist; this docs pass did not re-author a full J0 matrix | Session task pattern; spot-checks below overlap J0 interests |
| **J1** | `SectorPartitionMode` + `MapPlanProvider` + traditional provider ≡ `loadSector` | **已落地** (product + test **sources**; execution **未执行**) | `core/src/mindustry/y/campaign/partition/{SectorPartitionMode,MapPlanProvider,TraditionalMapPlanProvider,MapPlan}.java`; hook `World.java:312-313`; field `Planet.java:190`; tests `tests/.../partition/TraditionalPartitionEquivalenceTests.java` |
| **J1 baseline** | Vanilla path still one map per sector under TRADITIONAL | **已落地** | `core/src/mindustry/core/World.java:306-324` plan → single `loadGenerator` |
| **J2** | Portal environment injection at product tick | **部分 / 缺注入** | `GameplayRegionRuntime.setPortalEnvironment` exists (~`:44` per prior J0-style reads); product call site **未见** this docs pass (no full-tree scan) |
| **J2** | `ContainerCommand` network handler | **部分 / 缺 handler** | Packet type historically registered; handler attach **未见** in this docs-only pass (prior ledger: wire type ≠ receive path) |
| **J2** | Light host → `Y3DSceneRenderer.vertexLight` | **部分** | Session light bridge **已见** names under `y/y3d/light/` + session ensure path cited in `FEATURE_LEDGER_H_I.md`; product caller of renderer setter **未见** this turn |
| **J3** | Wafer multi-cell plan | **规划** — blocked on J1 types | No `WaferMapPlanProvider` / `MapRegionId` **未见** |
| **J4** | Cross-map unit/logistics | **规划** — blocked on J3 | **未见** MapRegion adjacency APIs |
| **J5** | Traditional equivalence tests, wafer geometry tests, journeys | **部分** | Traditional equivalence **source with J1**: `TraditionalPartitionEquivalenceTests.java` (**未执行**); H5/I6 Y3D unit/journey **sources** under `tests/.../y3d/*`, `ui-tests/.../Y3D*`; `WaferMapPlanTests` / `MapRegionIdTests` **未见** |
| **J5** | Execution | **未执行** | No gradle/JUnit this turn |
| **J6** | Dual-mode design doc + R196/R197 citation + status | **已落地** (docs) | `docs/y3d/SECTOR_PARTITION_TRADITIONAL_WAFER.md` (this line’s companion) |
| **J6** | CSV completion columns for R196/R197 | **有意未写** | `HISTORICAL_REQUIREMENTS_RAW.csv` unchanged |
| **J6** | `mc-y3d-system-mapping.json` promotion | **有意未写** | No code-backed mapping edit |

---

## 3. H/I vs J (do not mix)

Prior batches (already in `FEATURE_LEDGER_H_I.md` / `CURRENT_STATUS.md`): H1–H6, I1–I6 — product wiring for ClientLoop, combat, worldgen/light skeleton, signal/volume, tests/journeys, docs.

J batch is **separate**: campaign dual partition + residual Y3D breakpoints + wafer geometry.  
**Do not** mark J1 implemented because `y/y3d/lifecycle/regionizer/RegionPartition*` exists; J1 evidence is `y/campaign/partition/**` + `World.java:312`. Do not mark **WAFER load** implemented because `SectorPartitionMode.WAFER` exists.

---

## 4. Point-in-time spot-checks (J6 turn, refreshed after J1 landing)

| Check | Result |
|---|---|
| `World.loadSector` | **已见** `World.java:289`, `:293`, `:306` |
| J1 hook in `loadSectorInternal` | **已落地** `World.java:312-313` |
| `core/src/.../y/campaign/partition` | **已落地** (4 files) |
| `SectorPartitionMode` / `MapPlanProvider` | **已落地**; WAFER `plan()` throws; `WaferMapPlanProvider` **未见** |
| `Planet.sectorPartitionMode` | **已落地** `Planet.java:190` |
| Traditional partition tests | **已落地** source only; **未执行** |
| Adjacent `y/**/campaign/**` | **已见** tantros + oxygen (+ partition package) |
| Y3D `RegionPartition*` | **已见** under `y/y3d/lifecycle/regionizer/` |
| Compile/test/gradle/git/network | **未执行** |

---

## 5. Update protocol (for later J1–J5 completions)

When a J root finishes writing product/tests:

1. Add/replace **one row** with concrete `file:line`.  
2. Keep “缺 handler / 缺注入 / 未见 / 未执行” until the product chain is cited end-to-end.  
3. Never flip R196/R197 CSV or mapping JSON status from a docs-only turn.  
4. Cross-link any new geometry notes into `SECTOR_PARTITION_TRADITIONAL_WAFER.md` §4/§6.

---

## 6. Self-check

- [x] J0–J6 present with plan vs stage  
- [x] 已落地 only where a file pointer exists (J6 docs; vanilla/J1 `loadSector` + partition package)  
- [x] J1 traditional skeleton **已落地** with file:line; WAFER multi-map still **未见** / fail-closed  
- [x] H/I not re-labeled as J completion  
- [x] CSV / mapping generator untouched  
- [x] 未执行编译/测试 stated  

---

## 7. Verification boundary

未执行编译/测试；是否验证请你点名。
