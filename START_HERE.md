# Fitness Log Sync — setup

This is a new Android Studio project. It reads Samsung Health through Health Connect and exports to a standalone **Fitness Log** spreadsheet in **My Drive**. The old Second Brain spreadsheet and app are not modified.

The app retries temporary problems, retains unconfirmed uploads, catches up missed dates, and notifies you about unresolved issues. Routine manual checks are not required. The seven-day app-opening reminder is optional and off by default.

## 1. Create the new Google endpoint

1. Open [Google Apps Script](https://script.google.com/) using the Google account connected to ChatGPT Drive.
2. Choose **New project**. Name it **Fitness Log Sync**. Use a separate project from Second Brain.
3. Replace the contents of `Code.gs` with the complete contents of `apps-script/Code.gs` in this download. Save.
4. In the function dropdown above the editor, choose **setupFitnessLog**, then click **Run**.
5. Approve the spreadsheet access request. For this private script, Google may show an unverified-app screen. Check that the developer is your own account before continuing.
6. The execution log displays your **Sheet URL** and **Connection key**. Keep the key private. Setup creates **Fitness Log** in My Drive, with tabs **Daily Activity**, **Workouts**, **Measurements**, and **Sync Status**. Re-running setup reuses the same sheet and key.
7. Choose **Deploy → New deployment → Web app**. Set **Execute as: Me** and **Who has access: Anyone**. Deploy.
8. Copy the URL ending in **/exec**. This URL and the connection key are the two values you will paste into the phone app.

The endpoint requires the connection key for every data request. Its public browser page shows only the service name and version. The APK contains no Google account credentials and cannot browse your Drive. Do not share the connection key or post it in screenshots.

The supplied `appsscript.json` is available if you want explicit scopes: in Project Settings enable **Show appsscript.json manifest file**, then replace that manifest with the supplied one. The default generated manifest also works because Apps Script detects SpreadsheetApp usage. The supplied manifest requests only spreadsheet access.

If your account does not offer the **Anyone** deployment option, this authentication method cannot be used as written. Stop there and tell me what options Google offers. Do not choose “Anyone with a Google account”: the phone does not perform Google OAuth login.

## 2. Open the project in Android Studio

1. Extract the ZIP somewhere permanent on your Windows PC.
2. Open PowerShell in the extracted **FitnessLogSync** folder, the folder containing `settings.gradle.kts`.
3. Run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\setup-gradle-wrapper.ps1
   ```

   This downloads the official Gradle wrapper and verifies its published SHA-256 checksum. The wrapper JAR is not included in this package because downloads from that host were unavailable in the build environment.

4. Open **Android Studio → Open**, and select that same **FitnessLogSync** folder. Do not create an Empty Activity project or paste these files into the old project.
5. Allow Gradle to sync and Android Studio to download missing components. Use **Gradle 8.13**, **JDK 17 or Android Studio's compatible embedded JDK**, and **Android SDK 36**. The project pins Android Gradle Plugin 8.13.2 and Kotlin 2.2.21.
6. Enable Developer Options and USB debugging on the phone. Connect it, approve the USB prompt, select it in Android Studio, and click **Run**.

To build an APK later, choose **Build → Generate App Bundles or APKs → Generate APKs** (menu wording can vary). The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. A signed personal release is preferable for long-term use; keep the signing key so later updates retain app data. The new package is `au.com.kit.fitnesslogsync`, so it can coexist with the old app.

## 3. Finish the one-time phone setup

1. In Samsung Health, open **Settings → Health Connect → App permissions → Samsung Health**. Allow it to **write** steps, exercise, calories, weight, body fat, height and basal metabolic rate where available. Open Samsung Health after changing these permissions so it can resume sharing.
2. Open **Fitness Log Sync → Connect Google Sheet**. Paste the **/exec URL** and **Connection key** from step 1. Save.
3. Tap **Grant health access**. Allow the offered categories, **background access**, and **history access** where supported. No sleep or distance permissions are requested.
4. Tap **Allow issue notifications** and allow notifications.
5. Tap **Battery settings**. On Samsung, add **Fitness Log Sync** to **Never sleeping apps**. In the app's system battery settings, choose **Unrestricted** where offered. The Samsung list may be under **Settings → Battery / Device care → Background usage limits** depending on your phone.
6. Tap **Enable automatic syncing**. The initial import begins automatically. It may take several minutes with history access; if interrupted, saved progress resumes on the next run.

If background read access is unavailable on your Android/Health Connect version, the app can sync while open, but it cannot provide unattended health reads. It will explain that instead of claiming automatic syncing is enabled.

History access imports available records from **9 July 2026** onward, matching the original exporter period. Without it, the initial import stays inside the readable recent history window. Granting history access later automatically starts an earlier import. It cannot recover records Samsung Health has never shared with Health Connect or records that Health Connect has deleted.

## 4. Confirm the first connection once

This is an installation check, not a routine chore:

1. The app should show a recent **Last confirmed upload**, zero pending uploads, and healthy category statuses.
2. Open the new sheet. Check one completed day's steps against Samsung Health and confirm any available recent measurement is present.
3. In **Sync Status**, `setup_state` should be `active`, and the contact and confirmation timestamps should be recent.
4. Tell ChatGPT: **“The new Fitness Log app is installed. Test reading my new sheet.”** This confirms the actual sheet identity and allows an end-to-end read test. The daily briefing's scheduled access must also be observed on a real run; changing its prompt alone does not establish unattended access.

Once the new app is working, pause the old exporter's automatic syncing to avoid confusion. Keep the old spreadsheet as history. The new app does not write to it.

## Normal behaviour

- Background sync is requested every **two hours**, whenever Android allows it and a network is available. It does not promise an exact execution time.
- It exports today so far and rechecks the previous three dates for late updates. Each category resumes from its confirmed checkpoint after an interruption.
- Steps use Health Connect aggregation filtered to Samsung Health to avoid summing phone and watch records twice.
- Workouts retain their record IDs, start/end timestamps, Samsung exercise type code, optional title, and elapsed session time. Elapsed time may include pauses and is labelled accordingly.
- Calories are labelled **calories reported by Health Connect**. They are not relabelled as active calories, intake, or a calorie deficit. Samsung's mapping and exported values can differ from its headline activity screen.
- Weight, body-fat percentage, height and basal metabolic rate retain measurement timestamps and stable source IDs. Updates replace the same reading. Deletions remain as marked tombstones; only rows with `deleted = FALSE` are current measurements.
- Measurements are sparse. No reading on a particular date is normal. Updating only a Samsung profile field may not create a Health Connect measurement; availability depends on what Samsung Health exports.
- Measurement change tokens are independent. On token expiry, the app reconciles the readable history and preserves records outside that window. Loss of access to one category leaves the other categories working.
- No-data aggregate results are blank and labelled `no_data`. Genuine numeric zeros remain zero. A successful read that returned no data is distinguishable from a permission/read failure.
- Temporary connection failures retry quietly. While a worker can run, persistent temporary problems notify after approximately six hours; permission/configuration problems notify as soon as encountered. Android can suppress execution, so the briefing's independent stale-data check remains important.
- A continuing issue produces one notification, not a notification every two hours. A fully healthy sync after an alerted problem produces a recovery notification.
- You can leave the optional seven-day check-in **off**. It is not needed for the intended routine workflow once battery settings are configured, and it cannot wake an app already put into deep sleep.

## Recovery

Open the app when an alert or briefing asks you to. It automatically retries and updates its status. Use **Sync now / retry uploads** if needed. **Grant health access**, **Allow issue notifications**, and **Battery settings** lead to the relevant controls.

**Import earlier dates** safely reimports from a chosen date, preserving existing records by ID. Progress survives an interrupted import. Source edits to daily activity and workouts more than three days old require this backfill action; measurement edits use change tracking automatically. Long gaps or expired tokens require enough history permission to reconcile older records. Irrecoverable history gaps are reported instead of silently claiming they were recovered.

If Google authentication fails, check the saved key. If you lost it, re-run `setupFitnessLog` to display the existing key. To deliberately revoke a key, run `rotateConnectionKey`, then paste the new key into the phone.

If you edit `Code.gs`, choose **Deploy → Manage deployments → Edit → Version: New version → Deploy**. Keep the same deployment URL. Saving script source alone does not update the deployed web app.

Keep the tab names and first-row headers intact. Changing them makes the script stop with a clear schema error instead of guessing where to write. Prefer viewing and filtering the synced data rather than manually changing its values.

For troubleshooting, **Recent sync history** shows the latest 20 attempts. No credentials are included. The app retains up to 100 local log entries and encrypts its connection key using Android Keystore. Backups are disabled; uninstalling deletes local credentials, checkpoints and queued uploads, while the Google sheet remains.

## Validation and remaining checks

The upload script's functional tests run with:

```powershell
node --test tests/server.test.cjs
```

They cover retries, corrections, zeros versus missing data, measurement edits/deletions, token-expiry reconciliation boundaries, malformed snapshots, changed headers, failed readback, authentication, category isolation and formula-like workout titles. The tests mock Google services; they do not establish real Google deployment behaviour.

In Android Studio, run **Build → Make Project**. From its terminal, after wrapper setup:

```powershell
.\gradlew.bat lintDebug assembleDebug
```

**APK compilation, lint, device notifications, real Health Connect reads, Android process-stop/reboot recovery and unattended scheduled-briefing access were not executed in the authoring environment.** It has no Android SDK, and the SDK download host was inaccessible. Treat the first Android Studio build and phone connection as the remaining integration checks. If a build error occurs, send its complete error text; do not upgrade all dependencies at once.

## References

- [Health Connect background and historical reads](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Health Connect change tracking and token expiry](https://developer.android.com/health-and-fitness/health-connect/sync-data)
- [Samsung background application management](https://developer.samsung.com/mobile/app-management.html)
- [Samsung measurement export mapping](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect)
- [Android Gradle Plugin 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Gradle wrapper verification](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
