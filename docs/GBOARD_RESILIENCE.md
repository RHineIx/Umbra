# Gboard compatibility and resilience

This branch changes only Gboard clipboard/retention and spacebar internals,
plus tests and the validation workflow. No new network access, downloaded
mapping database or changes to Google Play.

- DexKit resolves the reader and retention method using independent existing
  semantic anchors, signature checks and unique matches. Reflection validates
  the full cached descriptor including return type.
- Flag names are read from bounded nonstatic String/Object/CharSequence/enum
  field candidates in the discovered reader hierarchy. Only exact existing
  clipboard flag values are accepted, not a fixed obfuscated field name or
  arbitrary toString(). Multiple matches leave original behavior intact.
- Cache schema 3 includes full version, update time and SHA-256 of base and
  split APK contents. Hashing occurs once during initialization. A failed
  cached installation triggers a fresh scan in the same startup; incomplete
  hook pairs are rolled back and never saved as valid.
- The timestamp-set compatibility hook keeps the established threshold
  translation but only alters homogeneous desugared Instant sets when the
  reported size actually changes. This hook remains process-wide within Gboard.
- Clipboard SQL changes preserve the known query shape, require unambiguous
  parameter binding, and do not mutate the original bindings array.
- The provider and spacebar still use their existing Android API/resource
  identifiers. Spacebar label changes are collected before mutation, and
  labels are restored even if unhooking fails. Unrecognized layouts are not
  guessed.

The exact Gboard APK was not supplied for this change. JVM fixture tests and
compilation do not establish compatibility with its live DEX or UI. The
field-resolution strategy may need adjustment if the real flag identity is
stored in an opaque object rather than an actual String/enum. On a device,
verify clipboard limits (including 1–4), retention, both clipboard feature
flags, and all spacebar icon/tint modes. Compare the first keyboard startup
latency; hashing large APKs has an I/O cost. Existing fixed provider name,
resource name and semantic text/threshold dependencies may need maintenance
if Gboard's implementation changes.
