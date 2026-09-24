package ch.furchert.homelab.data.netmon.collector;

/**
 * Thrown by a collector after it has written its data, to report a degraded but successful run
 * (for example {@link ErrorCode#TRUNCATED}: a window exceeded the upstream page limit, docs/060 §4.2).
 * {@link CollectorRunner} records it as a success — {@code last_success_at} advances and
 * {@code consecutive_failures} resets — but keeps the code and message so the status API shows them.
 * The message follows the {@link CollectorException} rules: collector-authored, no IPs, no secrets.
 */
public class CollectorWarning extends CollectorException {

    public CollectorWarning(ErrorCode code, String safeMessage) {
        super(code, safeMessage);
    }
}
