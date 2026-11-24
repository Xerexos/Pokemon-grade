# PokéGrade

An Android app that uses your phone camera to:

1. **Grade** a Pokémon card's condition on the PSA 1–10 scale, with centering, corners, edges and surface subgrades.
2. **Check whether it's real or fake**, front and back.
3. **Price it** using current TCGplayer and Cardmarket prices.

## How it works

| Step | What happens | Where it runs |
|---|---|---|
| Capture | CameraX preview with a card-shaped guide. The photo is cropped to the guide. You can also pick a photo from your gallery. | Phone |
| Find the card | Looks for the strongest rectangle with a card's 63:88 shape. This ignores sleeves, toploaders, cases and the card's own inner frame. | Phone |
| Grade | Measures border widths (centering), looks for whitening and chips at corners and edges, and for scratches on the border, then maps them onto PSA's tolerances. If the photo can't be measured (no clean outline, too much glare, blurry), it says so instead of guessing. | Phone |
| Identify | Google ML Kit text recognition reads the name, HP and number (e.g. `025/198`). Misreads of the stylised "ex" logo are corrected. | Phone |
| Look up | Matches the card in the free [TCGdex](https://tcgdex.dev) database. When several printings match, it picks the one whose official image looks most like your photo. You can also search by name. | Internet |
| Authenticate (front) | Card exists; HP, set number and attack names match; artwork layout, colour saturation and border colour match the official scan. | Phone |
| Authenticate (back) | Optional back scan. Checks the blue swirl, Poké Ball and logo are present and that the border blue is the right shade. If you've saved the back of a card you know is real, it compares against that, which is much stronger. If a trained model is bundled, it also runs that. | Phone |
| Price | TCGplayer near-mint market price (plus Cardmarket), adjusted for the estimated grade. You can switch to an estimated graded-slab value. | Phone |
| AI second opinion (optional) | Sends the front, the back (if scanned) and the official scan to Claude's vision model, which returns its own grade and authenticity assessment. | Anthropic API (needs your API key) |

## Getting the APK

Every push runs `.github/workflows/android.yml`, which runs the unit tests and builds the APK.

1. On GitHub, go to **Actions** → **Build Android APK** → the latest run.
2. Download the **PokeGrade-apk** artifact and unzip it.
3. Copy the `.apk` to your phone and open it. You may need to allow "Install unknown apps".

Or build it yourself:

```bash
./gradlew :app:assembleRelease      # APK in app/build/outputs/apk/release/
./gradlew :core:test                # unit tests (LIVE_API=1 also runs live TCGdex lookups)
```

## Languages

Cards are looked up in their own language. The language is detected from the text: kana means Japanese, Hangul means Korean, Chinese characters mean Chinese, and words like "Schwäche" or "Faiblesse" identify European languages. Supported: English, Japanese, French, German, Spanish, Italian, Portuguese, Korean, Chinese (Traditional and Simplified), Indonesian and Thai.

- European prints have TCGplayer and Cardmarket prices.
- Japanese and other Asian prints mostly have Cardmarket prices only, converted to USD.
- Japanese, Chinese and Korean text models download through Google Play services after install.
- The back check only knows the international back, so it's skipped for Asian cards.

## Prices for cards without database data

Some cards have no price in TCGdex, for example most mainland-China (Simplified Chinese) cards. For those, the price section offers **eBay sold**, **eBay for sale** and **PriceCharting** buttons, searched with an English phrase:

- Raw card: "pokemon chinese Hisuian Growlithe 020". Names are translated with a bundled table built from [PokeAPI](https://pokeapi.co) data (BSD-3-Clause).
- Slab: "pokemon chinese Captain Pikachu 701 DSG 10", taken from the label.

The same buttons appear under the database price for every card.

## Grading companies

The results screen can show the estimate the way each company would print it: PSA, Beckett (BGS, with subgrades and Black Label), CGC (Pristine 10), SGC, TAG, ACE, Diamond Service Grading (DSG), AGS, HGA, GMA and MNT.

- The graded-slab value lets you pick the company. Boutique graders' slabs usually resell below PSA, so their values are scaled down. These are rough multipliers, not market data.
- Labelled uploads accept any of these companies, or "Other" with a name.
- Only PSA certs are checked automatically (through PSA's API). For CGC, the pull script records a cert-lookup link so you can check by hand.

## Tips for accurate results

- **Take the card out** of any sleeve, toploader or case.
- Put it on a **dark, plain** surface. A white table hides edge whitening.
- Use even light without glare, and hold the phone flat, directly above the card.
- For the back check, first scan the back of a card you *know* is genuine and tap **Save as my reference back**. Later backs are compared with it under similar light.

## Improving accuracy with real data

The grading thresholds and the back-check rules are hand-tuned. Labelled photos make them measurable and trainable.

### Fake backs

1. Put back photos in `samples/backs/real/` and `samples/backs/fake/`. For example, use the Kaggle [Real and Fake Pokémon Cards](https://www.kaggle.com/datasets/ongshujian/real-and-fake-pokemon-cards) set, sorted by its labels.
2. Run:

   ```bash
   ./gradlew :tools:run --args="backs $PWD/samples/backs --write-model"
   ```

   This prints how well each measurement separates real from fake, trains a logistic-regression model with 5-fold cross-validation, and writes `core/src/main/resources/back_model.json`. The app uses that model on the next build.

### Grading

1. Collect photos of graded cards with their grades:
   - **Your own slabs**: put them in `samples/graded/10/`, `samples/graded/9/`, and so on.
   - **eBay**: run the scraper below. It writes `samples/ebay/labels.csv`.
2. Run:

   ```bash
   ./gradlew :tools:run --args="grades $PWD/samples/graded"     # or samples/ebay
   ```

   This reports mean error, the share of cards graded within one grade, bias, and averages per true grade.

### eBay scraper

`tools/ebay/ebay_graded_scraper.py` collects listing photos of PSA/BGS/CGC-graded Pokémon cards through eBay's official Browse API. The grade comes from the listing title. It only uses the Python standard library.

```bash
export EBAY_CLIENT_ID=...       # free keys from developer.ebay.com (Production keyset)
export EBAY_CLIENT_SECRET=...
python3 tools/ebay/ebay_graded_scraper.py --company PSA --grades 1-10 --per-grade 100
```

It skips lots, proxies, Japanese cards and ambiguous titles such as "PSA 8 or 9". It de-duplicates images and can be re-run to top up a dataset.

The photos belong to the sellers and show cards inside slabs. Keep them for your own testing, and follow eBay's API License Agreement.

## Shared dataset & privacy

You and your friends can label scans ("graded slab + cert number", "pulled from a pack", "confirmed fake") and upload them to a private Firebase project. The owner then verifies PSA certs and retrains or re-tunes the app from them.

Everyone must accept the [privacy policy](app/src/main/assets/PRIVACY.md) on first launch. Uploading is a separate opt-in. **Setup steps: [SETUP.md](SETUP.md).**

## Settings

- **Anthropic API key** (optional): turns on the "AI second opinion". Each inspection is billed to your Anthropic account. Photos are only sent when you ask for it, or when you turn on auto mode.
- **Reference back**: the saved back of your known-genuine card. You can clear it here.

## Limitations

- **Grades are estimates** from one photo of the front. Use them to decide whether a card is worth sending to PSA, BGS or CGC.
- **Authenticity checks catch common fakes**: made-up cards, wrong details, and off-colour or blurry printing. They can't check card stock or do the light test. "Likely authentic" is not a guarantee.
- The back check is designed for the English/international back. Japanese cards have a different back.
- **Graded values are ballpark figures.** Only raw prices come from real market data.
- Full-art and borderless cards have little or no border to measure, so their centering is less reliable.

## Project layout

```
core/    Pure Kotlin (JVM): grading engine, OCR text parser, TCGdex client,
         authenticity + back checks, price estimator, Claude client. Unit-tested.
app/     Android app (Jetpack Compose, CameraX, ML Kit).
tools/   Developer CLI (evaluate grades, train the back model) and the eBay scraper.
```
