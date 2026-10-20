# Getting Reelcards onto Google Play: step by step

This is the order to do things in and what to click. [PLAY-STORE.md](PLAY-STORE.md) has the text to
paste (store listing, Accessibility declaration, Data safety answers). Read the last section, *How
current this is*, before you rely on any detail: Play's rules change.

## The plan on one page

A new personal account can't publish to everyone straight away. Google makes you run a **closed test
with at least 12 testers for 14 days in a row** first. So the 14 days are the slow part. Start them
as soon as the app is ready, and do the paperwork while the clock runs.

| When | What | Who |
|---|---|---|
| Day 0 | Create the developer account and pay (Step 1) | You |
| Day 0 to 1 | Make the upload key, build the signed bundle (Steps 2 and 3) | You, with CI |
| Day 1 to 2 | Create the app, put the build on **Internal testing**, install it on 2 or 3 phones (Steps 4 and 5) | You |
| Day 2 | Start the **closed test** and get 12 or more people opted in (Step 7). The 14 days start counting for each tester when they opt in. | You and your testers |
| Days 2 to 16 | Fill in App content, the Accessibility declaration and its video, and the store listing (Step 6). Fix bugs the testers find. | You |
| Day 16 or later | Apply for production (Step 8). Google says review usually takes 7 days or less, and sometimes longer. | You, then Google |
| About week 4 | Release to production (Step 9) | You |

Allow **three to four weeks** if nothing is rejected. A first rejection is common, especially for the
Accessibility declaration. You fix it and send it again.

**Internal testing is the quick win.** It has no 12-tester rule and takes up to 100 people. They
install Reelcards from Google Play like any app, with none of the warnings of the GitHub file (see
the table near the end). You can have friends using it within a day or two.

## Before you start

- A computer with Java installed (the JDK, version 17 or newer), for `keytool`. Or use Git Bash or
  WSL on Windows.
- Admin access to the GitHub repo (to add secrets and run workflows).
- An Android phone, and a Google account to own the Play developer account.
- **US$25** (one-off), a bank card that isn't prepaid, and a government ID. They must carry the
  same legal name as the account. If the person who'll own the app is under 18, ask a parent, carer
  or school to be the account holder. Google's own rules decide who may register, so check at
  sign-up.
- A list of **15 to 20 testers** with ordinary Google accounts (Gmail). You need 12 who stay in for
  14 days, and a few will drop out.
- Your own **phone screenshots** of the app (see *What could go wrong*).

## Step 1: Create the developer account (day 0)

