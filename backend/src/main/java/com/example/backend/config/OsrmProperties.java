package com.example.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "osrm")
public class OsrmProperties {

    @NotBlank
    private String baseUrl = "http://localhost:5000";

    @Min(100)
    @Max(60000)
    private long timeoutMs = 5000;

    private boolean iamEnabled;

    @Min(1)
    @Max(100)
    private int maxTableCandidates = 50;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isIamEnabled() {
        return iamEnabled;
    }

    public void setIamEnabled(boolean iamEnabled) {
        this.iamEnabled = iamEnabled;
    }

    public int getMaxTableCandidates() {
        return maxTableCandidates;
    }

    public void setMaxTableCandidates(int maxTableCandidates) {
        this.maxTableCandidates = maxTableCandidates;
    }
}
