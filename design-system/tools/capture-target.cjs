'use strict';

/**
 * Resolve the only target the visual parity runner is allowed to control.
 *
 * Goldens are a development quality gate, never a reason to change a household TV's resolution,
 * wake state, or installed application. Keep the safety boundary in a tiny dependency-free module
 * so the command-line runner and its regression test share the exact same rule.
 */
// Side-by-side application IDs intentionally differ from the source namespace. Android resolves a
// component as `<installed package>/<source activity class>`, so keeping both facts here prevents a
// QA capture from accidentally asking Android for `com.scenix.tv.qa.StudioActivity`.
const ACTIVITY_PACKAGE = 'com.scenix.tv';

function captureTarget({ serial, applicationId } = {}) {
  const targetSerial = String(serial || '').trim();
  if (!/^emulator-\d+$/.test(targetSerial)) {
    throw new Error('ADB_SERIAL must name an Android emulator (for example emulator-5556)');
  }

  const targetApp = String(applicationId || 'com.scenix.tv.debug').trim();
  if (!/^com\.scenix\.tv\.(debug|qa)$/.test(targetApp)) {
    throw new Error('SCENIX_CAPTURE_APP_ID must be the side-by-side debug or QA package');
  }

  return { serial: targetSerial, applicationId: targetApp, activityPackage: ACTIVITY_PACKAGE };
}

module.exports = { captureTarget, ACTIVITY_PACKAGE };
