# Gurmukhi → Roman transliteration scheme

`Ardaas/Models/GurmukhiTransliterator.swift` is a deterministic, rule-based
transliterator: pure Swift, no dependencies, no network, no model. It is used
for (a) romanizing a user-authored benti and (b) generating a transliteration
layer for variants that ship without one (the Buddha Dal text).

The doc comment on the type is the canonical spec; this file is the short
version for review. **The scheme is a style choice — worth a proof-read.**

## House style

The target is the plain-ASCII "Gurbani style" already used by the bundled SGPC
layer — `Waheguru Ji Ki Fateh ||` — not academic ISO 15919 (`vāhigurū jī kī
fatahi`). No diacritics; every Gurmukhi word is Title-Cased.

The bundled SGPC transliteration is human-authored and internally inconsistent
(`ਸਭ ਥਾਂਈ` appears as both "Sabh Thaai" and "Sabh Thai"; `ਸਿਮਰਿ ਕੈ` deletes its
schwa but `ਧਿਆਇ` becomes "Dhiyae"). It is a **style reference, not a fitting
target** — reproducing it with rules is not achievable. This engine aims at the
same look while being internally consistent.

## Mapping

| Class | Mapping |
| --- | --- |
| Stops etc. | ਕ k · ਖ kh · ਗ g · ਘ gh · ਙ ng · ਚ ch · ਛ chh · ਜ j · ਝ jh · ਞ nj · ਟ t · ਠ th · ਡ d · ਢ dh · ਣ n · ਤ t · ਥ th · ਦ d · ਧ dh · ਨ n · ਪ p · ਫ ph · ਬ b · ਭ bh · ਮ m · ਯ y · ਰ r · ਲ l · ਵ v · ੜ rh · ਸ s · ਹ h |
| Pairin bindi | ਸ਼ sh · ਖ਼ kh · ਗ਼ gh · ਜ਼ z · ਫ਼ f · ਲ਼ l (precomposed **and** base + U+0A3C nukta) |
| Vowels | ਅ a · ਆ/ਾ aa · ਇ/ਿ i · ਈ/ੀ i · ਉ/ੁ u · ਊ/ੂ u · ਏ/ੇ e · ਐ/ੈ ai · ਓ/ੋ o · ਔ/ੌ au |
| Nasals | ੰ tippi and ਂ bindi → `n`, or `m` before a labial (ਪ ਫ ਬ ਭ ਮ ਫ਼) |
| Addak | doubles the next consonant's first letter (ਦਿੱਤੇ → Ditte, ਸੱਚੇ → Sacche) |
| Conjuncts | ੍ + ਹ/ਰ/ਵ join the onset (ਪ੍ਰ → pr, ਕ੍ਰਿ → kri, ਤਿਨ੍ਹਾਂ → Tinhaan) |
| Symbols | ੴ → `Ik-Onkar` · ॥ → `||` · । → `|` (standalone tokens: always space-separated, never double-spaced) · ੦–੯ → `0`–`9` |

Deliberate choices worth flagging:

- **Retroflex and dental collapse** (ਟ and ਤ are both `t`) — plain ASCII has no
  way to write the contrast, and the SGPC layer doesn't either.
- **Only a/aa length is written.** ਿ/ੀ both give `i` and ੁ/ੂ both give `u`,
  because that is what the house style's commonest words do: `Ji`, `Ki`, `Sri`,
  `Guru` — not `Jee`, `Kee`, `Sree`, `Guroo`.
- **Glides.** A vowel-initial syllable after i/u takes one: ਧਿਆਨ → `Dhiyaan`,
  ਸੁਆਸ → `Suwaas`.
- **Gemination is softened twice** for readability: aspirates are not doubled
  (ਸਿੱਖੀ → `Sikhi`, not `Sikkhi`) and a gemination landing word-final is
  written single (ਸਰਬੱਤ → `Sarbat`, ਸਿੱਖ → `Sikh`).
- **A homorganic nasal before the identical consonant is written once**:
  ਅੰਮ੍ਰਿਤਸਰ → `Amritsar`, ਮੰਨ → `Man`.
- Latin/ASCII already in the input passes through untouched and un-recased, and
  line breaks and spacing are preserved, so a mixed-script benti survives.

