# Play upload from CI

`.github/workflows/android-release.yml` builds the upload-key-signed AAB, keeps
it as a workflow artifact, and then (optionally) uploads it to a Play testing
track with a Google Cloud service account. Until the `PLAY_SERVICE_ACCOUNT_JSON`
repo secret exists, the upload step is skipped with a notice and the workflow
behaves exactly as before (artifact only).

The service account key is **as sensitive as the upload key**: anyone holding
it can push builds to the app's testing tracks. Treat it the same way — never
commit it, never paste it into an issue or chat, keep one copy in a password
manager.

## One-time setup

### 0. Before the first CI upload

- The app (`io.sarbloc.ardaas`) must already exist in Play Console, and **the
  first AAB must be uploaded by hand** (download it from a workflow run's
  artifact and create a release in Play Console). The API can't create an app;
  before this the action fails with "Package not found".
- While the app is still a *draft app* (never published to any track, not yet
  through review), Play only accepts releases with status `draft`. Run with
  `status=draft` (the default) until the first release has been rolled out;
  `status=completed` fails with "Only releases with status draft may be
  created on draft app."

### 1. Google Cloud: project, API, service account, key

1. Open <https://console.cloud.google.com/> and create a project (e.g.
   `ardaas-play`), or pick an existing one. You no longer need to link it to
   the Play developer account; the old *Setup → API access* page is gone.
2. Enable the **Google Play Android Developer API** for that project:
   <https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com>
   → **Enable**.
3. **IAM & Admin → Service accounts → Create service account.** Name it e.g.
   `ardaas-play-ci`. Skip the optional "grant this service account access to
   project" and "grant users access" steps: it needs **no** Cloud IAM roles.
   Play access is granted in Play Console (step 2 below).
4. Open the new service account → **Keys → Add key → Create new key → JSON**.
   The JSON file downloads. Note the account's email
   (`ardaas-play-ci@<project-id>.iam.gserviceaccount.com`).

If key creation is blocked by the organisation policy
`iam.disableServiceAccountKeyCreation`, that applies only to Google Workspace /
Cloud organisations; a personal Google account has no such policy.

### 2. Play Console: invite the service account

1. <https://play.google.com/console> → **Users and permissions** (left menu,
   account level, not inside the app).
2. **Invite new users**. Email address: the service account email from step
   1.4. Leave access expiry unset (or set one and rotate).
3. **Account permissions** tab: leave everything **unchecked**.
4. **App permissions** tab → **Add app** → select **Ardaas** → **Apply**. In
   the dialog grant only:
   - **View app information (read-only)**
   - **Release apps to testing tracks**

   Do **not** grant "Release to production, exclude devices, and use Play App
   Signing" — CI only targets `internal` and `alpha` (closed testing), and
   production stays a manual promotion in Play Console.
5. **Invite user** → confirm. Service accounts don't need to accept the
   invite. Permissions can take a few minutes (occasionally up to a day) to
   take effect; until then the upload fails with "The caller does not have
   permission".

### 3. GitHub secret

From the repo checkout, with `gh` logged in as Sarbloc:

```sh
gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo sarbloc/ardaas-app < ~/Downloads/<project-id>-<key-id>.json
```

Then store the JSON in the password manager (next to the upload keystore
entry) and delete the downloaded file:

```sh
rm ~/Downloads/<project-id>-<key-id>.json
```

If you'd rather keep no copy at all, skip the password manager: a lost key is
replaced by creating a new one (step 1.4) and re-running `gh secret set`.

## Releasing

Run the workflow from `main` (it refuses any other ref):

```sh
gh workflow run android-release.yml --ref main -f track=alpha -f status=draft
gh run watch "$(gh run list --workflow android-release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Or **Actions → Android release → Run workflow** in the GitHub UI.

| Input | Values | Meaning |
|---|---|---|
| `track` | `alpha` (default) | Closed testing. `alpha` is the API name of Play's closed testing track. |
| | `internal` | Internal testing. |
| | `none` | Build and keep the artifact only; no Play upload. |
| `status` | `draft` (default) | The release is created as a draft on the track. Open Play Console → Ardaas → *Test and release* → the track → review the release, add release notes, **Roll out**. |
| | `completed` | Rolled out to the track's testers immediately. Not accepted while the app is a draft app (see step 0). |

The AAB is always attached to the run as an artifact
(`ardaas-<versionName>-<versionCode>.aab`, kept 30 days), whatever the inputs.

## Troubleshooting

- **"Version code N has already been used" / "APK specifies a version code
  that has already been used".** `versionCode` is the commit count on `main`,
  so re-running the workflow on the same commit re-uploads the same
  `versionCode`. Land a new commit on `main` and release that.
- **"Package not found".** The first AAB hasn't been uploaded by hand yet
  (step 0), or `io.sarbloc.ardaas` isn't the app in this developer account.
- **"The caller does not have permission".** The service account isn't
  invited, doesn't have the Ardaas app permissions from step 2, or the grant
  hasn't propagated yet.
- **"Only releases with status draft may be created on draft app".** Use
  `status=draft` until the first release is out (step 0).
- **"Changes cannot be sent for review automatically".** Play wants these
  changes sent for review from the console (for example after a rejection, or
  with Managed publishing on). The release is not created; finish it in Play
  Console by hand, uploading the AAB from the run's artifact.
- **Upload skipped with a notice.** `track=none` was chosen, or the
  `PLAY_SERVICE_ACCOUNT_JSON` secret isn't set (`gh secret list` to check).

## Rotating or revoking the key

- Rotate: create a new JSON key (step 1.4), `gh secret set` it (step 3), then
  delete the old key under the service account's **Keys** tab.
- Revoke immediately (leaked key): delete the key under **Keys** in Google
  Cloud, and remove the service account in Play Console → **Users and
  permissions**. Then `gh secret delete PLAY_SERVICE_ACCOUNT_JSON` until a new
  key is set up; the workflow falls back to artifact-only.

## Why a third-party action

The upload uses `r0adkll/upload-google-play`, pinned to a full commit SHA in
the workflow. The alternatives were calling the Android Publisher API by hand
(edits insert → bundle upload → track update → commit, plus OAuth token
minting from the JSON key, all in shell) or the Gradle Play Publisher plugin
(a new build dependency that would also run inside the signed build with the
upload-key secrets present). The pinned action is the smallest, most
reviewable surface; bump the SHA deliberately when a new release is needed.
