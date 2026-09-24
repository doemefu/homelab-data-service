package ch.furchert.homelab.data.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties the NM-1 and NM-3 tables and the collector state rows between integration tests. */
public final class NetmonTables {

    private NetmonTables() {
    }

    public static void clear(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM netmon.inbound_request_groups").update();
        jdbc.sql("DELETE FROM netmon.firewall_events").update();
        jdbc.sql("DELETE FROM netmon.ip_enrichment").update();
        jdbc.sql("DELETE FROM netmon.blocklist_entries").update();
        jdbc.sql("DELETE FROM netmon.blocklist_snapshots").update();
        jdbc.sql("DELETE FROM netmon.lan_connection_snapshots").update();
        jdbc.sql("DELETE FROM netmon.ufw_block_snapshots").update();
        jdbc.sql("DELETE FROM netmon.ssh_auth_snapshots").update();
        jdbc.sql("DELETE FROM netmon.collector_state").update();
    }
}
