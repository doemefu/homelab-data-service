package ch.furchert.homelab.data.webanalytics.rollup;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Channel classification of a visit's first pageview (docs/100 §8.4), first match wins. */
public final class Channels {

    public static final String CAMPAIGN = "campaign";
    public static final String AI = "ai";
    public static final String SEARCH = "search";
    public static final String SOCIAL = "social";
    public static final String REFERRAL = "referral";
    public static final String DIRECT = "direct";
    public static final String INTERNAL = "internal";

    /** All seven channels in the §8.4 order. */
    public static final List<String> ALL = List.of(CAMPAIGN, AI, SEARCH, SOCIAL, REFERRAL, DIRECT, INTERNAL);

    private static final Set<String> AI_DOMAINS = Set.of("chatgpt.com", "chat.openai.com", "perplexity.ai", "claude.ai",
            "gemini.google.com", "copilot.microsoft.com");
    private static final Pattern GOOGLE = Pattern.compile("^google\\.[a-z.]+$");
    private static final Set<String> SEARCH_DOMAINS = Set.of("bing.com", "duckduckgo.com", "ecosia.org",
            "search.brave.com", "startpage.com", "yahoo.com");
    private static final Set<String> SOCIAL_DOMAINS = Set.of("linkedin.com", "lnkd.in", "facebook.com", "instagram.com",
            "x.com", "t.co", "reddit.com");

    private Channels() {
    }

    /** @param firstPageview the visit's first pageview (referrer domains are lowercase with {@code www.} stripped) */
    public static String classify(RawEvent firstPageview) {
        return classify(firstPageview.landing(), firstPageview.referrerDomain(), firstPageview.utmSource());
    }

    static String classify(Boolean landing, String referrerDomain, String utmSource) {
        if (utmSource != null) {
            return CAMPAIGN;
        }
        if (referrerDomain != null) {
            if (AI_DOMAINS.contains(referrerDomain)) {
                return AI;
            }
            if (GOOGLE.matcher(referrerDomain).matches() || SEARCH_DOMAINS.contains(referrerDomain)) {
                return SEARCH;
            }
            if (SOCIAL_DOMAINS.contains(referrerDomain)) {
                return SOCIAL;
            }
            return REFERRAL;
        }
        return Boolean.TRUE.equals(landing) ? DIRECT : INTERNAL;
    }
}
