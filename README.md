<p align="center">
  <img src="Icon.png" alt="Cinnazuku Logo" width="128" height="128" />
</p>

<h1 align="center">Cinnazuku</h1>

<p align="center">
  <b>Updated version of Shizuku rebuilt to work flawlessly with Android 17 (Beta 3+ through QPR2 and stable release).</b>
</p>

---

## What is Cinnazuku?

Cinnazuku is an enhanced fork of [Shizuku](https://github.com/RikkaApps/Shizuku) specifically patched to resolve the Android 17 / API 37 package management breakage while introducing a refreshed modern UI with iOS-style progressive blur navigation and fluid micro-interactions.

### Android 17 Compatibility Fix
In Android 17 Beta 3+, Google changed the internal IPC return type of `IPackageManager.getInstalledPackages(long, int)` from `ParceledListSlice` to `PackageInfoList`. This caused original Shizuku builds to fail with `NoSuchMethodError` / `ClassCastException`, leaving the Authorized Applications management screen permanently empty (0 apps).

Cinnazuku includes an intelligent multi-strategy compatibility layer (`InstalledPackagesCompat`) that:
- Seamlessly resolves installed applications across Android 7.0 through Android 17 QPR2+.
- Retains 100% backward-compatible IPC interfaces, so existing apps (AppOps, Swift Backup, IceBox, aBattery, Termux, etc.) continue to work out of the box without code modifications.

## Credits & Licensing

- Original **Shizuku** developed and maintained by [RikkaApps](https://github.com/RikkaApps/Shizuku).
- Released under the [Apache License 2.0](LICENSE).
- In accordance with Section 6 of Apache 2.0 and the upstream licensing terms, this project uses the independent name **Cinnazuku** and custom visual assets.
