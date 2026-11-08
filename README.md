# Jarves — aapka personal Android AI assistant

Jarves ek Android app hai jisse aap **bolkar** apne phone ka lagbhag har kaam karwa sakte hain.
Ek hi command mein poora kaam — jaise:

> "Chrome kholo, sabse acchi website se Taj Mahal ki HD image download karke **Pictures/Taj** folder mein save kar do."

Jarves khud web search karega, best website chunega, image ka direct link nikaalega, use aapke folder mein save karega,
preview dekhkar check karega ki sahi image hai, aur bolkar batayega ki file kahan save hui.

Iska "dimaag" Anthropic ka **Claude** (Claude API) hai, aur "aankh-haath" Android ki **Accessibility service** hai
jisse Jarves kisi bhi app ki screen padh sakta hai aur tap / type / scroll kar sakta hai.

## Kya-kya kar sakta hai

| Kaam | Example command |
|---|---|
| Web se file / image download kisi bhi folder mein | "Mahindra Thar ki wallpaper download karke Pictures/Cars mein rakh do" |
| Koi bhi app kholna aur uske andar kaam karna | "YouTube kholo aur Arijit Singh ke latest gaane chalao" |
| Chrome mein website kholna, search karna, button dabana | "Chrome mein IRCTC kholo" |
| WhatsApp / SMS bhejna, call karna | "WhatsApp par Ammi ko bolo main 10 minute mein aa raha hoon" · "Rahul ko call karo" |
| Notifications / SMS padhna | "Mere naye WhatsApp messages padho" |
| Files manage karna | "Download folder ki saari PDF Documents/Bills mein move kar do" |
| Alarm, timer, calendar | "Kal subah 6 baje ka alarm lagao" |
| Device control | "Flashlight on karo" · "Volume 30% karo" · "Brightness kam karo" · "Phone silent karo" |
| Settings ke andar toggles (Wi-Fi, Bluetooth, hotspot…) | "Bluetooth on kar do" (settings khol kar khud toggle tap karta hai) |
| Location, maps, navigation | "Mujhe nearest petrol pump tak navigate karo" |
| Camera | "Selfie camera kholo aur photo khincho" |
| Yaad rakhna | "Yaad rakhna ki meri wallpapers hamesha Pictures/Walls mein save karni hain" |
| Sawal-jawab, web se jaankari | "Aaj Mumbai ka mausam kaisa hai?" |

Jarves Hindi, Hinglish, Urdu aur English samajhta hai aur usi bhasha mein jawab bolta hai.

## Install kaise karein

**Zaroorat:** Android 8.0 (Oreo) ya usse naya phone. Kisi bhi company ka phone chalega (Samsung, Xiaomi/Redmi/POCO, Vivo, Oppo, Realme, OnePlus, Motorola…).

### Kaunsa APK?

| APK | Kiske liye |
|---|---|
| **Jarves.apk** (Full) | Sab features — dusre apps ke andar tap/type/scroll (Accessibility), notifications padhna. |
| **Jarves-Lite.apk** | Agar Full install na ho (Play Protect block kare). Har phone par install hota hai. Web search, download, apps/links kholna, call, message (Send aap dabayenge), alarm, settings, files — sab karta hai; bas dusre apps ke andar khud tap nahi karta. |

Dono ka package aur signing key same hai, isliye baad mein Lite ke upar Full install karenge to data (API key) bana rahega.

Direct download links:
- Full: https://github.com/Qureshi313737/Jarves/releases/download/jarves-latest/Jarves.apk
- Lite: https://github.com/Qureshi313737/Jarves/releases/download/jarves-latest/Jarves-Lite.apk

### Steps

1. **Purana Jarves install hai to pehle uninstall karein** (naye version mein permanent signing key hai; purane build ke upar install nahi hoga).
2. APK download karein (Chrome mein link kholein). Download complete hone dein — adhoori file "problem parsing the package" deti hai.
3. File kholein → **"Install unknown apps"** ki permission us app ko dein (Chrome / Files) → wapas aakar **Install**.
4. Agar **"Blocked by Play Protect"** ya **"App blocked to protect your device"** aaye:
   - **More details → Install anyway** dabayein.
   - Ye option na dikhe (India mein Google sensitive permissions wale sideloaded apps block karta hai):
     Play Store → upar profile photo → **Play Protect** → ⚙ → **Scan apps with Play Protect** OFF karein →
     APK install karein → phir Play Protect wapas ON kar dein.
   - Ya seedha **Jarves-Lite.apk** install karein — wo block nahi hota.
