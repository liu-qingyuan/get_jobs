package com.getjobs.application.workbench;

import java.net.URI;
import java.util.regex.Pattern;

/** OfferToday owns its opaque, case-sensitive detail identity; query parameters are never job IDs. */
record OfferTodayTarget(String id, String url) {
    private static final Pattern DETAIL = Pattern.compile("^/hk/job/([A-Za-z0-9_-]{12,128}={0,2})/?$");

    static boolean isHost(String value) {
        try {
            String host = URI.create(value).getHost();
            return "www.offertoday.com".equalsIgnoreCase(host) || "offertoday.com".equalsIgnoreCase(host);
        }
        catch (IllegalArgumentException e) { return false; }
    }

    static String identity(String value) {
        try {
            URI uri = URI.create(value);
            var match = DETAIL.matcher(uri.getPath());
            if (!isHost(value) || uri.getUserInfo() != null || !match.matches()) return null;
            boolean standardPort = uri.getPort() == -1 || "https".equals(uri.getScheme()) && uri.getPort() == 443
                    || "http".equals(uri.getScheme()) && uri.getPort() == 80;
            return standardPort && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    ? "OFFERTODAY:" + match.group(1) : null;
        } catch (IllegalArgumentException | NullPointerException e) { return null; }
    }

    static OfferTodayTarget parse(String value) {
        try {
            URI uri = URI.create(value);
            var match = DETAIL.matcher(uri.getPath());
            if (!"https".equals(uri.getScheme()) || !isHost(value) || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443 || !match.matches())
                throw new IllegalArgumentException();
            String id = match.group(1);
            return new OfferTodayTarget(id, "https://www.offertoday.com/hk/job/" + id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WorkbenchException(400, "OfferToday 请使用 HTTPS www.offertoday.com/hk/job/岗位编号 的规范详情链接");
        }
    }
}
