package com.lingua_app.backend.analysis.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WordCard {

    private String surface;
    private String lemma;
    private String pos;
    private String gloss;
    private String romanization;
    private String ipa;
    private String error;
}