# GitHub APK signing setup

To install updates over an existing APK on the target device, each new APK must keep:

- the same `applicationId` (`com.example.androidhaterminal`)
- the same signing key
- a higher `versionCode`

This repository is configured to do that in GitHub Actions by building a signed `release` APK and setting:

- `versionCode = github.run_number`
- `versionName = 1.0.<run_number>`

## 1. Create a release keystore once

Run this locally and keep the resulting `.jks` file safe:

```powershell
keytool -genkeypair -v -keystore androidhaterminal.jks -alias androidhaterminal -keyalg RSA -keysize 2048 -validity 10000
```

Record these four values because GitHub Actions will need them:

- keystore file: `androidhaterminal.jks`
- keystore password
- key alias
- key password

## 2. Convert the keystore to Base64

GitHub secrets cannot store binary files directly, so encode the keystore:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("androidhaterminal.jks")) | Set-Content androidhaterminal.jks.base64
```

Copy the contents of `androidhaterminal.jks.base64`.

## 3. Add GitHub repository secrets

In GitHub, open `Settings` -> `Secrets and variables` -> `Actions` and create:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## 4. Run the workflow

Use the `Android Release (Android HA Terminal)` workflow from the Actions tab. It will:

- decode the keystore into the runner temp folder
- build `assembleRelease`
- sign the APK with your release key
- upload a versioned artifact

## 5. Install updates on the device

The target device can install each new APK as an update if:

- it already has the same app package installed
- the APK is signed with the same keystore as the previous install
- the new APK has a higher `versionCode`

If you lose the keystore or change keys, Android will treat the APK as a different signer and require uninstalling the old app first.
