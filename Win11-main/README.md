# Win11

Windows 11 স্টাইলের Android লঞ্চার (HOME অ্যাপ), এখন Parallel clone engine সহ।

## Clone (Parallel) ফিচার
- লঞ্চারেই বসানো, আলাদা অ্যাপ নেই। Start → All apps (বা Pinned) এ অ্যাপে **long-press** করলে মেনু আসে।
- **bKash** (`com.bKash.customerapp`), **Nagad** (`com.konasl.nagad`), **WhatsApp** (`com.whatsapp`) ছাড়া বাকি যেকোনো অ্যাপ clone করা যায় (এই তিনটা virtualization-এ ঠিকমতো কাজ করে না বলে বাদ)।
- মেনু: `Clone app` (আসল অ্যাপে), `Clear clone data` / `Remove clone` (clone এ)।
- Clone তালিকায় আসে "WhatsApp (Clone)" নামে, pin/hide করা যায়, search-এও পাওয়া যায়।

## Default hide
- **PalmPay** প্রথমবার দেখা গেলে নিজে থেকে hidden হয় (All apps → Hidden apps এ পাওয়া যাবে)। unhide করলে আর জোর করে hide হয় না।

## Wallpaper
- Desktop-এ ডিভাইসের **বর্তমান ওয়ালপেপার** দেখায় (আলাদা wallpaper আঁকা হয় না)। Status/navigation bar এলাকা গাঢ় থাকে।

## Build
- JDK 21, Android SDK 37, NDK `29.0.13846066`, AGP 8.13.2, Gradle 8.14.3, Kotlin 1.9.23।
- GitHub Actions: **Android Build** (debug) বা **Android Release Build (Signed)**।
- `targetSdk = 37`।

## মনে রাখার বিষয়
- Android 11+ এ clone-এর ফাইল কাজের জন্য "সব ফাইলে অ্যাক্সেস" লাগে।
- Play Integrity / ব্যাংকিং অ্যাপ clone-এ ঠিকমতো না-ও চলতে পারে।
- Engine-এর বিস্তারিত: `NOTICE-Engine.md`, লাইসেন্স: `LICENSE-BlackBox`।

## Messages (SMS)
- নতুন UI: `SmsUi.kt` — Win11 Fluent স্টাইল, RecyclerView (শুধু দেখা যাওয়া row আঁকা হয়), SMS/Contacts query ব্যাকগ্রাউন্ড থ্রেডে, contact name cache, লাইভ আপডেট (ContentObserver)।
- চ্যাট bubble এখন স্ক্রিনের ৮২% (wide স্ক্রিনে ৬২%) পর্যন্ত চওড়া হয় — আগের "এক শব্দ প্রতি লাইন" সমস্যা নেই।

## Taskbar
- Windows 11 স্টাইল: মাঝখানে আইকন, নতুন Win11 লোগো, active pill, ডানে Wi-Fi / Volume / Battery (আসল লেভেল) + সময় ও তারিখ।
