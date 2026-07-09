package com.lingua_app.backend.analysis.step;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Maps part-of-speech labels from every pipeline source (Krdict Korean labels,
 * Claude's spelled-out English, Kuromoji's Japanese prefixes) onto the single
 * canonical vocabulary documented in the validation contract (FR-008, research
 * Decision 5). Unmappable labels return empty so the caller can flag
 * {@code UNKNOWN_POS} instead of guessing.
 */
public final class PosNormalizer {

    private static final Set<String> CANONICAL = Set.of(
            "noun", "verb", "adj", "adv", "pron", "prep",
            "conj", "det", "num", "particle", "punct", "other");

    private static final Map<String, String> MAPPINGS = Map.ofEntries(
            // Krdict Korean labels
            entry("명사", "noun"),
            entry("대명사", "pron"),
            entry("동사", "verb"),
            entry("형용사", "adj"),
            entry("부사", "adv"),
            entry("조사", "particle"),
            entry("수사", "num"),
            entry("관형사", "det"),
            entry("감탄사", "other"),
            // spelled-out English labels
            entry("adjective", "adj"),
            entry("adverb", "adv"),
            entry("pronoun", "pron"),
            entry("preposition", "prep"),
            entry("conjunction", "conj"),
            entry("determiner", "det"),
            entry("article", "det"),
            entry("numeral", "num"),
            entry("number", "num"),
            entry("punctuation", "punct"),
            entry("auxiliary", "verb"),
            entry("interjection", "other"),
            entry("postposition", "particle"),
            // Kuromoji Japanese prefixes
            entry("名詞", "noun"),
            entry("動詞", "verb"),
            entry("助詞", "particle"),
            entry("形容詞", "adj"),
            entry("副詞", "adv"),
            entry("助動詞", "verb"),
            entry("記号", "punct"),
            entry("連体詞", "det"),
            entry("接続詞", "conj"),
            entry("感動詞", "other"));

    private PosNormalizer() {
    }

    /**
     * Returns the canonical label for {@code raw}, or empty when no mapping is
     * known. Compound labels ("noun (pronoun)", "名詞-一般") match on their head
     * token; lookup is trimmed and case-insensitive.
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String head = raw.trim().split("[\\s(\\-]+", 2)[0].toLowerCase(Locale.ROOT);
        if (CANONICAL.contains(head)) {
            return Optional.of(head);
        }
        return Optional.ofNullable(MAPPINGS.get(head));
    }
}
