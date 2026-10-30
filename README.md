# KitchSync Minimal · Android Kitchen KDS

**KitchSync Minimal** is a high-performance, minimalist Android Kitchen Display System (KDS) and Catering Prep Aggregator tailored for multi-channel catering and Nasi Padang operations (pre-loaded with the **Hjh Maimunah Restaurant Pte Ltd V23 Menu Master**).

---

## 🚀 Key Features

- **📱 Pure Mobile Android UI**: Designed for hand-held Android mobile operations with single-thumb navigation, status bar, and responsive touch controls.
- **🥘 4 Catering Formats**:
  - `[MBC]` Mini Buffet Catering (1.5h lead time)
  - `[LB]` Lunchbox (1.5h lead time)
  - `[BSU]` Buffet Set Up (2.0h lead time)
  - `[BB]` Bento Box (1.5h lead time)
- **🔥 Dual Batch Aggregation**:
  - **Cook (Exploded)**: Automatically explodes catering sets (e.g., Chicken Set A, Mini Buffet) into individual station dishes (Rice, Proteins, Vegetables, Sides) for wok and fryer chefs.
  - **Pack (Sets)**: Retains set packages for assembly packing tables.
- **📥 Multi-Channel Native Ingestion**:
  - **Apache PDFBox-Android**: PDF text stripper with automatic Google ML Kit OCR fallback for scanned & rasterized PDFs.
  - **Apache POI (`XWPFDocument` / `HWPFDocument`)**: Word document ingestion extracting both tables and paragraphs in document order for `.docx` and `.doc` catering runsheets.
  - **Google ML Kit Text Recognition**: Neural on-device OCR engine for camera photos, physical dockets, and screenshots.
  - Manual docket entry with format detection and V23 fuzzy matching confidence scoring.
- **🖨️ LAN Thermal Ticket Printing**:
  - Raw 9100 ESC/POS network printing for Hot Kitchen, Drinks Bar, Dessert Station, and Order Dockets.
- **📶 100% Offline Capable**: Single-page application, zero external runtime dependencies.

---

## 📦 Android APK Installation

The Android APK is automatically built on every release using GitHub Actions.

1. Download the latest `KitchSync-Minimal.apk` from the [GitHub Releases](https://github.com/nrzwanmdnor-lab/kitchsync-minimal/releases) tab.
2. Transfer or open the `.apk` file on your Android tablet or smartphone.
3. Allow "Install unknown apps" if prompted.
4. Open **KitchSync Minimal** to start managing catering prep batches.

---

## 🛠️ Building from Source

### Android Studio
1. Open the `android/` directory in Android Studio.
2. Sync Gradle dependencies.
3. Run or Build APK: `Build > Build Bundle(s) / APK(s) > Build APK(s)`.

### Command Line
```bash
cd android
./gradlew assembleDebug
```
The output APK will be located at `android/app/build/outputs/apk/debug/app-debug.apk`.
