package com.jobpilot.resume;

import lombok.Data;
import java.util.Map;
import java.util.LinkedHashMap;

@Data
public class ResumeDocument {
    private String id;
    private String name;
    private String extension;
    private long size;
    private String sha256;
    private String importedAt;
    private String text = "";
    private String warning = "";
    private String analysisState = "IDLE";
    private String analysisError = "";
    private String analyzedAt;
    private String targetRole = "";
    private ResumeProfile profile;
    private Map<String, String> platformUploadIds = new LinkedHashMap<>();
    private Map<String, String> sentTo = new LinkedHashMap<>();
}
