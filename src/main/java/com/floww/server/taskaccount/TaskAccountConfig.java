package com.floww.server.taskaccount;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

@Component
public class TaskAccountConfig {
    public final boolean enabled;
    public final URI rpcUrl;
    public final Credentials executorKey;
    public final Credentials reporterKey;
    public final String executor;
    public final String reporter;
    public TaskAccountConfig(@Value("${floww.taskaccount.enabled:false}") boolean enabled,
                             @Value("${floww.taskaccount.rpc-url:}") String rpc,
                             @Value("${floww.taskaccount.executor-key:}") String executorKey,
                             @Value("${floww.taskaccount.reporter-key:}") String reporterKey,
                             @Value("${floww.taskaccount.executor-address:}") String executorAddress,
                             @Value("${floww.taskaccount.reporter-address:}") String reporterAddress) {
        this.enabled = enabled;
        if (!enabled) {
            rpcUrl = null; this.executorKey = null; this.reporterKey = null;
            executor = null; reporter = null; return;
        }
        try {
            rpcUrl = URI.create(rpc);
            if (!"https".equals(rpcUrl.getScheme()) && !"http".equals(rpcUrl.getScheme())) throw new IllegalArgumentException();
            if ("http".equals(rpcUrl.getScheme()) && !java.util.Set.of("127.0.0.1","localhost","::1")
                    .contains(rpcUrl.getHost())) throw new IllegalArgumentException();
            this.executorKey = Credentials.create(executorKey);
            this.reporterKey = Credentials.create(reporterKey);
            executor = this.executorKey.getAddress().toLowerCase(Locale.ROOT);
            reporter = this.reporterKey.getAddress().toLowerCase(Locale.ROOT);
            if (!executor.equals(executorAddress.toLowerCase(Locale.ROOT))
                    || !reporter.equals(reporterAddress.toLowerCase(Locale.ROOT)) || executor.equals(reporter))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new IllegalStateException("Invalid TaskAccount chain configuration");
        }
    }
}
