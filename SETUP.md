# Setting up the shared dataset

You need two free accounts, about 15 minutes in total. Until you finish, the app works normally with dataset uploads switched off.

## 1. Firebase (stores uploaded scans)

Everything below fits Firebase's free **Spark** plan. Only Firestore and Authentication are used, not Cloud Storage, which now needs a paid plan.

1. Go to https://console.firebase.google.com and sign in with your Google account.
2. **Create a project**, e.g. `pokegrade`. You can turn Google Analytics off.
3. **Add an Android app:**
   1. Click the Android icon.
   2. Package name: `com.pokegrade.app`. Nickname: anything. Skip the SHA-1.
   3. Download **`google-services.json`**. Skip the remaining wizard steps, since the code is already set up.
4. **Authentication:**
   1. Build → Authentication → Get started.
   2. Sign-in method → **Anonymous** → Enable.
5. **Firestore:**
   1. Build → Firestore Database → Create database.
   2. Pick a location near you and **Start in production mode**.
   3. Open the **Rules** tab and replace everything with the contents of [`firebase/firestore.rules`](firebase/firestore.rules), then **Publish**.
6. **Give the build your config.** In this GitHub repo:
   1. Go to Settings → Secrets and variables → Actions → **New repository secret**.
   2. Name: `GOOGLE_SERVICES_JSON`.
   3. Value: paste the **entire contents** of `google-services.json`.

   The next build turns uploads on. Don't commit the file itself: it's already in `.gitignore`.
7. **For pulling the data later** (owner only):
   1. Project settings → Service accounts → **Generate new private key**.
   2. Keep that file private and **outside** the repo. It gives full access to your project.

## 2. PSA public API (verifies cert numbers)

1. Go to https://www.psacard.com/publicapi and **sign in or register** with a free PSA account.
2. On the API page, generate an **access token**.
3. Keep it to yourself. It stays on your computer and is **not** built into the app, so your friends never see it:

   ```bash
   export PSA_API_TOKEN=your-token
   ```

   The free tier allows 100 cert lookups per day. The pull script caches results and picks up where it left off the next day.

## 3. Pulling and using the data

```bash
pip install firebase-admin
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccount.json
export PSA_API_TOKEN=your-token

python3 tools/dataset/pull_dataset.py --psa-images      # download + verify certs
./gradlew :tools:run --args="grades $PWD/samples/graded"                # how accurate is grading?
./gradlew :tools:run --args="backs  $PWD/samples/backs --write-model"   # retrain the fake-back model
```

What the pull does:
- Every PSA cert is checked against PSA's database.
  - If the grade someone typed doesn't match, PSA's grade is used and the mismatch is printed.
  - A cert that doesn't exist is reported, since that can mean a fake slab.
- Verified labels are marked in Firestore, so they're only looked up once.
- Labels the pull uses as known **genuine**: "I pulled it from a pack", and slabs with a verified PSA cert.
- Labels it uses as known **fake**: "Confirmed fake".

## Privacy

The privacy policy everyone must accept is [`app/src/main/assets/PRIVACY.md`](app/src/main/assets/PRIVACY.md). If you change it:
1. Bump `POLICY_VERSION` in `MainViewModel.kt`.
2. Everyone is asked to accept the new version the next time they open the app.

It names you only as "the person who shared the app". If you'd like friends to have an email address for deletion requests, add it to section 7.