## Schwa deletion

The biggest quality lever, and the part most likely to be wrong on an unusual
word. Three rules, per word:

1. **Final short-vowel elision** — a word-final sihari (ਿ) or aunkar (ੁ) is
   silent, the standard Gurbani reading convention: ਸਿਮਰਿ → `Simar`, ਸਤਿ →
   `Sat`, ਅਮਰਦਾਸੁ → `Amardaas`.
2. **Final schwa deletion** — the inherent `a` of a word-final consonant drops:
   ਨਾਨਕ → `Naanak`, not `Naanaka`.
3. **Medial schwa deletion** — scanning right to left, a non-initial,
   non-final inherent `a` drops when both neighbours still have a pronounced
   vowel (`VCəCV → VCCV`): ਅਰਦਾਸ → `Ardaas`, ਪਾਤਸ਼ਾਹੀ → `Paatshaahi`,
   ਹਰਗੋਬਿੰਦ → `Hargobind`. The first syllable of a word is never emptied, a
   syllable carrying a nasal keeps its vowel, and nothing is deleted before a
   vowel-initial syllable, where it would merge two syllables (ਭਗਉਤੀ →
   `Bhagauti`, not `Bhaguti`).

### Known limitations

- The rule is orthographic: it can't see morpheme boundaries, so compounds may
  keep or drop a schwa a reader would place differently.
- Two adjacent deletable schwas: only the rightmost goes (the left one is then
  blocked). ਗੁਰਦਵਾਰਿਆਂ → `Guradvaariyaan`, where a reader says *gurdvaariyaan*.
  (The same word spelled ਗੁਰਦੁਆਰਿਆਂ comes out right: `Gurduwaariyaan`.)
- Rule 1 wrongly silences a final short vowel in the rare modern Punjabi word
  that genuinely ends in one.
- Nothing restores the schwa Punjabi keeps before certain sonorant clusters.
- A vowel-vowel juncture with no glide is written plain, so ਲਈਂ → `Lain` can be
  misread as the `ai` of ਐ. `La-in` would be no clearer, and the house style
  writes such junctures plain too ("Nau", "Bhagauti").

## Exception lexicon

A deliberately small table (~13 entries) of high-frequency words whose rule
output reads wrong: ਵਾਹਿਗੁਰੂ → `Waheguru` (rules: *Vaahiguru*), ਖਾਲਸਾ →
`Khalsa` (*Khaalsaa*), ਸਤਿਗੁਰੂ → `Satguru`, ਫ਼ਤਹਿ/ਫਤਿਹ → `Fateh`, ਨੂੰ → `Noon`.
A few words the rules already get right (ਗੁਰੂ, ਜੀ, ਸ੍ਰੀ, ਸਿੰਘ, ਅਰਦਾਸ) are pinned
there too, so a later rule change can't silently alter them.

It is a **quality patch, not a dictionary**. It matches whole words only, so
inflected forms fall back to the rules — ਖਾਲਸਾ is `Khalsa` but ਖਾਲਸੇ is
`Khaalse`. Growing it is how you'd fix a specific bad word; growing it a lot is
how you'd end up maintaining a dictionary, which is not the goal.

## Known divergences from the bundled SGPC layer

Expected, and asserted as *this engine's* output in the tests:

| Gurmukhi | Engine | SGPC layer |
| --- | --- | --- |
| ਵਾਹਿਗੁਰੂ ਜੀ ਕਾ ਖਾਲਸਾ | Waheguru Ji Kaa Khalsa | Waheguru Ji Ka Khalsa |
| ਨਾਨਕ ਨਾਮ ਚੜ੍ਹਦੀ ਕਲਾ | Naanak Naam Charhdi Kalaa | Naanak Naam Chardi Kala |
| ਸਭ ਥਾਂਈ | Sabh Thaani | Sabh Thaai / Sabh Thai |
| ਪਾਤਸ਼ਾਹੀ | Paatshaahi | Paatshaahee |
| ਹੋਇ | Hoi | Ho-e |
| ਅਟੱਲ | Atal | Attal |
| ਮਾਫ (spelled without nukta) | Maaph | Maaf |

Tests live in `ArdaasTests/GurmukhiTransliteratorTests.swift`.
