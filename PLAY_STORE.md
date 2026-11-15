# NodeMax on Google Play: what to do and what to answer

The code side is done (see "Built into the app"). The rest is forms in Play Console. Google decides every review, so nothing here guarantees approval.

## The one policy that matters most

Google Play's Device and Network Abuse policy allows an app that proxies traffic for third parties only when that is "the primary, user-facing core purpose of the app". NodeMax fits that only if the app is honest and opt-in:

- The app's name, icon, store text and screenshots must say plainly that it shares the phone's internet connection with other customers and pays the owner (no "free VPN", no "speed booster" wording).
- Nothing runs until the person taps Connect, and a notification is visible the whole time (already true).
- The first screen explains it before anything else and asks for agreement (already true).
- Never hide the proxy behind another feature. Apps hidden inside "free VPN" apps were removed from Play in 2024.

Keep the direct APK on your website as the fallback. If Play rejects or later removes the app, the website download keeps working.

## Built into the app (version 2.2)

- First-run disclosure screen, then a permission screen (notifications, approximate location). Location is optional: "Not now" works.
- Approximate location only (about 1 km), read once an hour while sharing is on. Never continuous.
- The Play build ("play" flavor) has no REQUEST_INSTALL_PACKAGES permission and no self-update. The website build ("direct" flavor) keeps the in-app updater.
- Target SDK 35, 64-bit (arm64-v8a) included, signed Android App Bundle built by the workflow.
- In-app account deletion, plus a public deletion page at `/account-delete`.
- Privacy policy at `/privacy` (mentions location, phone details, payouts, deletion).
- No analytics, no advertising ID, Firebase used only for push messages.

## Files from the build

Run the "Build APK" workflow. Then:
- `NodeMax.apk` is on the Releases page (website download).
- `NodeMax-play.aab` is under the run's Artifacts (upload this to Play Console).

Use Play App Signing. Your fixed key becomes the upload key.

## Play Console forms

**App content > Privacy policy:** `https://YOUR-DOMAIN/privacy`

**App content > App access:** the app needs an account. Create a test account (mobile number and password) and enter it so reviewers can sign in. Tell them: tap Connect to see the sharing notification.

**App content > Ads:** No ads.

**App content > Target audience:** 18 and over. Not for children.

**App content > Data safety** (answer like this):

| Data | Collected | Shared | Why |
|---|---|---|---|
| Approximate location | Yes, optional | No | App functionality, fraud prevention |
| Name | Yes | No | Account management |
| Phone number | Yes | No | Account management |
| Financial info (UPI ID) | Yes, only when requesting a payout | No | App functionality |
| Device or other IDs (app device ID, push token) | Yes | No | App functionality, fraud prevention |
| App info and diagnostics (crash text, model, Android version, battery, network type, carrier, IP address) | Yes | No | App functionality |
| Network traffic of other customers | Passes through the phone; you do not store content | No | App functionality |

Also answer: data is encrypted in transit (yes), people can request deletion (yes, in-app and web link).

**App content > Foreground service permissions** (declare each):
- `specialUse` (and `dataSync` on older Android): "Shares the phone's internet connection with the NodeMax network only while the user has tapped Connect. Shows a persistent notification and stops when the user taps Disconnect." Add a short screen recording: open app, Connect, notification visible, Disconnect.
- `location`: "Takes one approximate location fix per hour while sharing is on, so the service knows which city and state the connection exits from. Optional; the app works without it."

**Permissions declaration for location:** the app asks only for approximate location (ACCESS_COARSE_LOCATION). It does not use background location permission, so no background-location declaration is needed.

**Store listing:** write what the app does in the first lines: "Earn money by sharing your phone's unused internet. When you tap Connect, other customers' traffic leaves through your phone. You are paid per GB." Add 2 to 8 phone screenshots, a 512 x 512 icon and a 1024 x 500 feature graphic. Category: Tools or Finance is a poor fit; use Tools.

**Content rating:** answer the questionnaire honestly. Expect a low rating.

**Developer account:** a new personal account must run a closed test with 12 testers for 14 days before production access. An organization account skips this. Check the current rule in Play Console when you register.

## Before you submit

1. Update `/privacy` with your real company name, address and contact (Admin > Settings). Have a lawyer review all legal pages.
2. Check that the Settings > "App download link" points to the right place for each audience.
3. Put the reviewers' test account in App access.
4. Upload the AAB to Internal testing first and install it from Play on a real phone: sign up, Connect, check location arrives in Admin > Devices.
