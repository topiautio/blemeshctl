# Releasing

Releases are built and published by GitHub Actions when a tag is pushed.
Without a tag, pull requests and pushes to `main` keep the verified Linux
executable as a workflow artifact, and pull requests that touch `android/` and
pushes to `main` keep a debug APK.

## Linux executable

Push a version tag such as `v0.1.0`. The workflow creates a GitHub Release and
attaches the executable as `blemeshctl-linux-x86_64`.

## Android app

Android releases use tags that start with `android-v`. These releases are not
marked as latest, so the Linux download link in the README keeps pointing at
the CLI.

### Signing key

Android releases are signed in GitHub Actions with a key kept in repository
secrets. Create the key once, outside the repository:

```bash
keytool -genkeypair -keystore blemeshctl-release.jks -storetype PKCS12 \
  -alias blemeshctl -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=blemeshctl"
base64 -w0 blemeshctl-release.jks > blemeshctl-release.jks.base64
```

Then add these secrets under the repository's Settings, Secrets and variables,
Actions:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Contents of `blemeshctl-release.jks.base64` |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_ALIAS` | `blemeshctl` |
| `ANDROID_KEY_PASSWORD` | The keystore password again; PKCS12 keys share it |

Back up `blemeshctl-release.jks` and its password somewhere safe. Every later
update must be signed with the same key; if the key is lost, users must
uninstall the app before they can install a new build.

### Publishing a release

Set `versionCode` and `versionName` in `android/app/build.gradle.kts`, merge
the change to `main`, and push a matching tag:

```bash
git tag android-v0.4.0
git push origin android-v0.4.0
```

The workflow refuses to publish when the tag does not match `versionName`,
when a signing secret is missing, or when the APK is debuggable.
