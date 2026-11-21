package com.getjobs.application.workbench;

import java.net.URI;

/** JobsDB's numeric identity and supported HK list URLs stay in the channel Adapter. */
record JobsDbTarget(String id, String url) {
    static final String OUTBOUND_BLOCK_REASON = "JobsDB 精确单岗外发能力待核实：原 Profile 会随申请发送，附件文件名和积极文案不能证明只外发本次适用事实及冻结字节；不 claim、不调用旧 apply/batch、不回退官网。";
    static boolean isHost(String value) {
        try { var host = URI.create(value).getHost(); return host != null && (host.equalsIgnoreCase("jobsdb.com") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".jobsdb.com")); }
        catch (IllegalArgumentException e) { return false; }
    }
    static void validate(String value, boolean source) {
        try {
            var uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"hk.jobsdb.com".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 443 || !(source ? uri.getPath().matches("/(?:jobs|[A-Za-z0-9-]+-jobs)/?") : uri.getPath().matches("/job/[0-9]+/?")))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new WorkbenchException(400, "JobsDB 仅支持 HTTPS hk.jobsdb.com 的岗位列表或数字单岗详情，不使用其他渠道绕过"); }
    }
    static JobsDbTarget parse(String value) {
        validate(value, false);
        String id = URI.create(value).getPath().split("/")[2];
        return new JobsDbTarget(id, "https://hk.jobsdb.com/job/" + id);
    }
}
