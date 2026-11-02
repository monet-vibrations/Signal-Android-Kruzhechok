# Kruzhechok F-Droid publishing workflow

This patch adds a dedicated GitHub Actions workflow for the `round-video-messages` branch. It builds the `githubProdRelease` universal APK using the repository's existing reproducible-build Docker image, signs it with a permanent Android release key retrieved from 1Password, creates a signed F-Droid repository index with a separate key, preserves older APK versions, and deploys the repository to GitHub Pages.

## What changed

- `app/build.gradle.kts`: assigns `org.thoughtcrime.securesms.kruzhechok` to `githubProdRelease`.
- `.github/workflows/publish-fdroid.yml`: manually triggered build/sign/index/publish pipeline.
- This document: one-time setup and operating notes.

This workflow is a starting implementation; it has not been run against your GitHub repository or your 1Password vault. Complete the one-time setup and test the first run before sharing the repository URL.

## 1. Create two permanent keystores

Do this on a trusted computer with Java `keytool`. Use different passwords for the two keystores, and keep secure offline backups.

### Android APK signing key

```bash
umask 077
keytool -genkeypair -v \
  -keystore kruzhechok-release.jks \
  -alias kruzhechok \
  -keyalg RSA -keysize 4096 -validity 10000
```

When prompted for the private-key password, you may use the same password as the keystore password. If you do, set the two 1Password references below to the same password field.

### F-Droid repository index signing key

```bash
umask 077
keytool -genkeypair -v \
  -keystore fdroid-repo.jks \
  -alias fdroidrepo \
  -keyalg RSA -keysize 4096 -validity 10000
```

**Never regenerate either key after publishing.** The Android key signs APK updates; the F-Droid key signs repository metadata. They serve different purposes.

## 2. Store the keystores in 1Password

Create two items in a dedicated vault, for example `Build Secrets`:

1. `Kruzhechok Android Signing`: attach `kruzhechok-release.jks`; save the keystore password in the `password` field and the private-key password in a field named `keyPassword`.
2. `Kruzhechok F-Droid Index Signing`: attach `fdroid-repo.jks`; save the keystore password in the `password` field and the private-key password in a field named `keyPassword`.

Use 1Password's **Document** item type for the files if you prefer, but ensure the CLI reference points to the file attachment/document correctly. Verify each reference with `op read` locally before wiring it into Actions. File references should resolve to the binary keystore, not a text/base64 export.

Create a 1Password **Service Account** with read-only access to only the vault containing these items. Store its token in GitHub repository Actions secrets as:

- `OP_SERVICE_ACCOUNT_TOKEN`

Do not put the service-account token or keystores in the source tree.

## 3. Add GitHub Actions variables

In the GitHub repository, open **Settings → Secrets and variables → Actions → Variables** and create these repository variables. Replace `Build Secrets` if you chose a different vault or item name. Secret references are case-sensitive and must match your actual 1Password items and fields.

| Variable | Example value |
|---|---|
| `OP_ANDROID_KEYSTORE_REF` | `op://Build Secrets/Kruzhechok Android Signing/kruzhechok-release.jks` |
| `OP_ANDROID_STOREPASS_REF` | `op://Build Secrets/Kruzhechok Android Signing/password` |
| `OP_ANDROID_KEYPASS_REF` | `op://Build Secrets/Kruzhechok Android Signing/keyPassword` |
| `OP_FDROID_KEYSTORE_REF` | `op://Build Secrets/Kruzhechok F-Droid Index Signing/fdroid-repo.jks` |
| `OP_FDROID_STOREPASS_REF` | `op://Build Secrets/Kruzhechok F-Droid Index Signing/password` |
| `OP_FDROID_KEYPASS_REF` | `op://Build Secrets/Kruzhechok F-Droid Index Signing/keyPassword` |

If your Android key uses the same password for both purposes, point `OP_ANDROID_KEYPASS_REF` to the same field as `OP_ANDROID_STOREPASS_REF`. The same rule applies to the F-Droid key.

Before trusting these references in CI, test them on a trusted machine using `op read 'op://…'`. The attachment reference syntax depends on the name/type of the item you create in 1Password.

## 4. Enable GitHub Pages

In the GitHub repository, go to **Settings → Pages** and set **Build and deployment → Source** to **GitHub Actions**.

The workflow deploys the static repository at:

`https://monet-vibrations.github.io/Signal-Android-Kruzhechok/`

F-Droid clients should add this repository URL:

`https://monet-vibrations.github.io/Signal-Android-Kruzhechok/repo`

The workflow keeps a `fdroid-repo` branch as a persistent copy of the published files so older APKs survive subsequent deployments. Do not edit that branch manually unless you know how it affects the repository.

## 5. Run a release

1. Commit the workflow and Gradle change to `round-video-messages`.
2. Open **Actions → Build, sign, and publish Kruzhechok F-Droid repo → Run workflow**. Run it from `round-video-messages`.
3. Wait for the build, APK signature verification, index generation, and Pages deployment to succeed.
4. Add the repository URL above to the F-Droid client and refresh.

The workflow only runs on manual dispatch from `round-video-messages`; it does not run on pull requests or arbitrary pushes. Protect this branch and review changes to the workflow carefully, because a modified workflow that runs with signing credentials could misuse them.

## Release/versioning notes

- The workflow fails if a different APK is published with a versionCode that already exists. Android requires monotonically increasing version codes for updates. If your fork's source changes without increasing versionCode, update the project's versioning for the next release before publishing it.
- The workflow selects the universal APK, not ABI-specific split APKs.
- The APK must be signed with the same Android key for all future updates.
- GitHub Pages deployment is public. Only the APK, repository index, icons, and site instructions are published; signing keystores and passwords are not intended to be published.
- The build can fail if upstream dependencies, Docker build requirements, or the source branch's build setup have changed. The first workflow run is a validation run, not a guarantee of a successful release.
