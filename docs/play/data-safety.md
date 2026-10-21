# Play Console — Data safety form

Answers for **Ardaas (`io.sarbloc.ardaas`)** as built from `main` after #121.
Console path: *Policy and programs → App content → Data safety*.
Re-check every answer whenever a network call, SDK, permission or backup
rule changes (#92 was written before translation existed — do not reuse its
"no network requests at all" line).

## Facts the answers rest on

| Fact | Where |
|---|---|
| No accounts, analytics, crash reporting, ads or tracking SDKs. Dependencies are Compose, Room, kotlinx-serialization and ONNX Runtime only. | `android/app/build.gradle.kts` `dependencies {}` |
| Saved Ardaas live in a local Room DB, `ardaas.db`. | `persistence/ArdaasDatabase.kt` |
| Reader preferences live in local SharedPreferences, `reader`. | `ui/reader/ReaderPreferences.kt` |
| Translation runs on the device. | `translation/TranslationEngine.kt`, `BentiTranslationService.kt` |
| The **only** network request: an opt-in, HTTPS-only download of model files from Hugging Face at a pinned revision, SHA-256 verified. It is a plain GET (plus `Range` to resume); it carries no user content. | `translation/ModelCatalog.kt` (`repoBase`), `translation/ModelFileFetcher.kt` |
| Permissions: `INTERNET` (that download) and `ACCESS_NETWORK_STATE` (refuse metered networks unless the user allows mobile data). Nothing else. | `AndroidManifest.xml` |
| Backup: Android Auto Backup / device transfer of `ardaas.db` and `reader.xml` only, into the user's own Google account, controlled by the user. The developer cannot read it. The model (`noBackupFilesDir`) is never backed up. | `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` |

## Data collection and security

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | Nothing the user creates is sent off the device by the app. Translation is on-device processing, which Google says need not be disclosed. The model download sends no user data. |
| Is all of the user data collected by your app encrypted in transit? | *Not asked after "No".* If shown: **Yes** | The one transfer is the model download, and `ModelCatalog.repoBase` is `https://`; cleartext is blocked by default at targetSdk 28+. |
| Do you provide a way for users to request that their data is deleted? | *Not asked after "No".* If shown: **No** (no data collected) | Users delete saved Ardaas in the app; uninstalling removes everything. |
| Does your app allow users to create an account? | **No** (if asked) | No sign-in of any kind. |

Result: the listing shows **"No data collected"** and **"No data shared with third parties"**.

### Judgment calls, stated so they can be checked

- **Auto Backup is not "collection".** Backup is performed by Android into the
  user's own Google account; the app never transmits it and the developer
  cannot access it. Google's definition of "collect" is data transmitted off
  the device *by your app*. If Google ever says otherwise, the fallback is to
  set `allowBackup="false"` rather than declare collection.
- **Hugging Face sees an IP address.** Any HTTP request exposes the device's IP
  and standard headers to the server. The app does not collect, store or
  receive it, and sends no identifiers. The privacy policy says this
  explicitly.

## Data types

Skipped — no data types are collected or shared.

## Security practices (optional badges)

| Item | Answer |
|---|---|
| Independent security review (MASA) | No |
| Committed to the Play Families Policy | Only if the target audience includes under-13s — see `app-content.md` |
