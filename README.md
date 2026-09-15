<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="130" alt="ParsV2R">

# ParsV2R

کلاینت V2Ray برای اندروید، با هسته‌ی Aether

[دانلود آخرین نسخه](https://github.com/Amirmahdavi2022/parsv2r/releases/latest) · [کانال تلگرام](https://t.me/parsv2r)

</div>

<div dir="rtl">

## این چیه؟

ParsV2R یه اپ برای وصل شدن با کانفیگ‌های V2Ray و Xray روی اندرویده. پایه‌اش v2rayNG هست، پس اگه قبلا با اون کار کردی اینجا هم همه چی سر جای خودشه و چیزی رو از نو لازم نیست یاد بگیری.

فرق اصلیش اینه که یه راه اتصال داره که اصلا کانفیگ نمی‌خواد. اسمش Aether هست و خودش میره یه سرور سالم کلادفلر پیدا میکنه و وصل میشه. یعنی اگه همه‌ی کانفیگات از کار افتادن هنوز یه راه داری.

## چی داره

هر چی v2rayNG داره اینم داره. ساب‌اسکریپشن، پروکسی برای هر اپ جدا، قوانین مسیریابی، تست پینگ و Real Delay، زنجیره‌ی پروکسی، اسکن QR، بکاپ و ریستور، و همه‌ی پروتکل‌ها مثل VLESS و VMess و Trojan و Shadowsocks و WireGuard و Hysteria2.

چیزهایی که بهش اضافه شده:

- **هسته‌ی Aether** برای اتصال بدون سرور و بدون کانفیگ
- **هسته‌ی Xray پچ‌شده** که به کانفیگ‌های VLESS و Trojan بدون رمزنگاری روی آدرس‌های عمومی هم وصل میشه
- **گزینه‌های cipherSuites و فینگرپرینت unsafe** هم تو تنظیمات هم تو لینک‌های اشتراکی
- **مسیریابی آماده برای ایران** که سایت‌های داخلی رو مستقیم میفرسته
- **ظاهر جدید** با تم تیره و طلایی، کارت برای هر سرور و یه دکمه‌ی اتصال بزرگ پایین صفحه
- **فارسی کامل**

## ساخت کانفیگ Aether

این کار کمتر از یه دقیقه طول میکشه:

1. تو صفحه‌ی اصلی روی + بزن
2. آخرین گزینه یعنی «افزودن دستی کانفیگ Aether» رو انتخاب کن
3. تنظیمات رو همونطور که هست بذار و «اسکن برای یافتن سرور» رو بزن
4. اگه خواستی یه اسم براش بنویس، این کار رو وسط اسکن هم میشه کرد
5. صبر کن تا اسکن تموم بشه و نشانی و پورت خودشون پر بشن، معمولا یکی دو دقیقه
6. تیک بالای صفحه رو بزن تا ذخیره بشه و بعد دکمه‌ی اتصال رو بزن

<div align="center">
<img src="docs/aether-guide.gif" width="300" alt="آموزش ساخت کانفیگ Aether">
</div>

یه چیزی رو هم بدون. Aether از نزدیک‌ترین سرور کلادفلر میره بیرون، واسه همین بعضی سایت‌هایی که ایران رو تحریم کردن ممکنه باهاش باز نشن. برای اونا از کانفیگ معمولی استفاده کن.

## دانلود و نصب

همه‌ی نسخه‌ها تو صفحه‌ی [Releases](https://github.com/Amirmahdavi2022/parsv2r/releases/latest) هستن.

| فایل | برای کدوم گوشی |
|---|---|
| `universal` | همه‌ی گوشی‌ها، اگه مطمئن نیستی همینو بگیر |
| `arm64-v8a` | بیشتر گوشی‌های جدید، حجمش کمتره |
| `armeabi-v7a` | گوشی‌های قدیمی‌تر |

فایل‌هایی که تو اسمشون `fdroid` هست رو لازم نداری.

موقع نصب ممکنه Play Protect بگه این برنامه رو نمیشناسه. دلیلش فقط اینه که اپ از گوگل پلی نیومده. بزن نصب در هر صورت.

اگه قبلا نسخه‌ی خیلی قدیمی ParsV2R رو داشتی و موقع نصب گفت سازگار نیست، اول اون رو پاک کن و بعد نصب کن.

## سوال یا مشکل

تو [کانال تلگرام](https://t.me/parsv2r) آپدیت‌ها رو میذارم. اگه به مشکلی خوردی یه [Issue](https://github.com/Amirmahdavi2022/parsv2r/issues) باز کن و اگه میتونی اسکرین‌شات لاگ رو هم بذار.

اگه اپ به کارت اومد یه ستاره به ریپو بده، خیلی کمک میکنه که بیشتر دیده بشه.

## دمشون گرم

این اپ بدون کار این بچه‌ها ساخته نمیشد:

- [v2rayNG](https://github.com/2dust/v2rayNG) از 2dust که کل پایه‌ی اپه
- [PattNG](https://github.com/patterniha/PattNG) از patterniha که Aether و پچ‌های Xray رو اضافه کرده بود
- [Aether](https://github.com/CluvexStudio/aether) از CluvexStudio
- [Xray-core](https://github.com/XTLS/Xray-core) و [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)

جزئیات بیشتر تو [NOTICE.md](NOTICE.md) هست. لایسنس GPL-3.0، مثل خود v2rayNG.

</div>

---

**English:** ParsV2R is an Android V2Ray/Xray client based on v2rayNG, with the Aether (WARP/MASQUE) core from PattNG built in, so you can connect without any config. Grab the `universal` APK from [Releases](https://github.com/Amirmahdavi2022/parsv2r/releases/latest). GPL-3.0.
