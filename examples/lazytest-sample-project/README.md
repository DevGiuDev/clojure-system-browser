# Lazytest sample project

Open `examples/lazytest-sample-project` in CSB. Open **Tests** in Desktop or
press F3 in the TUI. The example uses Lazytest 2.1.0 and keeps its suites in
`spec`.

| Action | Expected result |
| --- | --- |
| **Run suite** (F4) | `delivery-fee` and `subtotal` turn green. `failure-example` turns red. Both omitted suites stay gray. The run reports one intentional failure. |
| **Run namespace** (F5) on `demo.delivery-test` | Both delivery cases pass. |
| **Run test** (F6) on `demo.orders-test/subtotal` | Only the two subtotal cases run and pass. |
| **Open test source** or Enter on `subtotal` | CSB opens the read-only `defdescribe` suite. |

To see how CSB reports an error during setup, remove the
`--exclude setup-error` option pair from this project's `deps.edn` and run
`demo.orders-test/setup-error-example` individually. The output contains
`Intentional setup error`.

From this example directory, run the same suite directly with
`clojure -Srepro -M:lazytest`. The command exits with status 1 because of the
intentional failure. Add `--exclude demo-failure` for a passing run.
