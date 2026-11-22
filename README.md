# Lumina Gallery v4.0 Pro

An Android local-media gallery built with Kotlin, Jetpack Compose and Material 3.

- **Application ID / package:** `com.lumina.gallery`
- **Version:** `4.0` (`versionCode` 4)
- **Minimum Android:** 8.0 / API 26
- **Compile SDK:** Android API 37
- **Target SDK:** Android API 36
- **UI:** English, AMOLED-black palette, Material 3

## Build

Requirements: JDK 17 and an Android SDK with platform 37 and Build Tools 36.0.0.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The included `gradlew` / `gradlew.bat` bootstrap Gradle 9.6.0 into the local Gradle user directory on first use. They do not include the standard Gradle wrapper JAR. The GitHub Actions workflow uses the Gradle setup action and builds the release variant directly.

### GitHub Actions

Push to `main` or `master`, or run **Build Lumina Gallery Release APK** manually from the Actions tab. The workflow uploads `LuminaGallery-v4-Pro-unsigned.apk` as the `LuminaGallery-v4-Pro-APK` artifact. This is an **unsigned** release APK; configure Android signing credentials in GitHub Actions before distributing it as a production-signed build.

## Included functionality

- Five bottom destinations: Albums, AI Find, People, Clean and Vault.
- MediaStore-backed image/video/PDF metadata query with no fixed 300-item limit; lazy grids use stable media IDs.
- 1.5-second MediaStore observer debounce. Refreshes keep the current list composed while data is reloaded, preserving list scroll state; new media is ordered by modified date descending by default.
- Album grouping, pinned/hidden folders, dynamic Recycle Bin card, filters, timeline, sorting, camera intent and Google Code Scanner integration.
- Explicit back navigation from Studio Pro to Viewer to Folder / selection to Albums, followed by an exit prompt.
- Multi-touch pinch zoom (1x–5x), zoomed panning and double-tap zoom/reset.
- Long-press selection, select all, share, copy, move, trash/restore and permanent delete. Android 11+ batch operations use Android's system confirmation sheets (Android 8–10 fallback is single-item only).
- File rename, folder transfer via Storage Access Framework, set wallpaper, EXIF-clean image copy and metadata/HEX sample inspection.
- ML Kit Latin-script offline OCR, selectable/editable OCR text, copy/share, Gemini Vision fallback and explicit provider errors.
- Face detection using ML Kit accurate mode, landmark geometry, size, focus and YCbCr heuristics; rename, hide and merge candidate groups.
- AI Find across the current complete MediaStore result set, metadata background indexing with pause/resume, filename/folder/MIME hints, and on-demand coarse dominant-colour histogram scan.
- Swipe Clean, SHA-256 duplicate checks, large-file radar, user-selected `.thumbnails` folder cleanup and WhatsApp `.Statuses` export.
- AES-GCM encrypted private-file copies using an Android Keystore key, PBKDF2 PIN verification, biometric prompt and private-file sharing.
- Basic image crop/adjustment/preset export to `Pictures/Lumina Gallery/Edited`.

## Deliberate implementation boundaries

This project does not pretend that a library API is an AI model. The following requested capabilities are **not fully implemented** in this build:

1. **Bangla / Arabic / all-language OCR:** ML Kit's bundled recognizer is Latin-script only. The app calls Gemini Vision only when offline OCR returns no text and a saved key is available; requests require internet access. Fallback is attempted in the order `gemini-2.5-flash` → `gemini-2.5-flash-lite` → `gemini-2.0-flash`. Exact errors are surfaced in the OCR result dialog. The user's key is stored encrypted with Android Keystore-backed AES-GCM.
2. **Person identity clustering:** ML Kit Accurate face detection plus geometry, sharpness and YCbCr heuristics is implemented. The grouping stage uses a lightweight perceptual hash of face crops, **not** a trained eye-aligned identity embedding model. Results should be manually checked; false positives are reduced, not mathematically eliminated.
3. **AI subject cutout / manual segmentation refinement, generative retouch, Magic Eraser and super-resolution:** UI entry points explain that verified model packages or an inference endpoint are required. No synthetic result is fabricated.
4. **Lossless video trim:** Not implemented. Lumina does not re-encode video and mislabel it as lossless.
5. **PDF embedded-text extraction:** PDF file metadata and filename/MIME search are included where MediaStore exposes the document. Embedded PDF full-text indexing/OCR is not implemented.
6. **All-file visibility:** Android scoped-storage permissions restrict which files MediaStore exposes. Use the system folder picker for `.thumbnails`, `.Statuses`, copy and move operations. The app does not request broad all-files access.
7. **Recycle Bin and batch system prompts:** Proper MediaStore trash/restore/delete requests are available on Android 11+. On Android 8–10, the platform has no equivalent app-controlled trash API; delete falls back to a system delete intent for the first selected item.
8. **Safe Share:** The EXIF-clean-copy operation strips common GPS, timestamp, camera and author tags from image copies. It is not a universal metadata sanitizer for every image container or video format.
9. **Editor crop:** The current editor provides a draggable eight-handle crop rectangle and basic tone adjustments. It does not include full professional RAW editing or a full layer-based editor.

## Permissions and privacy

The app reads shared media through the platform media permissions and SAF. Imported Vault files are copied into app-private storage and encrypted with AES-GCM; original source files remain unchanged. The Gemini key is stored locally using Android Keystore encryption. Gemini OCR sends the chosen image to Google's Generative Language API only when the Latin OCR result is empty and the key is configured. The biometric system prompt is supported on API 28+; devices on API 26–27 must use the PIN. The biometric prompt unlocks the app screen while the encrypted files remain protected by the Keystore AES-GCM key. The app does not include analytics or ad SDKs.

## Build verification status

The source archive includes a GitHub Actions release workflow and a Gradle bootstrap script. A full Android Gradle build was **not run in the authoring container** because it did not have an installed Android SDK or Gradle distribution and outbound dependency downloads were unavailable. Therefore this archive should not be described as locally compile-verified until the included workflow completes successfully.

## Build tool compatibility note

The project uses Android Gradle Plugin 9.3.1 with Gradle 9.6.0 and Kotlin Compose plugin 2.4.21. This pins AGP within Kotlin 2.4.21's documented supported AGP range while retaining API 37 support.
