rootProject.name = "kalshi-arb"

// Dependencies point strictly downward. See CLAUDE.md section 4.
//   LAYER 0  libs/core-model, libs/common      value types, Clock/Config/Backoff
//   LAYER 1  libs/kalshi-client, libs/book     I/O + book reconstruction
//   LAYER 2  libs/detector                     pure, no I/O
//   LAYER 3  apps/recorder                      runnable

include(
    "libs:core-model",
    "libs:common",
    "libs:kalshi-client",
    "libs:persistence",
    "libs:book",
    "libs:detector",
    "apps:recorder",
)
