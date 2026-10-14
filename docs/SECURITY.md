# Security and privacy practices

Reelcards is checked against a standard security and privacy checklist, kept in the repository as
[`docs/security-checklist.json`](https://github.com/llamaoat/flashcardreela/blob/main/docs/security-checklist.json)
so it can be reviewed with each release (and read by the app later). This is the project's own honest
self-check, written on 13 October 2026. It is **not** an outside audit and **not** legal advice. Each item is
marked *Done*, *Partly*, *Planned*, *Not yet*, *Ongoing* or *Not applicable* (and why). Where something isn't done
yet, it says so.

### Rules that apply

- **UK GDPR and the Data Protection Act 2018** (and the EU GDPR for people there). Reelcards collects no
  personal data, so it holds nothing to protect, access, correct or delete on our side. YouTube and TikTok, on the
  Feed page only, are separate controllers of whatever they collect.
- **The ICO's Age Appropriate Design Code ("Children's Code")**, because students under 18 use it. It's used as
  a guide, not formally assessed: collect nothing, no profiling or tracking off the device, no location, high
  privacy by default (the Feed's third-party loading is off until you agree), clear plain-language
  explanations, and no tricks to get children to share more.
- **PECR (the UK's cookie and similar-technologies rules).** The apps' own storage on your device is strictly
  necessary to work. On the Feed page, third-party players may set cookies, which is why it asks first.
- **COPPA (United States).** Reelcards is not directed at children under 13 and collects no personal
  information.
- **Google Play policies** on user data, the Accessibility API and target audience (the app's audience is 13 and
  over). See `docs/PLAY-STORE.md`.

### Where data goes

- **Android app:** cards, history, settings and counts stay in one file in the app's private storage. Nothing
  leaves the phone, except what you choose to share through Android's share sheet, and answers sent to
  AnkiDroid on the same phone if you connect your Anki decks.
- **Web demo:** the page is fetched from GitHub Pages, and its fonts from Google Fonts (they see your internet
  address). Your data stays in your browser's storage.
- **Browser extension:** everything stays in the browser's extension storage. No server.
- **Reelcards Feed:** as the web demo, plus the video players loaded from YouTube or TikTok as described above.
- **Nowhere else:** we run no servers, databases, accounts or analytics.

### Who else is involved

- **GitHub:** hosts the source code, the downloadable apps and the web pages (GitHub Pages).
- **Google Fonts:** supplies the fonts to the web pages.
- **YouTube and TikTok:** the Feed page only, and only after you agree.
- **AnkiDroid:** only if you connect your Anki decks, on the same phone.
- **Google Play:** if you install from there, under Google's own terms.
- **A chatbot of your choice:** only if you paste Reelcards' prompt into one yourself.

### The checklist, item by item

### Authentication & Authorization

*Ensure your app properly authenticates users and controls access to resources*

- **Implement strong password policies:** Not applicable: there are no accounts or passwords.
- **Use secure session management:** Not applicable: there are no sessions, and Reelcards sets no cookies of its own.
- **Implement multi-factor authentication (MFA):** Not applicable: there is nothing to sign in to.
- **Use secure password storage:** Not applicable: no passwords are stored.
- **Implement proper account lockout policies:** Not applicable: there are no logins to lock.
- **Use secure password reset mechanisms:** Not applicable: there are no accounts to reset.
- **Use secure OAuth implementations:** Not applicable: OAuth isn't used. The only permission screens are Android's own.
- **Implement secure logout functionality:** Not applicable: there is no signed-in state.
- **Verify sensitive actions:** Done: *Reset everything*, the one action that deletes your data, needs a second tap on Android and on the web, and removing a deck asks "Tap to confirm". There's no email to confirm by, since there are no accounts.

### Input Validation & Data Sanitization

*Protect your app from malicious input and ensure data integrity*

- **Validate all user inputs on the server side:** Not applicable: Reelcards has no server. Every check happens on the device that uses the data.
- **Implement input length restrictions:** Partly: the Feed page caps pasted notes (20,000 characters), each link (300 characters) and the number of links (40). Elsewhere, text is only held in memory and isn't capped, and picture size is capped at 6 million pixels.
- **Use parameterized queries for database operations:** Not applicable: no SQL is ever built from text you type. Anki files are read with a small read-only reader written for this app, and AnkiDroid is asked through its official content provider.
- **Validate file uploads:** Partly: nothing is uploaded. Anki files are opened read-only on the device, must be a real Anki deck (anything else is refused with a message), and their pictures and sounds are size-limited and kept in the app's private storage. They aren't scanned for malware.
- **Implement proper error handling:** Done: errors show a short plain message (for example "Couldn't read that file"), never technical details. No error-reporting service is used, because that would mean sending data off the device.
- **Validate and sanitize URL parameters:** Done: the only page parameters (`?app=1`, `?card=1`) are matched exactly and anything else is ignored. The Feed accepts links only to a short list of hosts, with a strict pattern for the video id, and uses nothing else from a link.
- **Validate data types and formats:** Done: saved files are read defensively (a save that won't load is kept aside as `engine.json.damaged` and the backup is used), and numbers, dates and video ids are checked before use.

### Data Protection & Privacy

*Secure sensitive data and ensure user privacy*

- **Use encryption for sensitive data storage:** Partly: Reelcards keeps no sensitive personal data, only study data in the app's private storage, which the phone's own storage protection covers. It adds no encryption of its own.
- **Use HTTPS (SSL) for all connections:** Done: the web pages are served over HTTPS by GitHub Pages, and the Feed only allows HTTPS frames. The Android app has no internet permission.
- **Minimize collection of personal data:** Done: no personal data is collected. Only study data (cards, schedule, counts) is kept, on your device.
- **Implement proper data retention policies:** Done: data stays on your device until you delete it: *Reset everything*, uninstalling, clearing the site's data, or *Delete everything* on the Feed. There are no server copies. The reel detection log keeps only the last 40 entries.
- **Secure API keys and credentials:** Done: the apps use no API keys or passwords. Signing secrets live in GitHub Actions secrets, not in the repository. The shared debug key in `tools/` is public on purpose and is only for test builds, never for Google Play.
- **Implement data access logging:** Not applicable: there is no server or database of ours holding user data to log access to.
- **Clearly state your data policy (Privacy Policy):** Done: this document, published at `privacy.html` and linked from the app.
- **Secure database configurations:** Not applicable: there is no database of ours. The on-device file is private to the app.

### API Security

*Secure your application's APIs against common threats*

- **Use proper authentication for all API endpoints:** Not applicable: Reelcards has no API of its own.
- **Implement API rate limiting:** Not applicable: no API to limit.
- **Validate all API inputs:** Not applicable: no API.
- **Implement proper error handling in APIs:** Not applicable: no API.
- **Use API versioning:** Not applicable: no API. (Saved files carry a version number, `v`, so future versions can read old saves.)
- **Implement JWT best practices:** Not applicable: no tokens are used.
- **Protect against CSRF in APIs:** Not applicable: no API.
- **Monitor API usage for suspicious activity:** Not applicable: no API and no usage monitoring, by design.
- **Regularly rotate sensitive API tokens and keys:** Not applicable: there are no API tokens. The Google Play upload key is kept offline by the owner and Google can reset it.

### Infrastructure & Deployment Security

*Secure your hosting environment and deployment pipeline*

- **Implement network security controls:** Not applicable: Reelcards runs no servers. Hosting is by GitHub, under its own controls.
- **Use infrastructure as code with security checks:** Partly: building and publishing are code (GitHub Actions workflows in the repository), but there's no dedicated security scan step yet.
- **Implement proper logging and monitoring:** Partly: GitHub keeps build logs. There is no monitoring of users, by design.
- **Use secure container configurations:** Not applicable: no containers are used.

### Frontend Security

*Secure the client-side of your application*

- **Implement Content Security Policy (CSP):** Partly: the Feed page has a strict policy: its one script is allowed by hash, frames only from YouTube's privacy-enhanced domain and TikTok, and no other connections. The main web demo doesn't have one yet. The extension has the browser's default Manifest V3 policy, which blocks remote and inline scripts.
- **Use subresource integrity for external scripts:** Not applicable: no page loads an external script. The one external resource is the Google Fonts stylesheet, which can't be pinned because Google changes it.
- **Secure cookie usage:** Not applicable: Reelcards sets no cookies. (On the Feed page, YouTube or TikTok may set their own. See that section.)
- **Implement proper CORS policies:** Not applicable: there are no endpoints to protect.
- **Sanitize user-generated content:** Done: text from your notes, Anki files and pasted links is escaped or reduced to plain text before it's shown, and is never inserted as HTML.
- **Implement CSRF protection:** Not applicable: there are no state-changing endpoints or forms. The Feed's policy also forbids form submission.
- **Minimize use of localStorage for sensitive data:** Done: browsers keep only study data and settings, nothing sensitive: no passwords, tokens or personal details.
- **Validate all client-side redirects:** Done: the apps never redirect to an address supplied by a user. Open and share actions use fixed addresses.
- **Use modern framework security features:** Not applicable: no UI framework is used. Pages are plain HTML with escaping and, on the Feed, a content security policy; the Android screens use the system's own views.

### Dependency & Supply Chain Security

*Secure your application's dependencies and third-party components*

- **Regularly scan dependencies for vulnerabilities:** Planned: the apps use no third-party code libraries, only the Android SDK and build tools. Dependabot for build tooling and GitHub Actions is to be switched on.
- **Maintain a dependency inventory:** Done: there are no libraries to list. The fonts and their licences are in `docs/DESIGN.md` and `app/src/main/assets/fonts`, and the Actions used are in `.github/workflows`.
- **Use lockfiles to pin dependency versions:** Partly: the Gradle wrapper version is pinned. There's no lockfile because there are no library dependencies. GitHub Actions are pinned to major versions, not exact commits.
- **Minimize dependency usage:** Done: a project rule: no third-party libraries and no AndroidX. Small readers (Anki files, zstd, SQLite) are written in-house.
- **Schedule regular checks for available software updates:** Planned: builds run on every change; automatic update alerts are to be switched on (see above).

### Security Testing & Verification

*Verify your application's security through testing*

- **Perform regular security testing:** Not yet: functional checks run on every change (128 engine checks, a Java-to-JavaScript cross-check, and the Android code under Robolectric). There's been no penetration test.
- **Implement automated security testing in CI/CD:** Not yet: CI runs functional checks only.
- **Use static application security testing (SAST):** Planned: GitHub's CodeQL, which is free for public repositories, is to be enabled.
- **Use dynamic application security testing (DAST):** Not yet: there is no running service of ours to test; the static pages could be scanned.
- **Test for common OWASP vulnerabilities:** Partly: reviewed by hand for the parts that apply (injection, cross-site scripting, vulnerable components, insecure design). Not tested by an outside party.
- **Setup basic alerts (e.g., unusual logins, increased errors):** Planned: there are no logins or servers to alert on. GitHub's secret-scanning and Dependabot alerts are to be confirmed switched on for the repository.

### Compliance & Documentation

*Ensure your application meets regulatory requirements and is well-documented*

- **Identify applicable regulations:** Done: listed above.
- **Maintain security documentation:** Done: this file, `docs/PLAY-STORE.md`, `docs/ENGINE.md` and `docs/DESIGN.md`, updated with any change that touches data.
- **Create a data flow diagram:** Done: see *Where data goes* above, in words.
- **Implement proper consent mechanisms:** Done: the accessibility permission has its own consent screen (Android's settings only open after *Agree*); the Feed page asks for consent, including a 13-or-over confirmation, before it loads anything; colour matching is off until you switch it on; and numbers are only shared when you tap *Share*.
- **Document third-party service providers:** Done: listed above, under *Who else is involved*.

### Manage Your Secrets

*Make sure your secrets are properly stored and managed.*

- **Implement proper secrets management:** Done: signing secrets live in GitHub Actions secrets. The Google Play upload key is made and kept offline by the owner (`tools/make-upload-key.sh`).
- **Verify no .env or sensitive config files are pushed to Git:** Done: checked on 13 October 2026: no `.env` files, no private keys or passwords. The one keystore in the repository is the public demo debug key, on purpose.
- **Check your Git repository for accidentally committed API keys:** Done: searched the tracked files for key, token and password patterns on 13 October 2026 and found none. If a key is ever pushed, it must be rotated and the old one deleted.
- **Always use production mode (not debug mode) in live apps:** Partly: the Google Play bundle is a release build. The test APK on GitHub Releases is a **debug build**, signed with the shared demo key, meant only for testing. Switching it to a release-signed build needs the owner's own key.

### AI-Specific Security Considerations

*Address security concerns specific to AI-generated applications*

- **Implement proper access controls for AI services:** Not applicable: the apps call no AI service. *Make cards with AI* only copies a prompt, for a chatbot you choose to use yourself.
- **Validate AI-generated database queries:** Not applicable: no database queries are generated by AI while the app runs.
- **Implement fallbacks for AI components:** Done: nothing at run time depends on AI. A chatbot's reply you paste back is read leniently and bad lines are skipped.
- **Stay updated on AI security research:** Ongoing: this project was written with an AI assistant, and its code goes through the automated checks above and human review before release. This checklist is kept in `docs/security-checklist.json` to review each release against.

The checklist as data, for later use: [security-checklist.json](security-checklist.json).
