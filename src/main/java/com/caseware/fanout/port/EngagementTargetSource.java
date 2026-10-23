package com.caseware.fanout.port;

import java.util.List;

/**
 * Target engagements of a publication, page by page. Paging lets the worker decide <em>when</em> to read more,
 * which keeps read-but-unfinished tasks bounded however large the publication is.
 *
 * <ul>
 *   <li>Pre: {@code token} is {@code null} (first page) or a returned {@code nextToken}; {@code limit >= 1}.</li>
 *   <li>Post: at most {@code limit} ids; {@code nextToken} is {@code null} on the last page.</li>
 *   <li>Side-effect free: reading again is safe and may repeat ids; the worker deduplicates by key.</li>
 * </ul>
 */
public interface EngagementTargetSource {

    Page page(String publishId, String token, int limit);

    record Page(List<String> engagementIds, String nextToken) {
        public Page {
            engagementIds = List.copyOf(engagementIds);
        }
    }
}
