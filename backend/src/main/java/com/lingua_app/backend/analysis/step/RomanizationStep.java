package com.lingua_app.backend.analysis.step;

import com.ibm.icu.text.Transliterator;
import com.lingua_app.backend.analysis.pipeline.AnalysisContext;
import com.lingua_app.backend.analysis.pipeline.WordCard;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Component;

@Component
public class RomanizationStep implements AnalysisStep {

    private final Transliterator hangulToLatin;
    private final Transliterator katakanaToLatin;
    private final HanyuPinyinOutputFormat pinyinFormat;

    public RomanizationStep() {
        hangulToLatin   = Transliterator.getInstance("Hangul-Latin/BGN");
        katakanaToLatin = Transliterator.getInstance("Katakana-Latin");

        pinyinFormat = new HanyuPinyinOutputFormat();
        pinyinFormat.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
        pinyinFormat.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
        pinyinFormat.setCaseType(HanyuPinyinCaseType.LOWERCASE);
    }

    public void run(AnalysisContext ctx) {
        if ("lat".equals(ctx.getDetectedLanguage())) return; // English: no romanization

        for (WordCard word : ctx.getWords()) {
            String romanization = switch (ctx.getDetectedLanguage()) {
                case "kor" -> romanizeKorean(word.getSurface());
                case "jpn" -> romanizeJapanese(word.getRomanization()); // katakana set by DictionaryStep
                case "cmn" -> romanizeChinese(word.getSurface());
                default    -> null;
            };
            word.setRomanization(romanization);
        }
    }

    private String romanizeKorean(String surface) {
        if (surface == null || surface.isBlank()) return null;
        return hangulToLatin.transliterate(surface);
    }

    // word.romanization holds the katakana reading placed by DictionaryStep (Kuromoji).
    // ICU4J converts katakana → Hepburn-style romaji.
    private String romanizeJapanese(String katakanaReading) {
        if (katakanaReading == null || katakanaReading.isBlank()) return null;
        return katakanaToLatin.transliterate(katakanaReading);
    }

    private String romanizeChinese(String surface) {
        if (surface == null || surface.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : surface.toCharArray()) {
            try {
                String[] readings = PinyinHelper.toHanyuPinyinStringArray(c, pinyinFormat);
                if (readings != null && readings.length > 0) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(readings[0]);
                }
            } catch (BadHanyuPinyinOutputFormatCombination ignored) {
                // Non-CJK character — skip
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
