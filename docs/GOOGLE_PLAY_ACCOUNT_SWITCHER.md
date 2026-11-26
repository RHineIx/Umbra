# Google Play account switcher

Enable the Google Play Store scope for Umbra in an API 102-compatible manager, force-stop Google Play once, open it, then open Umbra. The original single-column layout is retained. Gboard and Google Play are separate expandable cards, initially collapsed. Pydroid 3, Grafika and Showly are grouped inside the initially collapsed Other Hooks card. Neither Google Play nor Gboard has an app logo; Gboard's functional action icons and settings remain.

The feature is off by default. Enable it, choose an available Google account, and verify the account in the next purchase confirmation. Reset removes the preference and turns off the override. No purchases are initiated by Umbra. Existing checkout sessions may need to be closed before changing accounts.

The module runs entirely offline. On the known 54.4.11-34 [0] [PR] 1025789771 build (version code 85441140), the reviewed ppr/tar mapping is accepted only if the base APK SHA-256 matches the supplied reference. For unknown builds, DexKit resolves all method names and the checkout field dynamically from unique semantic and structural evidence: current-account and cache text anchors, instance AccountManager field, method signatures, lookup/current/list/name invocation edges, billing Bundle key, Bundle.getString invocation, and one instance String field written by the constructor. If splits exist, the full target loader is scanned. Ambiguous or incomplete mappings retain original behavior; all hooks are installed atomically as a group, rolled back on failure, and only complete installed mappings enter the schema-versioned cache keyed by base and split APK SHA-256, full version and update time. Cache resolution failures trigger fresh discovery. The unversioned legacy poc route is removed. No in-progress checkout saved-state rewriting is performed. A per-thread guard prevents recursive account enumeration. The status card reports hook installation, not proof of checkout success. Real-device verification of account and amount remains required before confirming any purchase.

The companion stores account names locally, with Android backups disabled; a narrow provider call verifies the caller package and UID. No account-name logging, exported broadcast, unrestricted provider query, root file, or external network transmission is added. Other Umbra features do not receive the selected account via shared remote preferences.

## Manual validation

- Expand and collapse each of the three cards independently. Check that Gboard clipboard and spacebar settings still work, and that returning from the spacebar screen preserves expansion state.
- Turn Account override off: the entire account list, including the default row, must dim and reject selection. Re-enable with a connected Xposed service to restore normal selection.


- With override off, compare default purchase-sheet behavior to unmodified Play Store.
- With two accounts, enable and choose each in turn; confirm the displayed account and price without completing a purchase.
- Reset, remove the selected Google account, and verify fallback to the default.
- Force-stop/reopen Play Store and Umbra; verify persisted selection, live changes for new purchase flows, and scope-disabled behavior.
- Test an unsupported store build and check that the original behavior survives.
- Test calls from an unrelated app: provider calls and queries must be rejected.
- Exercise Gboard clipboard and spacebar settings and other existing hooks.
- Test hot reload and cold restart on actual Android 17 / ColorOS 17 with the intended framework.

Not affiliated with Google. Use only with accounts and devices you own or are authorized to use.
