# Gurmukhi → Roman transliteration scheme

`Ardaas/Models/GurmukhiTransliterator.swift` is a deterministic, rule-based
transliterator: pure Swift, no dependencies, no network, no model. It is used
for (a) romanizing a user-authored benti and (b) generating a transliteration
layer for variants that ship without one (the Buddha Dal text).

The doc comment on the type is the canonical spec; this file is the short
version for review. **The scheme is a style choice — worth a proof-read.**

## The generated Buddha Dal layer

`Ardaas/Resources/ardaas-buddha-dal.json` ships a `transliteration` for every
segment that is **engine output, not hand-authored** — that is what makes the
canonical text and a romanized benti look like one voice. It is checked in
rather than computed at launch so it can be proof-read and diffed like any
other scripture change.

`testBundledBuddhaDalLayerMatchesTheEngine` re-derives every segment and
asserts equality, so the two can never drift: change a rule and CI fails until
the layer is regenerated (and re-proof-read). Regenerating is mechanical —
map `GurmukhiTransliterator.transliterate` over each segment's `gurmukhi`.

`Ardaas/Resources/occasions.json` — the list of paaths and occasions that can
fill the `…..` slot — is generated and pinned the same way, by
`testBundledTransliterationMatchesTheEngine`, so a chosen occasion and a
romanized benti read in one voice.

The SGPC layer is human-authored and deliberately **not** pinned this way.

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
| Vowels | ਅ a · ਆ/ਾ aa · ਇ/ਿ i · ਈ/ੀ i · ਉ/ੁ u · ਊ/ੂ u · ਏ/ੇ e · ਐ/ੈ ai · ਓ/ੋ o · ਔ/ੌ au · bare ੳ o |
| Nasals | ੰ tippi → `n`, or `m` before a labial (ਪ ਫ ਬ ਭ ਮ ਫ਼); ਂ bindi → `(n)` |
| Addak | doubles the next consonant's first letter (ਦਿੱਤੇ → Ditte, ਸੱਚੇ → Sacche) |
| Conjuncts | ੍ + ਹ/ਰ/ਵ join the onset (ਪ੍ਰ → pr, ਕ੍ਰਿ → kri, ਤਿਨ੍ਹਾਂ → Tinhaan) |
| Symbols | ੴ → `Ik-Onkar` · ॥ → `||` · । → `|` (standalone tokens: separated from an adjacent word or Latin text by one space, never double-spaced, following punctuation left alone) · ੦–੯ → `0`–`9` |

Deliberate choices worth flagging:

- **Retroflex and dental collapse** (ਟ and ਤ are both `t`) — plain ASCII has no
  way to write the contrast, and the SGPC layer doesn't either.
- **Only a/aa length is written.** ਿ/ੀ both give `i` and ੁ/ੂ both give `u`,
  because that is what the house style's commonest words do: `Ji`, `Ki`, `Sri`,
  `Guru` — not `Jee`, `Kee`, `Sree`, `Guroo`.
- **Glides.** A vowel-initial syllable after i/u takes one: ਧਿਆਨ → `Dhiyaan`,
  ਸੁਆਸ → `Suwaas`.
- **An independent ਉ that opens its own syllable** — i.e. one that takes a
  glide — reads `o`, and is written `ou` so it stays distinct from ਓ: ਜੀਉ →
  `Jiyou`, where ਜੀਓ → `Jiyo`. Elsewhere ਉ is the second half of a plain vowel
  juncture (ਨਉ → `Nau`, ਨਾਉ → `Naau`, ਭਗਉਤੀ → `Bhagauti`) or a word-initial
  `u` (ਉਪਦੇਸ਼ → `Updesh`), and stays `u`.
- **Tippi and bindi are written differently.** Tippi is a nasal *consonant*, so
  it is plain and assimilates: ਸਿੰਘ → `Singh`, ਪਿੰਡੁ → `Pind`, ਅੰਮ੍ਰਿਤਸਰ →
  `Amritsar`. Bindi marks a nasalized *vowel*, so it is parenthesised and never
  assimilates: ਸਿੰਘਾਂ → `Singhaa(n)`, ਲਈਂ → `Lai(n)`, ਥਾਂਈ → `Thaa(n)i`. The
  brackets are what keep the pair apart in Roman — `Singh` the name against
  `Singhaa(n)` its oblique plural. (The rare adak bindi ਁ counts as a bindi.)
- **Gemination is softened twice** for readability: aspirates are not doubled
  (ਸਿੱਖੀ → `Sikhi`, not `Sikkhi`) and a gemination landing word-final is
  written single (ਸਰਬੱਤ → `Sarbat`, ਸਿੱਖ → `Sikh`).
- **A homorganic tippi before the identical consonant is written once**:
  ਅੰਮ੍ਰਿਤਸਰ → `Amritsar`, ਮੰਨ → `Man`.
- Latin/ASCII already in the input passes through untouched and un-recased, and
  line breaks and spacing are preserved, so a mixed-script benti survives.

## ਹ and the vowel it carries

ਹ is weak: it never hosts a deleted vowel, and it colours a preceding inherent
schwa. Both apply before schwa deletion:

- **A schwa before a word-final ਹ + sihari coalesces into `eh`.** A word ending
  in ਹਿ is read /-ɛh/, not /-əhi/, so the schwa becomes `e` and the ਹ closes the
  syllable: ਪਹਿ → `Peh`, ਮਹਿ → `Meh`, ਕਹਿ → `Keh`, ਕਰਹਿ → `Kareh`. Two things
  narrow it:
  - **Word-final only.** Mid-word ਹਿ takes the ordinary rules, so ਸਹਿਤ →
    `Sahit`, ਮਹਿਮਾ → `Mahimaa`, ਪਹਿਲਾ → `Pahilaa`.
  - **The inherent schwa only.** A written vowel before ਹ keeps its own
    syllable, so ਸਾਹਿਬ stays `Saahib`, ਬੋਹਿਥ stays `Bohith` and ਨਾਹਿ stays
    `Naahi`.
