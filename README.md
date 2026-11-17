# OSM Auto Squad — Android native

This project combines the Compose roster editor with the OCR / AccessibilityService foundation. Current app version: 0.4.0.

## Included in v0.4.0

- Native Android application using Kotlin, Jetpack Compose, ML Kit OCR and AccessibilityService.
- Formation editor for 4-3-3A and 5-2-3A.
- Twenty roster places: starting XI plus 9 substitutes.
- Position selection for substitutes and an “öncelikli yedek” flag.
- Preflight validation for the roster rules:
  - XI rating equals the selected target rating.
  - Starting XI age rule: non-OS positions 20–29; among the two OS players, one is 20–29 and one is 31+.
  - Priority substitutes: at least target rating and age 20–29.
  - Standard substitutes: at least 50 rating and age 15–50.
  - 9 substitute positions: 2 GK, 3 DF, 2 MD, 2 FW.
  - A target-rated SĞA cannot be a substitute.
- Automation handoff from the Compose roster to the AccessibilityService.
- Floating controls: BAŞLAT, DURAKLAT, DEVAM, DURDUR and TANI.
- After a pitch slot is selected, the service recognizes the category chooser and taps the blue + button inside the matching category row (Forvetler, Orta sahalar, Defanslar or Kaleciler). It does not tap the category heading itself. It then opens the player list/search step.
- OCR-based player-row matching checks name, age, position and available rating columns; duplicate matches or failed counter verification stop the run.
- For starting-XI slots, the service attempts to detect the green pitch area and maps the saved formation position into that area. If the pitch cannot be detected, it stops without tapping.
- For substitute slots, the service only proceeds when the Yedekler tab and the named slot are available through the Android accessibility tree. It does not guess substitute coordinates.

## Build

Push the project to a GitHub repository, then run **Actions → Build OSM Auto Squad APK → Run workflow**. The debug APK is uploaded as the `osm-auto-squad-debug-apk` artifact.

The repository does not include a Gradle wrapper binary. The workflow creates the wrapper using Gradle 8.7 before building.

## Phone setup

1. Install the debug APK.
2. Open the app and choose 4-3-3A or 5-2-3A.
3. Enter all 20 players, including age, rating and substitute position/priority status.
4. Open the Checklist tab and press **Otomasyon için hazırla**. Any roster rule errors must be fixed first.
5. Enable **OSM Auto Squad** under Android Accessibility settings.
6. Open OSM and go to the squad / pitch screen. Use the floating service panel and press **BAŞLAT**.
7. Keep an eye on the first run. Press **DURAKLAT** or **DURDUR** if the game screen looks different or unexpected.

## Known limitations — do not treat as production-verified

- This source revision has not been built on a real Android SDK or tested against the live OSM app UI in this environment. The GitHub Actions workflow is included, but an APK has not been generated here.
- OSM screen layouts and OCR text vary by app version, language, device and resolution. The pitch-region detection and normalized field points must be validated on the user's actual OSM screen before unattended use.
- Substitute placement depends on OSM exposing the bench tab and slot labels in the Accessibility tree. If those labels are unavailable, the service stops rather than tapping guessed coordinates.
- Goalkeeper rating is not independently compared because the older OCR row format did not expose a goalkeeper rating column. Name, age and position are still checked.
- There is not yet a verified transfer-price / available-budget parser. Do not run unattended with a valuable in-game budget until the budget guard is implemented and verified.
- Any player row with more than one strict candidate, a missing category, an unreadable counter, or an unverified add is treated as an error and stops the run.

The category + selection fix is based on the supplied OSM screenshot and still needs to be confirmed on the installed OSM version. This is an integrated development build, not a claim that all screen-specific automation is finished or field-tested.
