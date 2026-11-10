<div align="center">
  <h1>Naudio</h1>
  <p>A free, open-source YouTube Music client for Android and Desktop, built with Compose Multiplatform.</p>
  <p>
    <a href="https://github.com/crazynanda/Naudio_2/releases/latest"><img src="https://img.shields.io/github/v/release/crazynanda/Naudio_2" alt="Latest release"></a>
    <a href="https://github.com/crazynanda/Naudio_2/releases"><img src="https://img.shields.io/github/downloads/crazynanda/Naudio_2/total" alt="Total downloads"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/crazynanda/Naudio_2" alt="License: GPL-3.0"></a>
  </p>
  <p><strong><a href="https://github.com/crazynanda/Naudio_2/releases/latest">Download the latest Naudio release (2.3.3)</a></strong></p>
</div>

Naudio is an independently developed music streaming application based on [SimpMusic](https://github.com/maxrave-dev/SimpMusic), featuring its own branding, continuous architectural improvements, enhanced playback reliability, and unique user experience features.

---

## Highlights & Recent Enhancements

- **Swipe-to-Play-Next Everywhere**: Seamless gesture support across Search results (Songs and Videos), Playlists, Albums, Recent Songs, and queue views to instantly queue any track to play next.
- **Intelligent Recent Songs Engine**: Local, privacy-first playback history backed by AndroidX Room (Schema v28) with automatic deduplication, timestamp tracking, and instant playlist queueing.
- **Bulletproof Background Playback**: Engineered on AndroidX Media3 `MediaSessionService` and ExoPlayer with multi-tier power management (`WAKE_MODE_LOCAL`, gapless track-transition partial wake locks, and Wi-Fi streaming locks) to prevent playback cutoffs during extended screen-off listening.
- **Universal Duration & Stream Resolution**: Resilient streaming pipeline that gracefully handles live audio streams, podcasts, zero-duration tracks, and dynamic stream format resolution without UI stalls or playback failures.
- **Android Auto Integration**: Browse playlists, search library content, and control playback directly from your vehicle's head unit.

---

## Features

### Audio & Playback
- **Ad-Free Streaming**: Stream audio from YouTube Music and YouTube in high quality (up to 256 kbps Opus or AAC) with no advertisements.
- **Background Playback**: Continuous playback with screen off, lock screen controls, and Android system media notification integration.
- **Crossfade & Gapless Transitions**: Smooth track transitions with customizable crossfade duration.
- **Ten-Band Equalizer & AutoEq**: 10-band graphic equalizer with presets, AutoEq headphone frequency correction profiles, and custom Reverb/Delay effects.
- **Independent Audio Routing**: Route playback to specific Bluetooth devices, headphone jacks, or Cast targets without altering other applications' audio.
- **Sleep Timer**: Schedule automatic playback fade-out and stop.
- **SponsorBlock & Return YouTube Dislike**: Automatically skip non-music segments, sponsor intros, and intros; view community dislike metrics.

### User Interface & Customization
- **Three Now Playing Styles**: Choose between Classic, Material 3 Expressive, and Apple Music-inspired player layouts.
- **Dynamic Theming**: Light, Dark, Dynamic Material You (monet), and high-contrast OLED black themes.
- **Synchronized Lyrics**: Real-time synchronized lyrics from SimpMusic Lyrics and LRCLIB, word-by-word romanization across 12 languages, lyric timing offset, fullscreen landscape display, and shareable lyric cards.
- **Rich Visuals**: Optional Spotify Canvas loops and animated Apple Music artwork.
- **Home Screen Widgets**: Turntable widget, quick playlist launchers, and listening insight widgets.

### Discovery & Organization
- **Unified Search**: Search across songs, videos, albums, artists, community playlists, and featured content with quick filter chips.
- **Personal Library & YouTube Sync**: Create and organize local playlists with bi-directional YouTube Music library synchronization.
- **AI Listening Insights & Recap**: Naudio Wrapped listening recaps, monthly summaries, shareable listening receipts, and AI music taste analysis (\*).
- **Listen Together**: Synchronized multi-user listening rooms (compatible with Metrolist).
- **Discord Rich Presence & Last.fm**: Real-time listening status on Discord and automatic Last.fm scrobbling.

> (\*) Requires your own OpenAI or Gemini API key configured in Settings.<br>
> (\*\*) Listening history synchronization requires enabling the "Send back to Google" setting.

---

## Technical Specifications

| Component | Technology / Specification |
| :--- | :--- |
| **Product Version** | Version 2.3.3 (Version Code: 63) |
| **Application ID** | `com.maxrave.simpmusic` |
| **Official Distribution** | GitHub Releases (Direct APK) |
| **Language & Runtime** | Kotlin Multiplatform (KMP 2.4.20) / JVM 21 |
| **UI Framework** | Jetpack Compose Multiplatform (1.12.0) |
| **Audio Architecture** | AndroidX Media3 / ExoPlayer (1.11.1), `MediaSessionService` |
| **Database & Persistence** | AndroidX Room (2.8.5) with SQLite (Schema v28) |
| **Networking & Scraping** | Ktor Client, OkHttp, KotlinX Serialization |
| **Dependency Injection** | Koin |
| **Image Loading** | Coil 3 |
| **Android SDK Support** | Min SDK: 26 (Android 8.0 Oreo) · Target SDK: 36 · Compile SDK: 37 |
| **Binary Optimization** | R8 Minification + Android Resource Shrinking enabled |

---

## Project Architecture

Naudio follows Clean Architecture with unidirectional data flow and MVVM pattern in the presentation layer:

- `:androidApp` — Android platform entry point, `MainActivity`, `SimpleMediaService`, application configuration, and packaging.
- `:composeApp` — Shared Compose UI screens, navigation, components, and design system.
- `:core:common` — Common utilities, logging, extensions, and shared primitives.
- `:core:domain` — Business logic, use cases, domain models, and repository interfaces.
- `:core:data` — Room database implementation, local cache, repositories, and data sources.
- `:core:media:media3` — Media3 audio service, ExoPlayer management, wake locks, and playback delegates.
- `:core:service:kotlinYtmusicScraper` — YouTube Music API client and data extractor.

<p align="center">
  <img src="asset/dependencies_graph.svg" width="800" alt="Dependency graph">
</p>

---

## Installation

GitHub Releases is the official and primary distribution channel for Naudio Android APKs. Naudio is not published on the Google Play Store.

### 1. Download the APK

Navigate to the **[Latest Naudio Release](https://github.com/crazynanda/Naudio_2/releases/latest)** and download the APK suitable for your device:

- **Universal APK (`Naudio-2.3.3.apk` / `Naudio-2.3.3-universal.apk`)** [~54 MB]:
  *Recommended for most users.* Includes native audio binaries for all supported device architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`) and will run on any device running Android 8.0 or newer.
- **ARM64 APK (`Naudio-2.3.3-arm64-v8a.apk`)** [~28 MB]:
  Optimized lightweight package designed for all modern 64-bit Android smartphones and tablets.
- **ARMv7 APK (`Naudio-2.3.3-armeabi-v7a.apk`)** [~28 MB]:
  Designed for older 32-bit Android phones and legacy hardware.
- **x86_64 APK (`Naudio-2.3.3-x86_64.apk`)** [~29 MB]:
  Designed for Android emulators, ChromeOS devices, and Intel/AMD-based Android hardware.

### 2. Install on Android

1. Open your browser's **Downloads** or your device's **Files** app and tap the downloaded `Naudio-2.3.3.apk` file.
2. If Android prompts **"For your security, your phone is not allowed to install unknown apps from this source"**:
   - Tap **Settings** in the prompt dialog.
   - Toggle on **Allow from this source** (or **Install unknown apps**) for your browser or file manager.
   - Tap the back button to return to the installation screen.
3. Tap **Install** (or **Update** if updating an existing installation).
4. Launch Naudio from your app drawer.

### 3. Sideloading via ADB

Connect your Android device with USB debugging enabled, then run:

```bash
adb install -r Naudio-2.3.3.apk
```

---

## Windows Installation

Naudio for Windows is distributed as a modern 64-bit application package (MSIX) with offline installation tooling:

### Option A: Standalone Setup (Recommended)
Download **`Naudio-Setup-x64.exe`** from the [Latest Release](https://github.com/crazynanda/Naudio_2/releases/latest):
1. Run `Naudio-Setup-x64.exe`.
2. If Windows SmartScreen displays *"Windows protected your PC"* (common for open-source software without an EV certificate), click **More info** &rarr; **Run anyway**.
3. Accept the Administrator prompt so the installer can import the application signing certificate into `LocalMachine\TrustedPeople`.
4. The wizard will automatically install or update Naudio in place (preserving all your existing favorites, database, and settings) and launch the app.

### Option B: Offline Installer ZIP (Fallback)
Download **`Naudio-Windows-Installer.zip`** from the [Latest Release](https://github.com/crazynanda/Naudio_2/releases/latest):
1. Extract the ZIP archive to a folder.
2. Double-click `install.bat`. UAC will request elevation to import the bundled certificate into `LocalMachine\TrustedPeople` and run `Add-AppxPackage`.
3. To uninstall: Go to **Windows Settings &rarr; Apps &rarr; Installed apps &rarr; Naudio &rarr; Uninstall**.

---

## Building from Source

### Prerequisites

- **Java Development Kit (JDK)**: Version 21 (Eclipse Temurin, OpenJDK, or Amazon Corretto recommended).
- **Android SDK**: Build Tools 35.0.0+, Android SDK Platform 36/37.
- **Git**

### Build Commands

Clone the repository:

```bash
git clone https://github.com/crazynanda/Naudio_2.git
cd Naudio_2
```

#### 1. Build Debug APK

```bash
./gradlew :androidApp:assembleDebug
```

Output location: `androidApp/build/outputs/apk/debug/androidApp-universal-debug.apk`

#### 2. Build Production Release APKs

```bash
./gradlew :androidApp:assembleRelease
```

This generates:
- Universal APK: `androidApp/build/outputs/apk/release/androidApp-universal-release-unsigned.apk`
- ABI Splits:
  - `androidApp/build/outputs/apk/release/androidApp-arm64-v8a-release-unsigned.apk`
  - `androidApp/build/outputs/apk/release/androidApp-armeabi-v7a-release-unsigned.apk`
  - `androidApp/build/outputs/apk/release/androidApp-x86_64-release-unsigned.apk`

#### 3. Build Production Android App Bundle (AAB)

```bash
./gradlew :androidApp:bundleRelease
```

Output location: `androidApp/build/outputs/bundle/release/androidApp-release.aab`

#### 4. Run Automated Tests

```bash
./gradlew :domain:jvmTest :data:jvmTest :kotlinYtmusicScraper:jvmTest
```

---

## Signing Release Builds

Local release builds produce unsigned artifacts by default. To sign an unsigned release APK for distribution:

1. **Align the APK** (if needed):
   ```bash
   zipalign -v -p 4 input-unsigned.apk aligned.apk
   ```

2. **Sign with your release key using `apksigner`**:
   ```bash
   apksigner sign --ks your-release-key.jks \
                  --ks-key-alias your-key-alias \
                  --out Naudio-signed.apk \
                  aligned.apk
   ```

3. **Verify the signature**:
   ```bash
   apksigner verify --verbose Naudio-signed.apk
   ```

*Note: In continuous integration (CI) workflows, release artifacts are automatically signed using repository secrets (`simpmusic.jks`).*

---

## Privacy Policy

- **No Tracking or Spyware**: Naudio does not collect, sell, or monetize user data.
- **FOSS vs. Full Variants**:
  - The **Full** build includes opt-in [Sentry](https://sentry.io/) crash reporting to diagnose playback exceptions and crashes.
  - The **FOSS** build omits Sentry entirely.
- **YouTube Account Integration**: If you choose to log in and enable "Send back to Google", listening history and interactions are sent directly to YouTube's endpoints via the official YouTube Music API format to keep your personal recommendations accurate.

---

## Data and Credits

- Naudio uses YouTube Music's internal API to query and stream music.
- Spotify Canvas and lyrics integration utilize the Spotify Web API.
- [InnerTune](https://github.com/z-huang/InnerTune/) inspired YouTube Music data extraction patterns.
- [SmartTube](https://github.com/yuliskov/SmartTube) informed audio and video streaming URL extraction.
- [SponsorBlock](https://sponsor.ajay.app/) powers automatic segment skipping.
- [Return YouTube Dislike](https://returnyoutubedislike.com/) provides dislike counts.
- Primary lyrics are provided by SimpMusic Lyrics; fallback synchronized lyrics come from [LRCLIB](https://lrclib.net/).
- [SimpMusic Chart](https://chart.simpmusic.org/) provides chart trending data.
- Translations and baseline UI assets are inherited from the SimpMusic project.

---

## Contributing

Contributions, bug reports, and suggestions are welcome!

1. Please read [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before opening a pull request.
2. Check existing [GitHub Issues](https://github.com/crazynanda/Naudio_2/issues) before submitting new reports.
3. Follow the established Clean Architecture guidelines and run unit tests prior to opening PRs.

---

## License and Attribution

Naudio is licensed under the [GNU General Public License v3.0](LICENSE).

It is based on [SimpMusic](https://github.com/maxrave-dev/SimpMusic), also licensed under GPL-3.0, created by [maxrave-dev](https://github.com/maxrave-dev) (Tuan Minh Nguyen Duc). Original copyright and attribution remain with the SimpMusic authors and contributors.

*Naudio is an independent fork and is not affiliated with or endorsed by Google, YouTube, or the original SimpMusic maintainers.*

---

## Legal Disclaimer and Terms of Use

### 1. 100% Free, Open-Source and Strictly Non-Commercial
Naudio is a fully open-source project (FOSS) created purely for educational purposes and personal use. **We do not sell this application, nor do we monetize it in any way.** There are no advertisements, no premium features, no subscriptions, and no hidden fees within the app. This project has no commercial value or financial intent.

### 2. A Custom Browser with Content Filtering
Naudio acts strictly as a specialized, third-party client. It parses publicly available website content and APIs of YouTube and YouTube Music, rendering them in a custom user interface. The ad-free experience it provides is functionally equivalent to using a standard web browser equipped with common content-filtering extensions.

### 3. Support Content Creators
We deeply respect the hard work of artists, musicians, and content creators. **We strongly encourage all users to subscribe to [YouTube Premium](https://www.youtube.com/premium).** Purchasing a Premium subscription is the best way to financially support creators and ensure the platform continues to thrive.

### 4. No Hosting of Copyrighted Material
We do not host, upload, distribute, or store any audio, video, or copyrighted media files on our own servers. All content accessed through this application is stored entirely on Google's and YouTube's servers and remains the property of their respective copyright owners. The application merely acts as a conduit to stream publicly accessible links.

### 5. User Responsibility and Contact
The software is provided "AS IS", without warranty of any kind. Users are solely responsible for ensuring their usage complies with local laws and the Terms of Service of platforms they access.

Because we do not host any media files, we cannot process DMCA takedown requests for audio or video content. However, if you represent a copyright holder or have legal concerns regarding the open-source code itself, please contact: **nandablr242@gmail.com**.
