# Extension vs Backend Word-Card Parity Report

## Summary

- Sentences: 10 (extension failures: 0, backend failures: 0)
- Aligned cards (same surface): 45
- Segmentation: extension-only cards 19, backend-only cards 11
- Field mismatches on aligned cards — lemma: 2, pos: 24, gloss: 29, romanization: 9
- Backend sentences with dropped input: 3; out-of-order card violations: 4

Generated 2026-07-05T20:51:41.949Z

## Findings and root causes

1. **Chinese: total drop (0 cards on both sentences).** Two compounding bugs.
   `DictionaryStep.loadCedict()` silently no-ops because `dict/cedict_ts.u8` is not
   bundled in resources (DictionaryStep.java:58-61), so `lookupChinese` emits nothing.
   Then ClaudeStep derives its unresolved list *from ctx.words* (ClaudeStep.java:53-58),
   so tokens DictionaryStep never emitted are invisible to the Claude fallback — the
   "unmatched characters are skipped; ClaudeStep handles them" comment
   (DictionaryStep.java:130-131) is false. The last-resort tier can never fire for Chinese.

2. **English: dropped tokens, same mechanism.** `lookupEnglish` skips tokens the Free
   Dictionary 404s on (DictionaryStep.java:243-245) — "couldn't" vanished from eng-2.
   Korean does this correctly (surface-only card on miss, DictionaryStep.java:188);
   English and Chinese need the same contract: *every input token must reach ctx.words.*

3. **English: context-free first-sense lookups are wrong on most function words.**
   `parseFreeDictEntry` takes `meanings[0].definitions[0]` — so "the" → adverb,
   "quick" → noun "raw or sensitive flesh", "jumps" → plural-noun entry with lemma
   "jumps". 16 of 16 aligned English cards had a POS or gloss mismatch vs Claude.
   A dictionary cannot disambiguate POS in context; English either needs a POS tagger
   before lookup or should go to Claude like the other languages.

4. **Card order is not input order.** ClaudeStep removes the cards it re-resolved and
   appends its results at the end (ClaudeStep.java:101-107) — Krdict hits come first,
   Claude fills last (kor-1/2/3, jpn-3). Cards must be re-sorted to input position.