1. Go to [play.google.com/console](https://play.google.com/console) and sign up. Choose **Personal**
   unless the app belongs to a school or company. It's hard to change later. An **Organisation**
   account needs a D-U-N-S number, which can take weeks, and is reportedly exempt from the 12-tester
   rule. Check that against Google's current page before choosing.
2. Pay the $25 and complete the identity check (ID and card). It can take minutes or a few days.
3. Google also asks new personal accounts to prove they have an Android phone, using the **Play
   Console** app from Google Play. Install it on your phone and follow the prompts.
4. Your **developer name** and **contact email** are shown on the store page. Use ones you're happy
   to make public.
5. Play will ask some legal questions, for example whether you count as a "trader" under EU
   consumer law. Answer honestly. The Console explains each one.

## Step 2: Make your upload key and give it to GitHub (day 0 to 1)

The upload key proves a build came from you. Google keeps a separate key that signs what users get
(**Play App Signing**), and can reset the upload key if you lose it. The repo already has the
tooling.

1. On your own computer, in the repo folder, run `tools/make-upload-key.sh`. It asks for a password
   (6 or more characters) and writes `~/reelcards-upload.jks`.
2. **Back it up now.** Put the `.jks` file and its password in a password manager. Never commit it.
3. The script prints four secrets. In GitHub go to the repo's **Settings, Secrets and variables,
   Actions, New repository secret**, and add:
   - `REELCARDS_KEYSTORE_BASE64`: the output of `base64 -w0 ~/reelcards-upload.jks`
     (on a Mac: `base64 -i ~/reelcards-upload.jks`)
   - `REELCARDS_KEYSTORE_PASSWORD`: your password
   - `REELCARDS_KEY_ALIAS`: `upload`
   - `REELCARDS_KEY_PASSWORD`: the same password

Never sign anything for Play with the shared demo key in `tools/`. CI refuses to keep a bundle that
is signed with it.

## Step 3: Build the signed bundle (day 1)

1. On GitHub open **Actions, Build, Run workflow**, pick `main`, and run it.
2. After about three minutes, open the run. Under **Artifacts** download **Reelcards-play-bundle**.
   Unzip it. Inside is `Reelcards.aab`.
3. If there's no such artifact, the `play-bundle` job was skipped. Check the four secrets are
   spelled exactly as above.

Every upload to Play needs a higher `versionCode` in `app/build.gradle` than the last one. It's 7
now, for version 1.3.2.

## Step 4: Create the app (day 1)

In the Console choose **Create app**: name *Reelcards: GCSE flashcards*, default language English
(United Kingdom), **App**, **Free** (a free app can't be made paid later), and tick the
declarations. When asked, use **Play App Signing**.

## Step 5: Internal testing (day 1 to 2)

1. **Testing, Internal testing, Create new release.** Upload `Reelcards.aab`, add a note such as
   "First test build", then **Save**, **Review release** and **Start rollout**. Play may ask you to
   finish some *App content* items before it lets you roll out. Do them as it asks (Step 6 has the
   text).
2. **Testers** tab: **Create email list**, add your testers' Gmail addresses, **Save**.
3. Copy the **opt-in link** and send it. Each tester opens it on their phone, signed in with that
   Google account, taps **Become a tester**, then **Download it on Google Play**.
4. **Tell testers to uninstall the GitHub version first.** The Play version is signed with a
   different key, so Android refuses to install it over the old one. Their cards and progress don't
   carry over.
5. Install it on 2 or 3 real phones and go through [PHONE-TESTS.md](PHONE-TESTS.md). Reelcards has
   not run on a phone yet, so don't start the 14-day clock on a broken build. You can upload fixes
   during the 14 days. The clock counts testers staying opted in, not the build number.

## Step 6: The paperwork (while the clock runs)

All of it is in the Console under **Dashboard, Set up your app**, and **Policy and programs, App
content**. The Dashboard lists exactly what's still needed. The answers are in
[PLAY-STORE.md](PLAY-STORE.md):

- **Privacy policy:** `https://llamaoat.github.io/flashcardreela/privacy.html`. Open it in a
  browser first and check it loads.
- **Ads:** none. **App access:** everything works without an account.
- **Content rating:** the questionnaire. Educational, no violence, no user-to-user chat.
- **Target audience:** 13 and over. Don't tick any under-13 age: that brings in the Families policy.
- **Data safety:** nothing leaves the device.
- **Accessibility API declaration:** the form, the short answers and the video script are in
  PLAY-STORE.md. This is the part Google looks at hardest. The video should show the app opening,
  the disclosure, the *Agree* and *No thanks* routes, and a card appearing over a reel. Add
  captions. Upload it as an unlisted YouTube video and paste the link.
- **Store listing:** the text is in PLAY-STORE.md. The icon and feature graphic are in
  `docs/play-store/`. Use **real screenshots from a phone** for the phone images.

## Step 7: The closed test (day 2)

1. **Testing, Closed testing**, create a track (the default is called Alpha). On its **Testers**
   tab pick your email list, or a Google Group, and choose the countries (at least the United
   Kingdom).
2. **Create release.** You can promote the same build from Internal testing. **Start rollout.**
3. Copy the opt-in link and send it to **all 15 to 20 testers in the first two or three days**. Each
   tester needs 14 days in a row. If someone joins on day 6, their 14 days finish on day 20. If they
   opt out and back in, their count starts again.
4. Ask them to keep the app installed, open it now and then, and tell you what's wrong. Google asks
   you to summarise tester feedback when you apply, and may look at how engaged testers were. Use
   the "What we'd most like to know" list in [TESTERS.md](TESTERS.md), and keep your own notes.
5. Check the count on the Console's closed test page. It needs 12 or more opted in, and the
   Dashboard shows when you can apply.

## Step 8: Apply for production (day 16 or later)

When the Dashboard shows **Apply for production**, click it. The form asks about your closed test,
your app, and whether it's ready. Answer from your tester notes. Google usually replies within 7
days. If it says too few testers or too little engagement, keep the test running and apply again.

## Step 9: Release to everyone

**Production, Create release**, add the bundle, choose countries, and roll out. Choose a **staged
rollout** (for example 20 percent first) so that a problem on some phone model reaches fewer people.
Then watch **Android vitals** and reviews in the Console.

## After launch

- Each release: raise `versionCode` and `versionName` in `app/build.gradle`, add a CHANGELOG entry,
  tag `vX.Y.Z`, and run the Build workflow for the new `.aab`. Upload it to Production.
- If the app starts using the accessibility service in a new way, update the in-app disclosure,
  `PRIVACY.md` and the Play declaration together, and send the declaration form again. Google
  requires this, and can remove an app that doesn't.
- If a feature changes what data the app touches, update *Data safety* too.

## What testers get on Play, compared with the GitHub file

| | GitHub APK | Play |
|---|---|---|
| "This type of file can harm your device" | Yes | No |
| Allow your browser to install unknown apps | Yes | No |
| Play Protect "unrecognised app" screen | Yes | No |
| Android 13+ greys out the Accessibility switch ("restricted settings") | Yes | No |
| Updates | Download and install each new file | Automatic |
| Android developer verification for sideloaded apps (global from 2027) | Likely needed. Google offers a free "limited distribution" account for up to 20 devices. | Play registers apps automatically |

## What could go wrong

- **The Accessibility review.** Reelcards isn't an accessibility tool, so Google reviews it closely.
  If it's rejected, read the reason, change the disclosure or the video, and send it again.
  Don't set `isAccessibilityTool` to `true` to get past it. That would be a false declaration.
- **Advanced Protection on Android 17.** News reports say it blocks apps that aren't accessibility
  tools from using the Accessibility API. It's opt-in, so only students who switch it on are
  affected, but it shows where Google is heading. Check Google's own documentation.
- **Real phones.** The app has never run on one. Expect surprises in the first week of testing.
- **Screenshots.** The images in `docs/play-store/` are drawn from the web demo, not captured on a
  phone. Play wants screenshots that show the real app. Replace them with phone screenshots before
  you go to production.
- **Names.** Keep TikTok, Instagram and YouTube out of the app title, short description and
  graphics. Play's metadata policy is strict about other brands.

## How current this is

Checked on 17 October 2026.

- Read directly on Google's developer site: the target API rule (new apps and updates must target
  API 36 from 31 August 2026, and the app does) and the developer verification timeline.
- **Not read directly**, because Play Console Help wasn't reachable from where this was written.
  These come from search summaries of Google's pages and from articles: the fee and ID checks, the
  12 testers for 14 days rule, the review time, the Accessibility declaration and its video, and the
  Advanced Protection change. Check each against the Console and Google's help pages when you reach
  it.
- Google's pages: [Play Console Help](https://support.google.com/googleplay/android-developer),
  [target API level](https://developer.android.com/google/play/requirements/target-sdk),
  [developer verification](https://developer.android.com/developer-verification).
