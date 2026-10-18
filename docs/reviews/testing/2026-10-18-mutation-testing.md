# Mutation testing spike — 2026-10-18

Issue #63. Mutation testing makes many small deliberate bugs (a flipped
comparison, an off-by-one, a dropped call) and checks whether a test fails
for each. It measures whether the `sharedLogic` tests would catch real
mistakes in `waveshare/` and `image/`.

## Does PIT run against `sharedLogic`?

Yes, but not through its Gradle plugin.

- **gradle-pitest-plugin 1.19.0** applies without error but registers no
  `pitest` task. It hooks the `java` plugin, and the KMP Android library
  plugin doesn't apply that plugin.
- **PIT's command-line tool (1.30.1)** works. An opt-in `JavaExec` task in
  `sharedLogic/build.gradle.kts` runs it against the compiled
  `androidHostTest` classes:

  ```sh
  ./gradlew :sharedLogic:pitest
  # report: sharedLogic/build/reports/pitest/index.html (and mutations.xml)
  ```

  It depends on `testAndroidHostTest`, takes about 2.5 minutes, and nothing
  else depends on it, so default builds and CI don't run it.

AGP puts the production classes on the host-test classpath as a jar, and PIT
mutates only classes in directories. Without help, PIT mutated only the test
classes and still printed a plausible score. The task passes the Kotlin
compilation's class directory as `--mutableCodePaths` to fix this. If the
score suddenly covers `*Test` files, check that first.

Mutated: `io.github.cacack.keginker.image.*` and `.waveshare.*`. Tests run:
every `sharedLogic` host test, since `nfc/` and `replay/` tests also cover
`waveshare/`.

## Scores

PIT 1.30.1 with its default mutators, run on this change's commit. The
counts are a snapshot, not a baseline: they shift whenever the code or the
tests change. "Detected" means killed, or timed out because the mutant made a
test hang.

| Package | Mutants | Detected before | Detected after |
|---|---|---|---|
| `image/` | 226 | 192 (85%) | 220 (97%) |
| `waveshare/` | 356 | 233 (65%) | 242 (68%) |
| `waveshare/`, without compiler noise | 249 | 233 (94%) | 242 (97%) |

107 of the `waveshare/` mutants change code that the Kotlin compiler
generates for coroutines, not code anyone wrote. Examples are a removed
`ResultKt.throwOnFailure` call, a suspend function's `Unit` return replaced
with `null`, and removed `Intrinsics.checkNotNullExpressionValue` calls. They
can't be killed and need no fix. The commercial Arcmutate Kotlin plugin
filters them; free PIT doesn't.

## Survivors that matter

Fixed by tightening tests in this change:

| Mutant | Fix |
|---|---|
| Progress arithmetic in both writers (`(chunk + 1) * DATA_PROGRESS / chunks`: `+`→`-`, `*`→`/`) still ended at 100 in order | Both writers' progress tests check that values stay in 0–100 and that `IMAGE_SENT_PROGRESS` is reported |
| `GrayImage` accepted a 0-wide or 0-tall image (`> 0` → `>= 0`) | `GrayImageTest.rejectsAnEmptyImage` |
| `GrayImage.get` bounds checked only on the right edge | `rejectsOutOfRangeCoordinates` checks all four edges |
| `fill` accepted a 0 target (`> 0` → `>= 0`) | `ResizeTest.rejectsAnEmptyTarget` |
| `busyPollLimit` failed when exactly one poll fit (`>= 1` → `> 1`) | `WriteBudgetTest.aBudgetWithRoomForOnePollAllowsOne` |
| `isWaveshareIsoDepTag` rejected historical bytes that are exactly `YZRCOS` (`>=` → `>`) | New `WaveshareTagTest` |
| `writeProgressMessage` had no `sharedLogic` test | `WriteMessagesTest.progressSaysTheDisplayIsUpdatingOnceTheImageIsSent` |
| `testPattern` geometry (border, marker, ramp rows, checker cells, minimum size): 24 survivors | `TestPatternTest` pins each region's edges |

Left alone, each judged harmless:

| Mutant | Why it's harmless |
|---|---|
| `GrayImage.fromArgb` size checks (4 mutants) | The constructor repeats the same checks and throws the same exception, so these mutants can't change behaviour |
| `GrayImage` constructor size message (no coverage) | It changes only the number in an error message |
| `WaveshareIsoDepWriter` chunk count, `size + CHUNK_SIZE - 1` → `+ 1` | It's the same result unless the frame size mod 250 is 0 or 249. The only panel's controller frame is 6512 bytes (mod 250 = 12). Revisit when adding a panel |
| `testPattern` checker `x / cell + y / cell` → `-` | The sum and the difference have the same parity, and only parity is used |
| `sendOk` / `selectRegister` "negated conditional" in both writers | This is the compiler's `COROUTINE_SUSPENDED` check on a tail call. The fake transports never suspend |
| Default `pause = { delay(it) }` lambdas (no coverage) | Tests inject the pause so writes run instantly. Real delays run only on devices |
| `WaveshareWriter.POWER_ON_SEQUENCE` getter (no coverage) | Kotlin generates this getter, but the writer reads the list without calling it |

Nothing needed filing.

## Recommendation: run it on demand

Run `./gradlew :sharedLogic:pitest` when changing `waveshare/` or `image/`
code or their tests, and compare the survivors with the lists above. Don't
add it to CI or a schedule yet:

- 107 of the 114 `waveshare/` mutants that survive or lack coverage are
  compiler noise. A score threshold would either fail on noise or be set
  too low to catch anything.
- It adds about 2.5 minutes on top of the host tests.
- The code it covers changes rarely, and these survivors took a person to
  sort.

Reconsider if the Arcmutate Kotlin filter (or a free equivalent) becomes an
option, or if the protocol code starts changing often.
