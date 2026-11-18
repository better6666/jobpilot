package com.jobpilot.update;

import java.net.URI;
import java.util.List;
import java.util.Map;

public record UpdateManifest(String version, String publishedAt, List<String> notes, Map<String, Asset> assets) {
    public record Asset(String url, String sha256, long size) {
    }

    public static boolean newer(String candidate, String current) {
        if (!validVersion(candidate) || !validVersion(current)) {
            return false;
        }
        String[] left = candidate.split("\\.");
        String[] right = current.split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (order != 0) return order > 0;
        }
        return false;
    }

    public static boolean validVersion(String version) {
        return version != null && version.matches("\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}");
    }

    public void validate(URI origin) {
        if (!validVersion(version) || notes == null || notes.isEmpty() || notes.size() > 30 || assets == null) {
            throw new IllegalArgumentException("更新说明格式无效");
        }
        for (Asset asset : assets.values()) {
            URI url = URI.create(asset.url());
            if (!origin.getScheme().equals(url.getScheme()) || !origin.getHost().equals(url.getHost())
                    || origin.getPort() != url.getPort() || url.getUserInfo() != null
                    || !asset.sha256().matches("[0-9a-f]{64}") || asset.size() <= 0 || asset.size() > 2_000_000_000L) {
                throw new IllegalArgumentException("更新安装包信息无效");
            }
        }
    }
}
