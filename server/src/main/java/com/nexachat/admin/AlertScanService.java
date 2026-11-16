package com.nexachat.admin;

import com.nexachat.summary.ConversationAiOptOuts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Executes one queued scan task: opt-out check, rule scan, per-category
 * merge, redacted-snippet alert insert. Runs on the bounded queue drain —
 * never inside the send transaction.
 *
 * <p>Privacy boundary enforced here (not only at enqueue): the sender's AI
 * opt-out state is re-read per task, so an opt-out taken between send and
 * drain still suppresses analysis.
 */
@Service
public class AlertScanService {

    private static final Logger log = LoggerFactory.getLogger(AlertScanService.class);

    /** Signals "skipped because the sender opted out" so the queue can count it. */
    static final class OptedOutSkip extends RuntimeException {
        private OptedOutSkip() {
            super(null, null, false, false);
        }
    }

    private final AlertRuleRepository ruleRepository;
    private final AlertRepository alertRepository;
    private final SensitiveMessageScanner scanner;
    private final ConversationAiOptOuts optOuts;
    private final AdminProperties properties;

    public AlertScanService(AlertRuleRepository ruleRepository,
                            AlertRepository alertRepository,
                            SensitiveMessageScanner scanner,
                            ConversationAiOptOuts optOuts,
                            AdminProperties properties) {
        this.ruleRepository = ruleRepository;
        this.alertRepository = alertRepository;
        this.scanner = scanner;
        this.optOuts = optOuts;
        this.properties = properties;
    }

    /**
     * Scans one queued message and persists its alerts.
     *
     * @return number of alert rows written (0 in dry-run mode or on no-match)
     * @throws OptedOutSkip when the sender opted out of AI analysis here
     */
    public int process(AlertScanQueue.ScanTask task) {
        if (optOuts.isOptedOut(task.conversationId(), task.senderId())) {
            throw new OptedOutSkip();
        }
        List<AlertRuleRow> rules = ruleRepository.enabledRules();
        List<SensitiveMessageScanner.Match> matches = scanner.scan(task.content(), rules);
        if (matches.isEmpty()) {
            return 0;
        }
        Map<String, SensitiveMessageScanner.CategoryMatch> grouped = scanner.byCategory(matches);
        boolean dryRun = properties.alerts().dryRun();
        int created = 0;
        for (SensitiveMessageScanner.CategoryMatch group : grouped.values()) {
            if (dryRun) {
                log.info("Alert dry-run match: category={} severity={} rules={}",
                        group.category(), group.severity(), group.ruleIds().size());
                continue;
            }
            String snippet = scanner.snippet(task.content(), group.firstMatch(),
                    properties.alerts().snippetMaxChars());
            alertRepository.insertAlert(task.messageId(), task.conversationId(), task.senderId(),
                    task.seq(), group.category(), group.severity().name(), group.ruleIds(),
                    snippet, false, task.rescan());
            created++;
        }
        return created;
    }
}
