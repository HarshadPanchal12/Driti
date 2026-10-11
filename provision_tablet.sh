#!/usr/bin/env bash
# =============================================================================
# Drishti Tablet Provisioning Script
# Run this ONCE per tablet at mounting time (before signing into any account)
# =============================================================================
#
# Requirements:
#   - adb installed on your laptop (https://developer.android.com/tools/adb)
#   - USB cable connected to tablet
#   - Tablet must be factory reset with NO Google account signed in
#   - Developer Options must be enabled (tap Build Number 7 times in Settings)
#   - "USB Debugging" must be enabled in Developer Options
#
# Usage:
#   bash provision_tablet.sh
# =============================================================================

set -e

PACKAGE="com.remoteagent"
ADMIN_RECEIVER=".AdminReceiver"

echo "================================================================"
echo "  Drishti Tablet Provisioning"
echo "================================================================"
echo ""

# 1. Check ADB connection
echo "[1/4] Checking ADB connection..."
if ! adb devices | grep -q "device$"; then
  echo "ERROR: No device found via ADB. Check USB connection and USB Debugging is enabled."
  exit 1
fi
echo "  ✅ Device connected"

# 2. Check app is installed
echo "[2/4] Checking Drishti Agent is installed..."
if ! adb shell pm list packages | grep -q "$PACKAGE"; then
  echo "ERROR: Drishti Agent ($PACKAGE) is not installed on the device."
  echo "  Install the APK first: adb install app-debug.apk"
  exit 1
fi
echo "  ✅ App installed"

# 3. Check no accounts are signed in (required for Device Owner)
echo "[3/4] Checking no Google accounts are signed in..."
ACCOUNTS=$(adb shell dumpsys account | grep "Account {" | wc -l)
if [ "$ACCOUNTS" -gt 0 ]; then
  echo "ERROR: Device has $ACCOUNTS account(s) signed in."
  echo "  Device Owner can only be set before any accounts are added."
  echo "  Factory reset the tablet and try again before signing into any account."
  exit 1
fi
echo "  ✅ No accounts signed in"

# 4. Set Device Owner
echo "[4/4] Setting Device Owner..."
adb shell dpm set-device-owner "${PACKAGE}/${ADMIN_RECEIVER}"
echo "  ✅ Device Owner set successfully!"

echo ""
echo "================================================================"
echo "  Provisioning Complete!"
echo "================================================================"
echo ""
echo "  The Drishti Agent now has Device Owner privileges:"
echo "  - Screen sharing: no user consent dialog needed"
echo "  - Accessibility: can be granted silently"  
echo "  - ADB: can be enabled/disabled programmatically"
echo "  - All settings survive reboots automatically"
echo ""
echo "  You can now:"
echo "  1. Disconnect USB"
echo "  2. Mount the tablet in the vending machine"
echo "  3. The agent will connect to the backend automatically"
echo "================================================================"
