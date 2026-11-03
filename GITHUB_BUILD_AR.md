# Senator Captain Pro — Flat GitHub Build

## رفع الملفات
ارفع جميع الملفات الموجودة في هذا المجلد إلى جذر مستودع GitHub مع الاحتفاظ بمسار:
`.github/workflows/build.yml`

## البناء
1. افتح المستودع في GitHub.
2. اختر تبويب Actions.
3. افتح `Build Senator Captain Pro APK`.
4. اضغط `Run workflow` إذا لم يبدأ تلقائياً بعد رفع الملفات.
5. انتظر نجاح المهمة.
6. من أسفل صفحة التشغيل حمّل Artifact باسم `SenatorCaptainPro-debug-APK`.
7. فك ضغط Artifact لتحصل على `app-debug.apk` ثم ثبّته على الهاتف.

الـ workflow ينشئ هيكلية Android داخلياً من الملفات المسطحة، ثم ينفذ `gradle assembleDebug`.

ملاحظة: هذه نسخة Debug للتجربة. يلزم اختبارها على موديل الرسيفر الفعلي؛ نجاح إرسال TCP لا يؤكد أن الرسيفر نفّذ كل كود زر.