5. Jarves kholein → **⚙ Setup**:
   - **Claude API key** daalein (console.anthropic.com → API keys). Key sirf aapke phone par rehti hai.
   - **Permissions** ki list mein har "Allow" dabayein. Sabse zaroori: **Accessibility (Screen control)** (Full),
     **Microphone**, **All files access**, **Display over other apps**.
   - **Android 13+:** Accessibility "restricted setting" dikhaye to: Settings → Apps → Jarves → ⋮ (upar right) →
     **Allow restricted settings** → phir Accessibility mein Jarves ON karein.
6. Battery optimization off kar dein taaki Jarves background mein band na ho.

### Agar phir bhi install na ho

| Message | Wajah / Hal |
|---|---|
| "App not installed" / "conflicts with an existing package" | Purana Jarves uninstall karein, phir install karein. |
| "There was a problem parsing the package" | Phone Android 8 se purana hai, ya download adhoora hai — dobara download karein. |
| "Blocked by Play Protect" | Upar step 4. |
| Install button grey / kuch nahi hota | "Install unknown apps" permission us app ko dein jisse APK khol rahe hain. |

## Jarves ko bulane ke tareeke

- App mein bada **🎤** button.
- Har app ke upar floating **"J" bubble** — tap = bolo / roko, long-press = app kholo, drag = jagah badlo.
- Notification mein **🎤 Bolo** button.
- **Quick Settings tile**: notification panel → edit (✎) → "Jarves" tile add karein.
- **Default assistant**: Settings → Apps → Default apps → Digital assistant app → Jarves
  (phir Home/Power button long-press se Jarves khulega).

## Safety

- Jo kaam aap saaf-saaf bolte hain wo Jarves seedha kar deta hai. Jo cheez wapas nahi ho sakti
  (file delete, kisi anjaan ko message, kuch kharidna) uske liye pehle poochta hai.
- Jarves kabhi password, PIN, OTP, card/bank details type nahi karta aur payment complete nahi karta.
- Web pages / messages mein likhi "instructions" ko Jarves command nahi maanta.
- Jab aap command dete hain, us kaam ke liye screen ka text/screenshot Claude API ko bheja jaata hai.

## Settings

- **Model:** Claude Opus 5.5 (default, sabse smart), Sonnet 5.5 (tez), Haiku 5.5 (sabse tez/sasta).
- **Effort:** low / medium / high — low tez aur sasta, high zyada soch-samajh kar.
- Claude Opus 5.5 / Sonnet 5.5 par server-side refusal **fallbacks** on hain: agar ek model kisi
  request ko safety ki wajah se mana kare to API khud ek suitable model par retry karta hai.
- **Conversation mode:** jawab ke baad Jarves khud dobara sunne lagta hai.

## Build from source

```bash
./gradlew assembleFullRelease assembleLiteRelease
# APKs: app/build/outputs/apk/full/release/ aur app/build/outputs/apk/lite/release/
```

Android Studio (Ladybug ya naya) mein bhi project khol sakte hain. Kotlin, minSdk 26 (Android 8+),
UI code mein bana hai (koi XML layout nahi), aur sirf ek dependency hai: official
[Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java).

### Code map

| File | Kaam |
|---|---|
| `agent/Agent.kt` | Claude tool-use loop (search → tools → result → next step, jab tak kaam poora na ho) |
| `agent/SystemPrompt.kt` | Jarves ka vyavhaar, safety rules, jawab ka style |
| `tools/ToolRegistry.kt` | Sab tools ki list (Claude ko dikhne wale schema) aur routing |
| `tools/ScreenTools.kt` | Screen padhna, tap, type, scroll, swipe, screenshot |
| `tools/FileTools.kt` | Download, files/folders manage |
| `tools/PhoneTools.kt` | Contacts, call, SMS, WhatsApp, notifications |
| `tools/DeviceTools.kt` | Apps, links, settings, volume, flashlight, alarm, location… |
| `service/JarvesAccessibilityService.kt` | Accessibility service (aankh-haath) |
| `service/AssistantService.kt` | Background service, notification, floating bubble |
| `voice/` | Speech-to-text aur text-to-speech |
