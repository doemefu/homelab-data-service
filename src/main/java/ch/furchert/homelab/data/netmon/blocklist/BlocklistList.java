package ch.furchert.homelab.data.netmon.blocklist;

/** The two lists of docs/060 §4.4, with their {@code list_name} and common {@code source} values. */
public enum BlocklistList {
    SPAMHAUS_DROP_V4("spamhaus-drop-v4", "spamhaus"),
    FIREHOL_LEVEL1("firehol-level1", "firehol");

    private final String listName;
    private final String source;

    BlocklistList(String listName, String source) {
        this.listName = listName;
        this.source = source;
    }

    public String listName() {
        return listName;
    }

    public String source() {
        return source;
    }
}
