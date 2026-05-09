package com.algo.trade.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_recommendation",
        indexes = @Index(name = "idx_ai_rec_generated_at", columnList = "generatedAt"))
public class AiRecommendationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant generatedAt;

    @Column(length = 20)
    private String runType;

    @Lob
    private String marketContext;

    @Lob
    private String tradeSummary;

    @Lob
    private String signalSummary;

    @Lob
    private String findings;

    @Lob
    private String suggestions;

    @Column(length = 100)
    private String overallAssessment;

    protected AiRecommendationEntity() {}

    public AiRecommendationEntity(Instant generatedAt, String runType,
                                  String marketContext, String tradeSummary,
                                  String signalSummary, String findings,
                                  String suggestions, String overallAssessment) {
        this.generatedAt = generatedAt;
        this.runType = runType;
        this.marketContext = marketContext;
        this.tradeSummary = tradeSummary;
        this.signalSummary = signalSummary;
        this.findings = findings;
        this.suggestions = suggestions;
        this.overallAssessment = overallAssessment;
    }

    public Long getId() { return id; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getRunType() { return runType; }
    public String getMarketContext() { return marketContext; }
    public String getTradeSummary() { return tradeSummary; }
    public String getSignalSummary() { return signalSummary; }
    public String getFindings() { return findings; }
    public String getSuggestions() { return suggestions; }
    public String getOverallAssessment() { return overallAssessment; }
}
