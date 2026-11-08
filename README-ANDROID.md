# ShabGPT Android

Native Android application built with Kotlin and Jetpack Compose. It is not a WebView wrapper.

## Build
Open this repository in Android Studio and run the `app` configuration, or use the **Build ShabGPT Android APK** GitHub Actions workflow. The workflow uploads `app-debug.apk` as an artifact.

## Backend
The app connects to the existing ShabGPT Cloudflare Worker at `https://shabgptapi.shabarish4737.workers.dev`. It uses the account registration/login and chat-completions API. Internet access is required.

## Notes
This first native build is a functional starter: account sign-in/registration, chat, and sign-out. Conversation-history browsing, attachments, voice, notifications, and a signed release APK are not included yet.
