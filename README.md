# Music Assistant mobile app – patched build

Builds [music-assistant/mobile-app](https://github.com/music-assistant/mobile-app)
with the patches in `patches/` and publishes the APK as a GitHub release.
Install and update it with Komi Store or Obtainium by adding this repository.

The app installs as `io.music_assistant.client.patched` ("Music Assistant (patched)"),
next to the official app.

Patches:
- Volume keys step the selected remote player's volume while the app is in the foreground.
- Separate application id / label / version suffix.

## How it works

- `main` mirrors upstream and is not touched.
- `patched` (default branch) holds only this README, the workflow and `patches/`.
- `.github/workflows/build.yml` runs daily, on push and manually. It takes the newest
  upstream `android-v*` tag, applies `patches/*.patch` with `git am --3way`, builds
  `assembleRelease` signed with the key from the repository secrets and creates a release
  `<upstream tag>-patched-<patches hash>`. If that release exists, it does nothing.
- If a patch no longer applies, the run fails (GitHub sends a mail).

## Updating the patches

In the upstream clone, rebase the patch branch onto the new tag and re-export:

```sh
cd ~/dev/ma-mobile
git fetch origin --tags
git rebase --onto android-vX.Y.Z <old-tag> volume-keys   # fix conflicts if any
~/dev/ma-mobile-patched/scripts/update-patches.sh ~/dev/ma-mobile
cd ~/dev/ma-mobile-patched && git add -A patches && git commit -m "Rebase patches onto android-vX.Y.Z"
```

## Secrets

| Secret              | Value                                   |
|---------------------|-----------------------------------------|
| `KEYSTORE_BASE64`   | `base64 -w0 mass-release.jks`           |
| `KEYSTORE_PASSWORD` | keystore password                       |
| `KEY_ALIAS`         | key alias (`mass`)                      |
| `KEY_PASSWORD`      | key password                            |

Keep a backup of the keystore: a build signed with a different key cannot update
the installed app.
