package com.ninemail.util;

import lombok.extern.slf4j.Slf4j;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DNS utilities for MX lookup and SPF/RBL checks
 */
@Slf4j
public final class DnsUtil {

    private DnsUtil() {}

    /**
     * Lookup MX records for a domain (sorted by priority)
     */
    public static List<String> lookupMx(String domain) {
        List<String> mxHosts = new ArrayList<>();
        try {
            Lookup lookup = new Lookup(domain, Type.MX);
            lookup.setResolver(new SimpleResolver());
            Record[] records = lookup.run();

            if (records != null) {
                List<MXRecord> mxRecords = new ArrayList<>();
                for (Record record : records) {
                    if (record instanceof MXRecord mx) {
                        mxRecords.add(mx);
                    }
                }
                mxRecords.sort(Comparator.comparingInt(MXRecord::getPriority));
                for (MXRecord mx : mxRecords) {
                    mxHosts.add(mx.getTarget().toString(true));
                }
            }
        } catch (Exception e) {
            log.error("MX lookup failed for domain: {}", domain, e);
        }

        // If there is no MX record, return the domain itself
        if (mxHosts.isEmpty()) {
            mxHosts.add(domain);
        }
        return mxHosts;
    }

    /**
     * Lookup SPF TXT record
     */
    public static String lookupSpf(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.TXT);
            lookup.setResolver(new SimpleResolver());
            Record[] records = lookup.run();

            if (records != null) {
                for (Record record : records) {
                    if (record instanceof TXTRecord txt) {
                        String text = String.join("", txt.getStrings());
                        if (text.startsWith("v=spf1")) {
                            return text;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("SPF lookup failed for domain: {}", domain, e);
        }
        return null;
    }

    /**
     * RBL (Real-time Blackhole List) check
     * Reverse the IP and query the RBL server
     */
    public static boolean isBlacklisted(String ip, String rblServer) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;

            String reversed = parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0];
            String query = reversed + "." + rblServer;

            Lookup lookup = new Lookup(query, Type.A);
            lookup.setResolver(new SimpleResolver());
            Record[] records = lookup.run();

            if (records != null && records.length > 0) {
                String result = records[0].rdataToString();
                // A 127.0.0.x response typically indicates blacklisting
                if (result.startsWith("127.0.0.")) {
                    log.warn("IP {} is blacklisted on {}", ip, rblServer);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("RBL check failed for {} on {}: {}", ip, rblServer, e.getMessage());
        }
        return false;
    }
}
