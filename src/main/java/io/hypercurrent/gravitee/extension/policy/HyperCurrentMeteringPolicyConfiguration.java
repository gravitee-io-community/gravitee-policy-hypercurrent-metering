package io.hypercurrent.gravitee.extension.policy;

import io.gravitee.policy.api.PolicyConfiguration;

@SuppressWarnings("unused")
public class HyperCurrentMeteringPolicyConfiguration implements PolicyConfiguration {

    /**
     * The HyperCurrent Metering URL
     */
    private String meteringUrl = "api.hypercurrent.io";

    private String apiKey = "The HyperCurrent API Key";

    private String meteringExpression = "";

    private String metadataExpression = "";

    private String successExpression = "";

    private Boolean activeMode = false;

    public int getProductKeyCacheTTL() {
        return productKeyCacheTTL;
    }

    public void setProductKeyCacheTTL(int productKeyCacheTTL) {
        this.productKeyCacheTTL = productKeyCacheTTL;
    }

    private int productKeyCacheTTL = 2;

    public String getMeteringUrl() {
        return meteringUrl;
    }

    public void setMeteringUrl(String meteringUrl) {
        this.meteringUrl = meteringUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getMeteringExpression() {
        return meteringExpression;
    }

    public void setMeteringExpression(String meteringExpression) {
        this.meteringExpression = meteringExpression;
    }

    public String getMetadataExpression() {
        return metadataExpression;
    }

    public void setMetadataExpression(String metadataExpression) {
        this.metadataExpression = metadataExpression;
    }

    public boolean getActiveMode() {
        return activeMode;
    }

    public void setActiveMode(Boolean activeMode) {
        this.activeMode = activeMode;
    }

    public String getSuccessExpression() {
        return successExpression;
    }

    public void setSuccessExpression(String successExpression) {
        this.successExpression = successExpression;
    }

}
