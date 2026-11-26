# Runtime performance pass

This branch reduces redundant work without changing discovery fingerprints,
cache identity schemas, account authorization or visual design.

- All three exact APK identity paths share a streaming SHA-256 helper. Full
  base and split contents are still read; hex encoding uses a single bounded
  character array instead of formatting each byte separately.
- Gboard reads field values directly during flag matching rather than allocating
  a value list and a matches list. Ambiguity still returns the original behavior.
  SQL bind indexing avoids intermediate match lists and substrings.
- Spacebar resource lookup has a one-entry weak-reference fast path for the
  common same-resources case; the existing weak map remains the fallback.
  Bounds calculation allocates one rectangle instead of two. Label traversal,
  tint, grouped opacity, icon sizes and resource name are unchanged.
- Google Play stores only installed version metadata for the process lifetime.
  No selected account, enabled setting, account membership or caller identity
  is cached. Every account override still enumerates currently available
  accounts and checks the authenticated provider response. Unchanged reports
  no longer allocate/apply a preferences editor, and normalization remains
  bounded to the original 128 names.
- Completed background workers release their references. Hot-reload cleanup
  and exact-build invalidation are retained.

JVM tests cover full hashes across read-buffer boundaries, equivalent flag
matching, ambiguity short-circuiting, name normalization and SQL bindings.
These are correctness tests, not measurements on an Android device. No
percentage improvement is claimed. Profile cold starts, warm keyboard input,
and account selection on the same installed builds to quantify improvement.
The first startup still hashes the APKs, and Google Play still uses the local
provider on each selection deliberately to avoid stale account decisions.
