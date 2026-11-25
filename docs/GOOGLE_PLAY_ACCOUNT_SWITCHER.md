# Google Play account switcher

Enable the Google Play Store scope for Umbra in an API 102-compatible manager, force-stop Google Play once, open it, then open Umbra. The original single-column layout is retained. Gboard and Google Play are separate expandable cards, initially collapsed. Pydroid 3, Grafika and Showly are grouped inside the initially collapsed Other Hooks card. Neither Google Play nor Gboard has an app logo; Gboard's functional action icons and settings remain.

The feature is off by default. Enable it, choose an available Google account, and verify the account in the next purchase confirmation. Reset removes the preference and turns off the override. No purchases are initiated by Umbra. Existing checkout sessions may need to be closed before changing accounts.

DexKit discovers the primary account manager through two independent account-cache/current-account string anchors, then validates the a/b/f/i signatures and AccountManager field before installing. The resolved class is cached by version code, version name and last update time. For the reviewed 54.4.11-34 [0] [PR] 1025789771 build, the verified DEX mapping is ppr and the checkout model is tar; the latter is used only on that exact version. Legacy poc compatibility remains. Unknown secondary routes are not guessed or rewritten. The original PSwitch checkout flow is retained for the legacy mapping. Diagnostics report stage and exception type, not email addresses. Runtime compatibility still requires a real checkout test. If primary symbols cannot be resolved, or the selected account cannot be verified in the current account list, the original behavior is retained. The status card is a last report, not live proof of checkout success.

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
