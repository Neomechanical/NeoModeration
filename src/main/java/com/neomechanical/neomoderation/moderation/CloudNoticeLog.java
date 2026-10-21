package com.neomechanical.neomoderation.moderation;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which console notices about the cloud plan were already given ({@link CloudPlanNotices#console}), so each is said
 * once for a key: the trial service answers every refusal, refresh and restart of the cloud, and an admin reads the
 * log, not the same line every ten minutes. A new key, or a key that works again, may need its notices once more.
 */
public final class CloudNoticeLog {

    private final Set<String> given = ConcurrentHashMap.newKeySet();

    /** The notice to tell the console for this answer, or null when there is nothing new to say. */
    public CloudPlanNotices.Notice next(CloudPlanWatch.Snapshot snapshot, ModerationApiResult.Kind refusal) {
        CloudPlanNotices.Notice notice = CloudPlanNotices.console(snapshot, refusal);
        return notice != null && given.add(notice.key() + "|" + notice.dateIso()) ? notice : null;
    }

    /** The key changed or works again: a later refusal is told again. */
    public void forget() {
        given.clear();
    }
}
