# =============================================
# CANphon Kiosk Mode REVERT Script
# سكربت إلغاء وضع Kiosk
# =============================================

Write-Host "========================================" -ForegroundColor Yellow
Write-Host "  CANphon Kiosk Mode REVERT" -ForegroundColor Yellow
Write-Host "  إلغاء وضع Kiosk" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow
Write-Host ""

# التحقق من اتصال الهاتف
Write-Host "[1/4] Checking device connection..." -ForegroundColor Yellow
$devices = .\adb devices
if ($devices -notmatch "device$") {
    Write-Host "ERROR: No device connected! / لا يوجد جهاز متصل!" -ForegroundColor Red
    exit 1
}
Write-Host "Device connected! / الجهاز متصل!" -ForegroundColor Green

# إيقاف سكربت Kiosk
Write-Host ""
Write-Host "[2/4] Stopping kiosk script... / إيقاف سكربت Kiosk..." -ForegroundColor Yellow
.\adb shell "su -c 'pkill -f kiosk.sh'"
.\adb shell "su -c 'rm -f /data/adb/service.d/kiosk.sh'"
Write-Host "Kiosk script stopped! / تم إيقاف السكربت!" -ForegroundColor Green

# إعادة تفعيل Launcher
Write-Host ""
Write-Host "[3/4] Re-enabling launcher... / إعادة تفعيل الواجهة..." -ForegroundColor Yellow
.\adb shell "su -c 'pm enable com.android.launcher'"
.\adb shell "su -c 'settings put global policy_control null'"
.\adb shell "su -c 'wm overscan 0,0,0,0'"
Write-Host "Launcher enabled! / تم تفعيل الواجهة!" -ForegroundColor Green

# إعادة التشغيل
Write-Host ""
Write-Host "[4/4] Rebooting... / إعادة التشغيل..." -ForegroundColor Yellow
.\adb reboot

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Kiosk Mode REVERTED!" -ForegroundColor Green
Write-Host "  تم إلغاء وضع Kiosk!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "The phone will boot normally now." -ForegroundColor Green
Write-Host "الهاتف سيقلع بشكل طبيعي الآن." -ForegroundColor Green