5. **POS vocabulary is unnormalized.** Krdict returns Korean labels (명사, 부사) passed
   through raw; Claude and Free Dictionary return assorted English labels ("noun
   (pronoun)", "adverb"). Same response mixes both. Needs a single normalized POS enum
   (the extension enforces one: noun/verb/adj/... via VALID_POS).

6. **First-sense problem hits Korean too.** Krdict trans_word[0] for 정말 is "fact"
   (POS 명사) where context demands adverb "really". Dictionary tiers need a
   context-check or Claude verification for polysemous words — this is where a
   per-card confidence level belongs.

7. **Japanese segmentation: morpheme-level vs word-level, not a bug but a product
   decision.** Kuromoji splits 飲みます → 飲み+ます, 行きました → 行き+まし+た (5 cards
   where the extension shows 2). Linguistically defensible, but a まし card glossed
   "polite auxiliary verb" is noise for a learner. Also は romanized "ha" (reading-based)
   where the extension correctly gives topic-particle "wa".

8. **Korean romanization styles disagree (9 mismatches).** Backend outputs hyphenated
   letter-mechanical RR ("gat-i", "bwass-eoyo", "yeonghwaleul" — 를 should be "reul");
   extension outputs sound-change-free RR ("gati", "bwateoyo"). Both miss assimilation
   (좋네요 → jonneyo; ext "jotneyo", backend "johneyo"). Pick one convention.

9. **What matched:** translations were identical on all 10 sentences (DeepL on both
   sides), Korean surface segmentation matched exactly (both whitespace-based), and
   content-word lemmas almost always agreed (2 lemma mismatches in 45 aligned cards,
   both English lemmatization misses). The tiered architecture is not fundamentally
   broken — the failures are in the tier hand-offs and dictionary sense selection,
   not the concept.

## kor-1: 오늘 날씨가 정말 좋네요

- Translation (extension): The weather is really nice today.
- Translation (backend):   The weather is really nice today.
- Language (backend): kor
- Cards: extension 4, backend 4
- **Backend cards out of input order** (1 violations)

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| 오늘 | 오늘 | 오늘 / 오늘 | noun / 명사 | today / today | oneul / oneul | match |
| 날씨가 | — | 날씨 / — | noun / — | weather / — | nalssiga / — | EXT-ONLY |
| 정말 | 정말 | 정말 / 정말 | adv / 명사 | really, truly / fact | jeongmal / jeongmal | POS GLOSS |
| — | 날씨가 | — / 날씨 | — / noun | — / weather | — / nalssiga | BE-ONLY |
| 좋네요 | 좋네요 | 좋다 / 좋다 | adj / verb | good, nice / to be good/nice | jotneyo / johneyo | POS GLOSS ROMAN |

## kor-2: 저는 어제 친구와 같이 영화를 봤어요

- Translation (extension): I went to see a movie with a friend yesterday.
- Translation (backend):   I went to see a movie with a friend yesterday.
- Language (backend): kor
- Cards: extension 6, backend 6
- **Backend cards out of input order** (1 violations)

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| 저는 | — | 저 / — | pron / — | I (humble form) / — | jeoneun / — | EXT-ONLY |
| 어제 | 어제 | 어제 / 어제 | adv / 명사 | yesterday / yesterday | eoje / eoje | POS |
| 친구와 | — | 친구 / — | noun / — | friend / — | chinguwa / — | EXT-ONLY |
| 같이 | 같이 | 같이 / 같이 | adv / 부사 | together, alike / together | gati / gat-i | GLOSS |
| — | 저는 | — / 저 | — / noun (pronoun) | — / I (humble/polite first-person pronoun) | — / jeoneun | BE-ONLY |
| — | 친구와 | — / 친구 | — / noun | — / friend | — / chinguwa | BE-ONLY |
| 영화를 | 영화를 | 영화 / 영화 | noun / noun | movie, film / movie, film | yeonghwareul / yeonghwaleul | ROMAN |
| 봤어요 | 봤어요 | 보다 / 보다 | verb / verb | to watch, to see / to see, to watch | bwateoyo / bwass-eoyo | GLOSS ROMAN |

## kor-3: 한국어를 배우는 것은 어렵지만 재미있어요

- Translation (extension): Learning Korean is difficult but fun.
- Translation (backend):   Learning Korean is difficult but fun.
- Language (backend): kor
- Cards: extension 5, backend 5
- **Backend cards out of input order** (1 violations)

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| 한국어를 | — | 한국어 / — | noun / — | Korean language / — | hangukeoreul / — | EXT-ONLY |
| 배우는 | 배우는 | 배우다 / 배우다 | verb / 동사 | to learn / learn | baeuneun / baeuneun | match |
| — | 한국어를 | — / 한국어 | — / noun | — / Korean language | — / hangug-eoleul | BE-ONLY |
| 것은 | 것은 | 것 / 것 | noun / noun | thing; nominalizer / thing; nominalizer | geoteun / geos-eun | ROMAN |
| 어렵지만 | 어렵지만 | 어렵다 / 어렵다 | adj / adjective | difficult, hard / to be difficult | eoryeopjiman / eolyeobjiman | GLOSS ROMAN |
| 재미있어요 | 재미있어요 | 재미있다 / 재미있다 | adj / adjective | fun, interesting / to be fun; to be interesting | jaemiiteoyo / jaemiiss-eoyo | GLOSS ROMAN |

## jpn-1: 私は毎朝コーヒーを飲みます

- Translation (extension): I drink coffee every morning.
- Translation (backend):   I drink coffee every morning.
- Language (backend): jpn
- Cards: extension 6, backend 7

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| 私 | 私 | 私 / 私 | pron / noun | I, me / I, me (first-person pronoun) | watashi / watashi | POS GLOSS |
| は | は | は / は | other / particle | topic marker particle / topic marker particle | wa / ha | POS ROMAN |
| 毎朝 | 毎朝 | 毎朝 / 毎朝 | noun / noun | every morning / every morning | maiasa / maiasa | match |
| コーヒー | コーヒー | コーヒー / コーヒー | noun / noun | coffee / coffee | kōhī / kōhī | match |
| を | を | を / を | other / particle | object marker particle / direct object marker particle | wo / wo | POS GLOSS |
| 飲みます | — | 飲む / — | verb / — | to drink / — | nomimasu / — | EXT-ONLY |
| — | 飲み | — / 飲む | — / verb | — / to drink | — / nomi | BE-ONLY |
| — | ます | — / ます | — / particle | — / polite verb ending (present/future affirmative) | — / masu | BE-ONLY |

## jpn-2: 昨日友達と映画を見に行きました

- Translation (extension): I went to see a movie with a friend yesterday.
- Translation (backend):   I went to see a movie with a friend yesterday.
- Language (backend): jpn
- Cards: extension 7, backend 10

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| 昨日 | 昨日 | 昨日 / 昨日 | noun / noun | yesterday / yesterday | kinō / kinou | ROMAN |
| 友達 | 友達 | 友達 / 友達 | noun / noun | friend(s) / friend(s) | tomodachi / tomodachi | match |
| と | と | と / と | prep / particle | with (particle) / with; and (comitative/quotative particle) | to / to | POS GLOSS |
| 映画 | 映画 | 映画 / 映画 | noun / noun | movie, film / movie; film | eiga / eiga | match |
| を | を | を / を | prep / particle | object marker (particle) / direct object marker | wo / wo | POS GLOSS |
| 見に | — | 見る / — | verb / — | to see, watch / — | mi ni / — | EXT-ONLY |
| 行きました | — | 行く / — | verb / — | went (past polite) / — | ikimashita / — | EXT-ONLY |
| — | 見 | — / 見る | — / verb | — / to see; to watch | — / mi | BE-ONLY |
| — | に | — / に | — / particle | — / purpose marker (in order to) | — / ni | BE-ONLY |
| — | 行き | — / 行く | — / verb | — / to go | — / iki | BE-ONLY |
| — | まし | — / ます | — / verb | — / polite auxiliary verb | — / mashi | BE-ONLY |
| — | た | — / た | — / particle | — / past tense auxiliary | — / ta | BE-ONLY |

## jpn-3: 日本語の勉強は難しいですが、楽しいです

- Translation (extension): Studying Japanese is difficult, but it's fun.
- Translation (backend):   Studying Japanese is difficult, but it's fun.
- Language (backend): jpn
- Cards: extension 9, backend 9
- **Backend cards out of input order** (1 violations)

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| 日本語 | 日本語 | 日本語 / 日本語 | noun / noun | Japanese language / Japanese language | nihongo / nihongo | match |
| の | の | の / の | other / particle | possessive/nominalizing particle / possessive/nominalizing particle | no / no | POS |
| 勉強 | 勉強 | 勉強 / 勉強 | noun / noun | study, learning / study, studying | benkyou / benkyou | GLOSS |
| は | は | は / は | other / particle | topic marker particle / topic marker particle | wa / ha | POS ROMAN |
| 難しい | 難しい | 難しい / 難しい | adj / adjective | difficult, hard / difficult, hard | muzukashii / muzukashii | match |
| です | です | です / です | other / verb | polite copula (is/am/are) / polite copula (is/are) | desu / desu | POS GLOSS |
| が | が | が / が | conj / particle | but, however / conjunction/contrast particle (but) | ga / ga | POS GLOSS |
| 楽しい | 楽しい | 楽しい / 楽しい | adj / adjective | fun, enjoyable / fun, enjoyable | — / tanoshii | match |
| です | です | です / です | other / verb | polite copula (is/am/are) / polite copula (is/are) | desu / desu | POS GLOSS |

## cmn-1: 我昨天和朋友一起看了电影

- Translation (extension): I went to see a movie with a friend yesterday.
- Translation (backend):   I went to see a movie with a friend yesterday.
- Language (backend): cmn
- Cards: extension 7, backend 0
- **Backend dropped input**: covers 0/12 non-punct chars

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| 我 | — | 我 / — | pron / — | I, me / — | wǒ / — | EXT-ONLY |
| 昨天 | — | 昨天 / — | noun / — | yesterday / — | zuótiān / — | EXT-ONLY |
| 和 | — | 和 / — | prep / — | with, and / — | hé / — | EXT-ONLY |
| 朋友 | — | 朋友 / — | noun / — | friend / — | péngyou / — | EXT-ONLY |
| 一起 | — | 一起 / — | adv / — | together / — | yīqǐ / — | EXT-ONLY |
| 看了 | — | 看 / — | verb / — | watched, looked at / — | kàn le / — | EXT-ONLY |
| 电影 | — | 电影 / — | noun / — | movie, film / — | diànyǐng / — | EXT-ONLY |

## cmn-2: 学习中文很有意思

- Translation (extension): Learning Chinese is really interesting.
- Translation (backend):   Learning Chinese is really interesting.
- Language (backend): cmn
- Cards: extension 4, backend 0
- **Backend dropped input**: covers 0/8 non-punct chars

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| 学习 | — | 学习 / — | verb / — | to study, to learn / — | xuéxí / — | EXT-ONLY |
| 中文 | — | 中文 / — | noun / — | Chinese language / — | Zhōngwén / — | EXT-ONLY |
| 很 | — | 很 / — | adv / — | very / — | hěn / — | EXT-ONLY |
| 有意思 | — | 有意思 / — | adj / — | interesting, fun / — | yǒu yìsi / — | EXT-ONLY |

## eng-1: The quick brown fox jumps over the lazy dog

- Translation (extension): The quick brown fox jumps over the lazy dog.
- Translation (backend):   The quick brown fox jumps over the lazy dog
- Language (backend): lat
- Cards: extension 9, backend 9

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| The | The | the / the | det / adverb | definite article / With a comparative or with more and a verb phrase, establishes a correlation with one or more other such comparatives. | — / — | POS GLOSS |
| quick | quick | quick / quick | adj / noun | fast, speedy / Raw or sensitive flesh, especially that underneath finger and toe nails. | — / — | POS GLOSS |
| brown | brown | brown / brown | adj / noun | dark orange color / A colour like that of chocolate or coffee. | — / — | POS GLOSS |
| fox | fox | fox / fox | noun / noun | a wild canine animal / A red fox, small carnivore (Vulpes vulpes), related to dogs and wolves, with red or silver fur and a bushy tail. | — / — | GLOSS |
| jumps | jumps | jump / jumps | verb / noun | leaps into the air / The act of jumping; a leap; a spring; a bound. | — / — | LEMMA POS GLOSS |
| over | over | over / over | prep / noun | above and across / A set of six legal balls bowled. | — / — | POS GLOSS |
| the | the | the / the | det / adverb | definite article / With a comparative or with more and a verb phrase, establishes a correlation with one or more other such comparatives. | — / — | POS GLOSS |
| lazy | lazy | lazy / lazy | adj / noun | unwilling to work / A lazy person. | — / — | POS GLOSS |
| dog | dog | dog / dog | noun / noun | a domestic canine / A mammal, Canis familiaris or Canis lupus familiaris, that has been domesticated for thousands of years, of highly variable appearance due to human breeding. | — / — | GLOSS |

## eng-2: She couldn't have finished the report yesterday

- Translation (extension): She couldn't have finished the report yesterday.
- Translation (backend):   She couldn't have finished the report yesterday
- Language (backend): lat
- Cards: extension 7, backend 6
- **Backend dropped input**: covers 33/41 non-punct chars

| ext surface | be surface | lemma (ext/be) | pos (ext/be) | gloss (ext/be) | roman (ext/be) | flags |
|---|---|---|---|---|---|---|
| She | She | she / she | pron / noun | third person singular female / A female. | — / — | POS GLOSS |
| couldn't | — | can / — | verb / — | past negative modal verb / — | — / — | EXT-ONLY |
| have | have | have / have | verb / noun | auxiliary for perfect aspect / A wealthy or privileged person. | — / — | POS GLOSS |
| finished | finished | finish / finished | verb / verb | completed, brought to end / To complete (something). | — / — | LEMMA GLOSS |
| the | the | the / the | det / adverb | definite article / With a comparative or with more and a verb phrase, establishes a correlation with one or more other such comparatives. | — / — | POS GLOSS |
| report | report | report / report | noun / noun | formal written document / A piece of information describing, or an account of certain events given or presented to someone, with the most common adpositions being by (referring to creator of the report) and on (referring to the subject). | — / — | GLOSS |
| yesterday | yesterday | yesterday / yesterday | adv / noun | the day before today / The day immediately before today; one day ago. | — / — | POS GLOSS |

