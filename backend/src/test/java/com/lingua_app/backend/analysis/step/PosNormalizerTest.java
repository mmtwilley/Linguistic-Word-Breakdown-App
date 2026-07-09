package com.lingua_app.backend.analysis.step;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PosNormalizerTest {

    // --- Krdict Korean labels (research Decision 5) -----------------------

    @ParameterizedTest
    @CsvSource({
            "명사, noun",
            "대명사, pron",
            "동사, verb",
            "형용사, adj",
            "부사, adv",
            "조사, particle",
            "수사, num",
            "관형사, det",
            "감탄사, other",
    })
    void krdictKoreanLabels_mapToCanonical(String raw, String expected) {
        assertThat(PosNormalizer.normalize(raw)).contains(expected);
    }

    // --- spelled-out English labels ---------------------------------------

    @ParameterizedTest
    @CsvSource({
            "adjective, adj",
            "adverb, adv",
            "pronoun, pron",
            "preposition, prep",
            "conjunction, conj",
            "determiner, det",
            "article, det",
            "numeral, num",
            "number, num",
            "punctuation, punct",
            "auxiliary, verb",
            "interjection, other",
            "postposition, particle",
    })
    void spelledOutEnglishLabels_mapToCanonical(String raw, String expected) {
        assertThat(PosNormalizer.normalize(raw)).contains(expected);
    }

    // --- Kuromoji Japanese prefixes ----------------------------------------

    @ParameterizedTest
    @CsvSource({
            "名詞, noun",
            "動詞, verb",
            "助詞, particle",
            "形容詞, adj",
            "副詞, adv",
            "助動詞, verb",
            "記号, punct",
            "連体詞, det",
            "接続詞, conj",
            "感動詞, other",
    })
    void kuromojiPrefixes_mapToCanonical(String raw, String expected) {
        assertThat(PosNormalizer.normalize(raw)).contains(expected);
    }

    @Test
    void kuromojiCompoundLabel_matchesOnPrefix() {
        // Kuromoji emits hierarchical labels like 名詞-一般; the leading segment decides.
        assertThat(PosNormalizer.normalize("名詞-一般")).contains("noun");
        assertThat(PosNormalizer.normalize("動詞-自立")).contains("verb");
    }

    // --- canonical labels pass through unchanged ---------------------------

    @ParameterizedTest
    @ValueSource(strings = {"noun", "verb", "adj", "adv", "pron", "prep",
            "conj", "det", "num", "particle", "punct", "other"})
    void canonicalLabels_normalizeToThemselves(String canonical) {
        assertThat(PosNormalizer.normalize(canonical)).contains(canonical);
    }

    // --- compound "noun (pronoun)" matches on head token --------------------

    @Test
    void compoundLabel_matchesOnHeadToken() {
        assertThat(PosNormalizer.normalize("noun (pronoun)")).contains("noun");
        assertThat(PosNormalizer.normalize("verb (auxiliary)")).contains("verb");
    }

    // --- case and whitespace tolerance --------------------------------------

    @Test
    void normalization_isCaseInsensitiveAndTrimmed() {
        assertThat(PosNormalizer.normalize("Adjective")).contains("adj");
        assertThat(PosNormalizer.normalize("  noun  ")).contains("noun");
    }

    // --- unmappable → empty --------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"gibberish", "1234", "품사불명"})
    void unmappableLabels_returnEmpty(String raw) {
        assertThat(PosNormalizer.normalize(raw)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nullOrBlank_returnsEmpty(String raw) {
        assertThat(PosNormalizer.normalize(raw)).isEmpty();
    }
}