- **A short vowel on ਹ is never elided.** ਕਹੁ → `Kahu`, not `Kah`.

ਵਾਹਿਗੁਰੂ → `Waheguru` shows the same `e` after a long vowel, but that is a
lexicalized reduction rather than a live rule, so it stays in the exception
lexicon: applying it generally would also give *Saaheb* and *Boheth*.

Restricting the rule to word-final position is what keeps the tatsama
borrowings that hold their /əhi/ intact — ਸਹਿਤ reads `Sahit`. The words that
*are* reduced mid-word aren't predictable from the spelling (ਰਹਿਤ is spelled
exactly like ਸਹਿਤ but read /rɛhət/), so they are lexicon entries rather than
rule output: ਰਹਿਤ → `Rehat`.

## Schwa deletion

The biggest quality lever, and the part most likely to be wrong on an unusual
word. Three rules, per word:

1. **Final short-vowel elision** — a word-final sihari (ਿ) or aunkar (ੁ) is
   silent, the standard Gurbani reading convention: ਸਿਮਰਿ → `Simar`, ਸਤਿ →
   `Sat`, ਅਮਰਦਾਸੁ → `Amardaas`. Except on ਹ, per the section above.
2. **Final schwa deletion** — the inherent `a` of a word-final consonant drops:
   ਨਾਨਕ → `Naanak`, not `Naanaka`.
3. **Medial schwa deletion** — scanning right to left, a non-initial,
   non-final inherent `a` drops when both neighbours still have a pronounced
   vowel (`VCəCV → VCCV`): ਅਰਦਾਸ → `Ardaas`, ਪਾਤਸ਼ਾਹੀ → `Paatshaahi`,
   ਹਰਗੋਬਿੰਦ → `Hargobind`. The first syllable of a word is never emptied, a
   syllable carrying a nasal keeps its vowel, nothing is deleted before a
   vowel-initial syllable, where it would merge two syllables (ਭਗਉਤੀ →
   `Bhagauti`, not `Bhaguti`), and nothing is deleted before a conjunct
   cluster, where it would pile up three consonants (ਸਮਗ੍ਰੀ → `Samagri`, not
   `Samgri`).

### Known limitations

- The rule is orthographic: it can't see morpheme boundaries, so compounds may
  keep or drop a schwa a reader would place differently.
- Two adjacent deletable schwas: only the rightmost goes (the left one is then
  blocked). ਗੁਰਦਵਾਰਿਆਂ → `Guradvaariyaa(n)`, where a reader says
  *gurdvaariyaan*. (The same word spelled ਗੁਰਦੁਆਰਿਆਂ comes out right:
  `Gurduwaariyaa(n)`.) ਸੁਖਮਨੀ had the same shape and is now a lexicon entry,
  since it reaches bundled content.
- Rule 1 wrongly silences a final short vowel in the rare modern Punjabi word
  that genuinely ends in one — ਹ aside, which is now exempt.
- Nothing restores the schwa Punjabi keeps before certain sonorant clusters;
  only the conjunct-cluster case is handled.
- A word whose *mid-word* ਹਿ is reduced by readers comes out unreduced until it
  is lexicalized: ਪਹਿਲਾ → `Pahilaa`. The words that reach bundled content are
  lexicalized instead — ਰਹਿਤ → `Rehat`, and ਰਹਿਰਾਸ → `Reharaas`, ਸਹਿਜ → `Sehaj`
  for `occasions.json` (#69) — so what is left on the rules only reaches a
  user-authored benti.
- A vowel-vowel juncture with no glide is written plain, so ਲਈਂ → `Lai(n)` can
  be misread as the `ai` of ਐ. `La-i(n)` would be no clearer, and the house
  style writes such junctures plain too ("Nau", "Bhagauti").

## Exception lexicon

A deliberately small table — a dozen or so words, several of them just alternate
spellings of the same one — covering high-frequency words whose rule output reads
wrong: ਵਾਹਿਗੁਰੂ → `Waheguru` (rules: *Vaahiguru*), ਖਾਲਸਾ → `Khalsa`
(*Khaalsaa*), ਸਤਿਗੁਰੂ → `Satguru`, ਫ਼ਤਹਿ/ਫਤਿਹ/ਫਤੇ → `Fateh`, ਨੂੰ → `Noon`,
ਰਹਿਤ → `Rehat` (*Rahit*), ਰਹਿਰਾਸ → `Reharaas` (*Rahiraas*), ਸਹਿਜ → `Sehaj`
(*Sahij*), ਸੁਖਮਨੀ → `Sukhmani` (*Sukhamni*).
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
| ਸਭ ਥਾਂਈ | Sabh Thaa(n)i | Sabh Thaai / Sabh Thai |
| ਪਾਤਸ਼ਾਹੀ | Paatshaahi | Paatshaahee |
| ਹੋਇ | Hoi | Ho-e |
| ਅਟੱਲ | Atal | Attal |
| ਮਾਫ (spelled without nukta) | Maaph | Maaf |
| ਸਿੰਘਾਂ | Singhaa(n) | Singhaa |

The bindi brackets are the widest of these: the SGPC layer mostly drops the
bindi rather than writing it, so every oblique plural differs. That layer is
human-authored and **not** regenerated, so it keeps its own spelling.

Tests live in `ArdaasTests/GurmukhiTransliteratorTests.swift`.
