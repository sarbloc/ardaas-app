# Play Console — App content answers

Console path: *Policy and programs → App content*. Data safety is in
`data-safety.md`. Answers describe the app as built from `main` after #121.

| Section | Answer | Why |
|---|---|---|
| Privacy policy | `https://sarbloc.github.io/ardaas-app/privacy.html` | Live, including the Android Auto Backup disclosure (#134). |
| Ads | **No, my app does not contain ads** | No ad SDKs (`android/app/build.gradle.kts`). |
| App access | **All functionality is available without special access** | No login, no paywall. Translation needs only a one-time model download the reviewer can trigger themselves. |
| Advertising ID | **No** | No `AD_ID` permission, no Play services / ads libraries. |
| Government app | **No** | |
| Financial features | **My app doesn't provide any financial features** | |
| Health apps | **No** / none of the listed health features | |
| News app | **No** | |
| Content rating | IARC questionnaire — see below | |
| Target audience and content | **13–15, 16–17, 18+** — see below | |
| Data safety | See `data-safety.md` | |

## Content rating (IARC)

- **Category:** *Reference, News, or Educational* (a prayer reference and composer).
  *All Other App Types* is the fallback if the reviewer disagrees.
- **Violence:** no images, video or interactive violence. The canonical Ardaas
  text does describe historical martyrdom in words ("cut to pieces from each of
  the joints… cut by saws… flayed alive"). If a question asks about *text*
  references to violence, answer **Yes, non-graphic, historical/religious
  text, no depiction**; questions about depicted/graphic violence or blood are
  **No**. Answer it truthfully — a slightly higher rating in some regions is
  better than a rejected or revoked rating.
- **Sexuality, nudity, profanity/crude humour, drugs/alcohol/tobacco,
  gambling (real or simulated), horror/fear:** **No**.
- **User-generated content / interaction:** **No.** Users write a personal
  benti, but it stays on the device; nothing is shared with other users, there
  is no chat, no social features.
- **Shares user location:** **No.** **Digital purchases:** **No.**
- **Religious content:** IARC does not rate religion as such. If a free-text or
  "other content" field appears, say: *Sikh prayer (Ardaas) text with
  transliteration and English translation; reference use.*
- Expected outcome: Everyone / PEGI 3–7 / USK 0–6 range, which lines up with
  the iOS 4+ rating. If the martyrdom answer raises a region higher, accept it.

## Target audience — recommendation: 13+

Pick **13–15, 16–17, 18+**. Answer **No** to "Could your store listing
unintentionally appeal to children?" (no cartoon art, no child-directed
wording).

Tradeoff, briefly:

- **Including under-13 age groups** puts the app under Google's **Families
  policy**: stricter review, Families-specific privacy-policy and metadata
  requirements, restrictions on SDKs and on transmitting device identifiers,
  and Families re-review on every policy change. The app would probably pass
  (no data, no ads), but it is ongoing obligation for no distribution gain.
- **13+ does not stop children using it.** Target audience is about who the
  listing is *designed for*, not an install gate. Families can still install it
  and read it together; the content rating (above) is what age-gates, and it
  will still come out at the "everyone" end.
- **No parity requirement with iOS.** The iOS "4+" is a content *age rating*,
  Play's equivalent of the IARC rating above — not a target-audience
  declaration. Nothing on iOS commits the Android listing to targeting
  children. The privacy policy's "suitable for all ages" sentence remains true
  either way.

Choose an under-13 group only if Sarbloc wants the app positioned as a
children's/family app (e.g. for teaching Ardaas to kids) and is prepared to
take on the Families policy.
