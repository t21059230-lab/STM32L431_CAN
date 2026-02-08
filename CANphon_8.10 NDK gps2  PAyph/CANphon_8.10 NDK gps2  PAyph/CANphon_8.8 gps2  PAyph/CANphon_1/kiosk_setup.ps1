# =============================================
# CANphon Kiosk Mode Setup Script
# سكربت إعداد وضع Kiosk لـ CANphon
# =============================================

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  CANphon Kiosk Mode Setup" -ForegroundColor Cyan
Write-Host "  إعداد وضع Kiosk لـ CANphon" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# التحقق من اتصال الهاتف
Write-Host "[1/6] Checking device connection..." -ForegroundColor Yellow
$devices = .\adb devices
if ($devices -notmatch "device$") {
    Write-Host "ERROR: No device connected! / لا يوجد جهاز متصل!" -ForegroundColor Red
    exit 1
}
Write-Host "Device connected! / الجهاز متصل!" -ForegroundColor Green

# تثبيت التطبيق (اختياري)
Write-Host ""
Write-Host "[2/6] Installing CANphon (if APK exists)..." -ForegroundColor Yellow
if (Test-Path ".\CANphon.apk") {
    .\adb install -r .\CANphon.apk
    Write-Host "CANphon installed! / تم تثبيت CANphon!" -ForegroundColor Green
} else {
    Write-Host "No APK found, assuming already installed / لا يوجد APK، التطبيق مثبت مسبقاً" -ForegroundColor Gray
}

# منح الصلاحيات
Write-Host ""
Write-Host "[3/6] Granting permissions... / منح الصلاحيات..." -ForegroundColor Yellow
.\adb shell "su -c 'pm grant com.example.canphon android.permission.CAMERA'"
.\adb shell "su -c 'pm grant com.example.canphon android.permission.ACCESS_FINE_LOCATION'"
.\adb shell "su -c 'pm grant com.example.canphon android.permission.ACCESS_COARSE_LOCATION'"
.\adb shell "su -c 'pm grant com.example.canphon android.permission.RECORD_AUDIO'"
.\adb shell "su -c 'pm grant com.example.canphon android.permission.BODY_SENSORS'"
Write-Host "Permissions granted! / تم منح الصلاحيات!" -ForegroundColor Green

# تحسين النظام
Write-Host ""
Write-Host "[4/6] Optimizing system... / تحسين النظام..." -ForegroundColor Yellow
.\adb shell "su -c 'dumpsys deviceidle disable'"
.\adb shell "su -c 'dumpsys deviceidle whitelist +com.example.canphon'"
.\adb shell "su -c 'cmd appops set com.example.canphon RUN_IN_BACKGROUND allow'"
Write-Host "System optimized! / تم تحسين النظام!" -ForegroundColor Green

# تفعيل الشاشة الكاملة
Write-Host ""
Write-Host "[5/6] Enabling fullscreen mode... / تفعيل الشاشة الكاملة..." -ForegroundColor Yellow
.\adb shell "su -c 'settings put global policy_control immersive.full=*'"
Write-Host "Fullscreen enabled! / تم تفعيل الشاشة الكاملة!" -ForegroundColor Green

# تعطيل Launcher
Write-Host ""
Write-Host "[6/6] Disabling launcher... / تعطيل الواجهة..." -ForegroundColor Yellow
.\adb shell "su -c 'pm disable-user --user 0 com.android.launcher'"
Write-Host "Launcher disabled! / تم تعطيل الواجهة!" -ForegroundColor Green

# إعادة التشغيل
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Setup complete! Rebooting..." -ForegroundColor Cyan
Write-Host "  اكتمل الإعداد! جاري إعادة التشغيل..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
.\adb reboot

Write-Host ""
Write-Host "Done! The phone will boot into CANphon only." -ForegroundColor Green
Write-Host "تم! الهاتف سيقلع على CANphon فقط." -ForegroundColor Green
