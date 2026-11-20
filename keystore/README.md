# The debug key

`naudio-debug.keystore` signs **every debug build** of the app - the one on a machine and the
one on a runner alike.

## Why it is in the repository

The Android SDK makes a **throwaway debug key** wherever a build runs: `~/.android/debug.keystore`
on a machine, a new one on every runner. Two debug builds therefore never share a signer, and
Android refuses to put one over the other:

```
adb: failed to install: INSTALL_FAILED_UPDATE_INCOMPATIBLE
Package com.maxrave.simpmusic.dev signatures do not match previously installed version
```

The only way out of that was to uninstall the app first - and with it everything the app had
stored: the library, the downloads, the settings, the queue.

With one key in the repository, every debug build is signed the same way, and a new build can be
put over an older one:

```
adb install -r naudio-android-arm64-debug.apk
```

The data of the app stays where it is.

## What this key is not

- It signs **no release**. The release builds use the production key, which lives in the secrets
  of the repository (`BASE_64_SIGNING_KEY`) and never in a file of the tree.
- It is **not a secret**. The password is written in the build files, on purpose: a key that
  anyone can sign a debug build with is exactly what is wanted here.
- It does not stand in for the production key. The check that the app is an official build reads
  the certificate of the build and compares it with the key of the release - and it does not run
  on a debug build at all.

## The two applications side by side

| Build | Package | Signed with |
|---|---|---|
| Release | `com.maxrave.simpmusic` | the production key (a secret) |
| Debug | `com.maxrave.simpmusic.dev` | `naudio-debug.keystore` (this directory) |
| vivo debug | `com.spotify.music` | `naudio-debug.keystore` (this directory) |

A debug build and a release build are **two different apps** on the phone: they stand side by
side, each with its own data. A debug build updates another debug build ✓.

## The fingerprint of the key

```
SHA-256: 6c5113c44b4b809a7d8a96ee3752df45b4bdff8283d202cb99565c17bb0870520
```

It is checked by `scripts/verify-debug-signing.sh`, which reads the certificate of a built APK
and compares it with this one.
