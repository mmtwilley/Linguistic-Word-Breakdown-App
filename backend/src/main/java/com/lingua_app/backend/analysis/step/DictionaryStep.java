package com.lingua_app.backend.analysis.step;

import com.lingua_app.backend.AppProperties;
import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.analysis.pipeline.WordCard;
import jakarta.annotation.PostConstruct;
import org.apache.lucene.analysis.ja.JapaneseTokenizer;
import org.apache.lucene.analysis.ja.tokenattributes.BaseFormAttribute;
import org.apache.lucene.analysis.ja.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.ja.tokenattributes.ReadingAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DictionaryStep {

    private static final String KRDICT_BASE  = "https://krdict.korean.go.kr/api/search";
    private static final String FREEDICT_BASE = "https://api.dictionaryapi.dev/api/v2/entries/en";

    // CEDICT line format: Traditional Simplified [pinyin] /gloss1/gloss2.../
    private static final Pattern CEDICT_LINE =
            Pattern.compile("^(\\S+) (\\S+) \\[([^\\]]+)] /(.+)/$");

    private final AppProperties appProperties;
    private final RestClient restClient;
    private Map<String, CedictEntry> cedictMap = new HashMap<>();

    public DictionaryStep(AppProperties appProperties, RestClient.Builder restClientBuilder) {
        this.appProperties = appProperties;
        this.restClient = restClientBuilder.build();
    }

    @PostConstruct
    void loadCedict() {
        ClassPathResource resource = new ClassPathResource("dict/cedict_ts.u8");
        if (!resource.exists()) {
            return; // dict not bundled; Chinese lookups return empty until file is added
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) continue;
                Matcher m = CEDICT_LINE.matcher(line);
                if (!m.matches()) continue;
                String traditional = m.group(1);
                String simplified  = m.group(2);
                String pinyin      = m.group(3);
                String gloss       = m.group(4).split("/")[0]; // first definition only
                CedictEntry entry  = new CedictEntry(traditional, simplified, pinyin, gloss);
                cedictMap.put(simplified, entry);
                if (!traditional.equals(simplified)) {
                    cedictMap.put(traditional, entry);
                }
            }
        } catch (Exception ignored) {
            // Non-fatal: Chinese lookups will return empty results
        }
    }

    public void run(AnalysisContext ctx) {
        List<WordCard> words = switch (ctx.getDetectedLanguage()) {
            case "jpn" -> lookupJapanese(ctx.getText());
            case "cmn" -> lookupChinese(ctx.getText());
            case "kor" -> lookupKorean(ctx.getText());
            case "lat" -> lookupEnglish(ctx.getText());
            default    -> List.of();
        };
        ctx.setWords(new ArrayList<>(words));
    }

    // -------------------------------------------------------------------------
    // Japanese — Kuromoji (Lucene) morphological analysis
    // romanization field is pre-populated with katakana reading so that
    // RomanizationStep can convert it to romaji via ICU4J Katakana-Latin.
    // -------------------------------------------------------------------------

    private List<WordCard> lookupJapanese(String text) {
        List<WordCard> result = new ArrayList<>();
        try {
            JapaneseTokenizer tokenizer =
                    new JapaneseTokenizer(null, true, JapaneseTokenizer.Mode.NORMAL);
            tokenizer.setReader(new StringReader(text));
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                String surface  = tokenizer.getAttribute(CharTermAttribute.class).toString();
                String baseForm = tokenizer.getAttribute(BaseFormAttribute.class).getBaseForm();
                String pos      = tokenizer.getAttribute(PartOfSpeechAttribute.class).getPartOfSpeech();
                String reading  = tokenizer.getAttribute(ReadingAttribute.class).getReading();
                result.add(WordCard.builder()
                        .surface(surface)
                        .lemma(baseForm != null ? baseForm : surface)
                        .pos(pos)
                        .romanization(reading) // katakana; RomanizationStep converts this
                        .build());
            }
            tokenizer.end();
            tokenizer.close();
        } catch (Exception ignored) {
            // Return partial results on tokenizer failure
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Chinese — greedy longest-match against CC-CEDICT (max 6-char window)
    // Unmatched characters are skipped; ClaudeStep handles them.
    // -------------------------------------------------------------------------

    private List<WordCard> lookupChinese(String text) {
        List<WordCard> result = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            boolean found = false;
            for (int len = Math.min(6, text.length() - i); len >= 1; len--) {
                String candidate = text.substring(i, i + len);
                CedictEntry entry = cedictMap.get(candidate);
                if (entry != null) {
                    result.add(WordCard.builder()
                            .surface(candidate)
                            .lemma(entry.simplified())
                            .gloss(entry.gloss())
                            // pinyin stored for RomanizationStep (Pinyin4j will re-derive,
                            // but CEDICT pinyin is used as a cross-check)
                            .build());
                    i += len;
                    found = true;
                    break;
                }
            }
            if (!found) {
                i++; // unresolved — ClaudeStep will handle
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Korean — Krdict REST API (XML response)
    // -------------------------------------------------------------------------

    private List<WordCard> lookupKorean(String text) {
        List<WordCard> result = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            // Strip non-Hangul characters from each whitespace-delimited word
            String surface = token.replaceAll(
                    "[^\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]", "");
            if (surface.isBlank()) continue;
            try {
                String xml = restClient.get()
                        .uri(KRDICT_BASE + "?key={key}&q={word}&part=word&translated=y&trans_lang=1",
                                appProperties.getApi().getVerdictKey(), surface)
                        .retrieve()
                        .body(String.class);
                if (xml == null) continue;
                WordCard card = parseKrdictXml(surface, xml);
                if (card != null) result.add(card);
            } catch (Exception ignored) {
                // Network or parse failure — skip token; ClaudeStep handles it
            }
        }
        return result;
    }

    private WordCard parseKrdictXml(String surface, String xml) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList wordNodes   = doc.getElementsByTagName("word");
            NodeList posNodes    = doc.getElementsByTagName("pos");
            NodeList transWords  = doc.getElementsByTagName("trans_word");
            NodeList transDefs   = doc.getElementsByTagName("trans_dfn");

            if (wordNodes.getLength() == 0) return null;

            String lemma = wordNodes.item(0).getTextContent().trim();
            String pos   = posNodes.getLength() > 0
                    ? posNodes.item(0).getTextContent().trim() : null;
            String gloss = null;
            if (transWords.getLength() > 0) {
                gloss = transWords.item(0).getTextContent().trim();
            } else if (transDefs.getLength() > 0) {
                gloss = transDefs.item(0).getTextContent().trim();
            }

            return WordCard.builder()
                    .surface(surface)
                    .lemma(lemma)
                    .pos(pos)
                    .gloss(gloss)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // English — Free Dictionary API (JSON response)
    // -------------------------------------------------------------------------

    private List<WordCard> lookupEnglish(String text) {
        List<WordCard> result = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            String word = token.replaceAll("[^a-zA-Z'\\-]", "").toLowerCase();
            if (word.isBlank()) continue;
            try {
                List<Map<String, Object>> entries = restClient.get()
                        .uri(FREEDICT_BASE + "/{word}", word)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (entries == null || entries.isEmpty()) continue;
                WordCard card = parseFreeDictEntry(token, entries.get(0));
                if (card != null) result.add(card);
            } catch (Exception ignored) {
                // 404 = word not found; network error — skip token
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private WordCard parseFreeDictEntry(String surface, Map<String, Object> entry) {
        String lemma = (String) entry.get("word");
        List<Map<String, Object>> meanings =
                (List<Map<String, Object>>) entry.get("meanings");
        if (meanings == null || meanings.isEmpty()) return null;

        Map<String, Object> firstMeaning = meanings.get(0);
        String pos = (String) firstMeaning.get("partOfSpeech");
        List<Map<String, Object>> definitions =
                (List<Map<String, Object>>) firstMeaning.get("definitions");
        String gloss = null;
        if (definitions != null && !definitions.isEmpty()) {
            gloss = (String) definitions.get(0).get("definition");
        }

        return WordCard.builder()
                .surface(surface)
                .lemma(lemma != null ? lemma : surface.toLowerCase())
                .pos(pos)
                .gloss(gloss)
                .build();
    }

    // -------------------------------------------------------------------------

    record CedictEntry(String traditional, String simplified, String pinyin, String gloss) {}
}
