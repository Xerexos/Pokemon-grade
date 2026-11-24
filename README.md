# PokéGrade

An Android app that uses your phone camera to:

1. **Grade** a Pokémon card's condition on the PSA 1–10 scale, with centering, corners, edges and surface subgrades.
2. **Check whether it's real or fake** by comparing it with the official card.
3. **Price it** using current TCGplayer and Cardmarket prices.

## How it works

| Step | What happens | Where it runs |
|---|---|---|
| Capture | CameraX preview with a card-shaped guide. The photo is cropped to the guide. You can also pick a photo from your gallery. | Phone |
| Grade | Computer vision finds the card outline, measures border widths (centering), and looks for whitening or chips at the corners and edges and for scratches on the border. The results are mapped onto PSA's tolerances. | Phone |
| Identify | Google ML Kit text recognition, an on-device ML model, reads the name, HP and collector number (e.g. `025/198`). | Phone |
| Look up | The card is matched in the free [Pokémon TCG API](https://pokemontcg.io), which also provides the official scan and daily prices. | Internet |
| Authenticate | Checks that the card exists, that HP, set number and attack names match the official card, and that the artwork layout, colour saturation and border colour match the official scan. | Phone |
| Price | Uses the TCGplayer near-mint market price, adjusted for the estimated grade. You can switch between raw-card value and graded-slab value. | Phone |
| AI second opinion (optional) | Sends the photo and the official scan to Claude's vision model, which returns a structured grade and authenticity assessment. | Anthropic API (needs your API key) |

## Getting the APK

Every push to GitHub runs `.github/workflows/android.yml`, which runs the unit tests and builds the APK.

1. Open the repo on GitHub, then go to **Actions** → **Build Android APK** → the latest run.
2. Download the **PokeGrade-apk** artifact and unzip it.
3. Copy the `.apk` to your phone and open it. You may need to allow "Install unknown apps" for your file manager.

Or build it yourself with Android Studio (Ladybug or newer), or from the command line:

```bash
./gradlew :app:assembleRelease      # APK in app/build/outputs/apk/release/
./gradlew :core:test                # grading / authenticity / pricing unit tests
```

The release build is signed with the debug key so it installs directly. Use your own keystore before publishing to the Play Store.

## Settings

- **Anthropic API key** (optional): turns on the "AI second opinion". Get one at console.anthropic.com. Each inspection is billed to your account. Photos are only sent when you ask for an AI opinion, or when you turn on auto mode.
- **Pokémon TCG API key** (optional): the API works without a key. A free key from dev.pokemontcg.io raises the rate limit.

## Tips for accurate results

- Put the card on a **dark, plain** surface. A white table hides edge whitening.
- Use even light without glare, and hold the phone flat, directly above the card.
- Fill the guide frame.

## Limitations (please read)

- **Grades are estimates.** The app sees one photo of the front only. Real graders also check the back, and they inspect surface and edges under magnification. Use the app to decide whether a card is *worth* sending to PSA, BGS or CGC.
- **Authenticity checks catch common fakes**: made-up cards, wrong HP or attacks, mismatched set numbers, and washed-out or over-saturated printing. They cannot check card stock, weight, or the light test. "Likely authentic" is not a guarantee.
- **Graded values are ballpark figures.** Only raw (ungraded) prices come from real market data. Graded prices use typical multipliers, because no free API publishes graded sales. Check recent sold listings before buying or selling.
- Full-art and borderless cards have little or no border to measure, so their centering is less reliable.

## Project layout

```
core/   Pure Kotlin (JVM). Grading engine, OCR text parser, Pokémon TCG API client,
        authenticity checker, price estimator, Claude client. Unit-tested.
app/    Android app (Jetpack Compose, CameraX, ML Kit).
```
