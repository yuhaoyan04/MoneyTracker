#!/usr/bin/env bash
# Only a fresh emulator and the separate .isolated package are used here.
set -u

classes="com.mudasir.smartledger.CaptureDiagnosticsTest,com.mudasir.smartledger.CaptureDuplicateDaoTest,com.mudasir.smartledger.CategorySelectionFlowTest,com.mudasir.smartledger.CooldownOverlayInflationTest,com.mudasir.smartledger.CoreScreensSmokeTest,com.mudasir.smartledger.ExpenseTaxonomyMigrationTest,com.mudasir.smartledger.GuardThresholdStorageTest,com.mudasir.smartledger.InboxConfirmFlowTest,com.mudasir.smartledger.LegacyBackupFlowTest,com.mudasir.smartledger.LegacyTaxonomyMigrationTest,com.mudasir.smartledger.SettingsLayoutTest,com.mudasir.smartledger.WishlistBackupFlowTest,com.mudasir.smartledger.DesignReviewTest"

./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  -PisolatedDeviceTest=true \
  "-Pandroid.testInstrumentationRunnerArguments.class=$classes" \
  -Pandroid.testInstrumentationRunnerArguments.persistDesignPreviews=true \
  --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' --console=plain
result=$?

# Collect only programmatically rendered synthetic UI, never an emulator-wide dump.
adb pull /sdcard/Download/Zhiyu-QA app/build/design-review || true
exit "$result"
