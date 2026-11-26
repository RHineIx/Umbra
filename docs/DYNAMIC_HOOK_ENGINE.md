# Shared dynamic hook engine

The shared engine uses exact installed base/split APK SHA-256, package name,
full version code/name, update time, and an adapter discovery-rules revision.
Each target has a separate cache file. Empty or stale caches are not treated
as valid. When no immediate handles register on a cached attempt, the engine
retries discovery, except for adapters that explicitly defer installation.
A thrown attempt rolls back its own registered handles, not unrelated lifecycle
hooks. This does not establish that every individual hook succeeded: existing
adapters may handle errors internally or install later.

Fresh analysis uses the final target loader for split APKs. A per-instance
worker is cancelled at teardown, duplicate package registration is suppressed,
and handles registered after teardown are immediately removed. A successful
cache hit preserves synchronous installation timing. Hashing the APKs has a
startup I/O cost. No app-specific target selection or callback behavior is
changed. Grafika explicitly declares its existing deferred installation.

Gboard retains its synchronous discovery/cache implementation; it shares only
the safer registration/teardown behavior. Google Play's runtime code is
unchanged. Its status text is green on ready, red on unsupported, and neutral
when there is no report; day/night colors are selected independently of the
wallpaper palette. The wording and version label are preserved.

JVM tests cover cache identity and empty-target handling. Actual application
launches, deferred callbacks, and hot reload require device tests.
