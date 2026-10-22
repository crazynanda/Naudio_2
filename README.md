<div align="center">
  <h1>Naudio</h1>
  <p>A free, open-source YouTube Music client for Android and Desktop, built with Compose Multiplatform.</p>
  <p>
    <a href="https://github.com/crazynanda/Naudio_2/releases/latest"><img src="https://img.shields.io/github/v/release/crazynanda/Naudio_2" alt="Latest release"></a>
    <a href="https://github.com/crazynanda/Naudio_2/releases"><img src="https://img.shields.io/github/downloads/crazynanda/Naudio_2/total" alt="Total downloads"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/crazynanda/Naudio_2" alt="License: GPL-3.0"></a>
  </p>
  <p><strong><a href="https://github.com/crazynanda/Naudio_2/releases/latest">Download the latest Naudio release (2.3.1)</a></strong></p>
</div>

Naudio is an independently developed project based on [SimpMusic](https://github.com/maxrave-dev/SimpMusic), with its own branding, features, and ongoing development.

## Features

- Play music from YouTube Music or YouTube for free, without ads and in the background
- Three Now Playing styles: Classic, Material 3 Expressive, and an Apple Music-inspired player
- Apple Music-style word-by-word lyrics, romanization for 12 languages, lyric sharing, landscape fullscreen lyrics, and a timing offset
- Ten-band equalizer with presets and AutoEq headphone profiles, plus Delay and Reverb effects; Android also supports the system equalizer
- Choose the speaker, headphones, or Cast device Naudio uses without moving other apps' audio
- Naudio Wrapped, monthly recap playlists, and a shareable listening receipt
- On-device listening analytics, charts, period history, and a listening clock
- Home screen widgets (Android): turntable, playlists, and listening insights
- Send sign-ins from Android to Desktop by scanning a QR code
- Preferred audio language for videos with multiple audio tracks
- Up to 256 kbps audio (Opus or AAC) for YouTube Music Premium users
- Browse Home, Charts, Podcasts, Moods & Genres using YouTube Music data
- Search YouTube from the app
- Spotify Canvas and Apple Music animated artwork (opt-in)
- SimpMusic Chart integration
- 1080p video playback with subtitles
- Play YouTube Music live stations
- AI song suggestions and an AI reading of your music taste
- Import playlists converted from Spotify and other apps
- Create and manage local playlists, synced with YouTube Music
- Notifications from followed artists
- Caching and offline playback (Android)
- Crossfade and DJ-style transitions
- Light, dark, dynamic color, and other themes
- Synced lyrics from SimpMusic Lyrics, LRCLIB, Spotify (login required), and YouTube transcripts, with AI translation (BETA) (\*)
- Personalize data (\*\*) and multi-YouTube-account support
- Last.fm scrobbling (Full build)
- SponsorBlock and Return YouTube Dislike support
- Sleep timer
- Android Auto support with online content
- Discord Rich Presence
- Listen Together: synchronized shared rooms, compatible with Metrolist
- And more

> (\*) Requires your own OpenAI or Gemini API key.
> (\*\*) For users who enable the "Send back to Google" feature.

> **Note**
> Naudio is a personal fork in active development. Because playback depends on YouTube Music, errors can happen when YouTube changes something. Please report reproducible issues on GitHub.

## Install

### Android

1. Open the [latest release](https://github.com/crazynanda/Naudio_2/releases/latest).
2. Download `Naudio-2.3.1.apk`.
3. Open the APK and allow installation from your browser or file manager when prompted.

Naudio requires Android 8.0 (API 26) or newer. The universal APK includes `arm64-v8a`, `armeabi-v7a`, and `x86_64` libraries. The current release does not include a separate vivo/Origin Island APK.

## Data and credits

- Naudio uses YouTube Music's internal API to read data from YouTube Music.
- Spotify Canvas and lyrics use the Spotify Web API.
- [InnerTune](https://github.com/z-huang/InnerTune/) inspired the YouTube Music data extraction.
- [SmartTube](https://github.com/yuliskov/SmartTube) informed streaming URL extraction.
- [SponsorBlock](https://sponsor.ajay.app/) is used to skip sponsor segments.
- [Return YouTube Dislike](https://returnyoutubedislike.com/) provides vote data.
- Main lyrics data comes from SimpMusic Lyrics; alternative lyrics come from [LRCLIB](https://lrclib.net/).
- [SimpMusic Chart](https://chart.simpmusic.org/) provides chart data.
- [Sentry](https://sentry.io/) provides crash reporting in the Full build.
- Translations are inherited from the SimpMusic project.

## Privacy

The FOSS build contains no analytics or crash-reporting SDK for collecting user data. If a signed-in user enables "Send back to Google", Naudio sends listening history and video listening records to Google through the YouTube Music tracking API so recommendations and creator support work (API reference: [Ytmusic.kt](core/service/kotlinYtmusicScraper/src/commonMain/kotlin/com/maxrave/kotlinytmusicscraper/Ytmusic.kt)).

The Full build uses [Sentry](https://sentry.io/) for crash reporting. The current release is the Full build; a FOSS build can be produced from source if you do not want crash data collected.

## Full and FOSS builds

The source supports two variants:

- **Full** includes Sentry crash reporting.
- **FOSS** contains no Sentry integration.

The APK attached to the current GitHub release is the Full build.

## FAQ

### Wrong lyrics?

Lyrics come from LRCLIB and other sources. A track can match the wrong lyrics when the `videoId` metadata does not line up perfectly, so Naudio uses string and duration matching to pick a result. Some songs still get the wrong lyrics.

### Why is the app called Naudio?

Naudio is a personal fork of SimpMusic, built for the maintainer's own listening needs: streaming music from YouTube Music, free and ad-free. Full credit for the original app goes to [maxrave-dev](https://github.com/maxrave-dev).

For other questions, open an issue on [GitHub](https://github.com/crazynanda/Naudio_2/issues).

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before opening a pull request. The contributing guide covers the issue-first workflow and the project's AI-assistance policy.

Thanks to all contributors:

<a href="https://github.com/crazynanda/Naudio_2/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=crazynanda/Naudio_2" alt="Naudio contributors">
</a>

## Architecture

Naudio follows Clean Architecture and MVVM in the UI layer.

<p align="center">
  <img src="asset/dependencies_graph.svg" width="800" alt="Dependency graph">
</p>

## License and attribution

Naudio is licensed under the [GNU General Public License v3.0](LICENSE). It is based on [SimpMusic](https://github.com/maxrave-dev/SimpMusic), also GPL-3.0, by [maxrave-dev](https://github.com/maxrave-dev) (Tuan Minh Nguyen Duc). Original copyright and attribution remain with the SimpMusic authors and contributors.

Naudio is an independent fork and is not an official SimpMusic release.

## Legal Disclaimer and Terms of Use

### 1. 100% Free, Open-Source and Strictly Non-Commercial

Naudio is a fully open-source project (FOSS) created purely for educational purposes and personal use. **We do not sell this application, nor do we monetize it in any way.** There are no advertisements, no premium features, no subscriptions, and no hidden fees within the app. This project has no commercial value or financial intent.

### 2. A Custom Browser with Content Filtering

Naudio acts strictly as a specialized, third-party web browser and client. It parses the publicly available website content and APIs of YouTube and YouTube Music, rendering them in a custom user interface. The ad-free experience it provides is fundamentally no different from using a standard web browser (like Chrome, Firefox, or Brave) equipped with a common ad-blocking extension (such as uBlock Origin).

### 3. Support Content Creators

We deeply respect the hard work of artists, musicians, and content creators. **We strongly encourage all users to subscribe to [YouTube Premium](https://www.youtube.com/premium).** Purchasing a Premium subscription is the best way to financially support the creators you listen to and ensure the continued growth of the platform. Naudio is built as a proof of concept for developers and enthusiasts, not to harm creators' revenues.

### 4. No Hosting of Copyrighted Material

We do not host, upload, distribute, or store any audio, video, or copyrighted media files on our own servers. All content accessed through this application is stored entirely on Google's/YouTube's servers and remains the property of their respective copyright owners. The app merely acts as a conduit to stream publicly accessible links.

### 5. User Responsibility and Legal Contact

The software is provided "AS IS", without warranty of any kind. The developers of Naudio do not encourage or condone piracy. Users are solely responsible for ensuring their usage of this app complies with their local copyright laws and the Terms of Service of the platforms they access.

Because we do not host any media files, we cannot process DMCA takedown requests for audio or video content. However, if you represent a copyright holder or have legal concerns regarding the open-source code itself, please contact us via email at: **nandablr242@gmail.com**
