package io.floci.aws.services.iotwireless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LoRaWAN regional parameters (RP002-1.0.x) for the regions IoT Wireless supports: data rates, the uplink channels a
 * gateway listens on (the first sub-band of the fixed-plan regions, the default channels of the others), the RX1 and
 * RX2 downlink frequencies and data rates, and the router configuration a LoRa Basics Station gateway takes.
 */
final class LoRaRegions {

    /** A data rate: spreading factor (0 for FSK), bandwidth in kHz, and whether it's downlink only. */
    record DataRate(int sf, int bw, boolean downlinkOnly) {
    }

    record Region(String name, String station, boolean fixed, long minFreq, long maxFreq, List<DataRate> drs,
                  List<long[]> upChannels, long rx2Freq, int rx2Dr, int[][] rx1Dr, long[] rx1Freqs) {
        DataRate dr(int index) {
            return index >= 0 && index < drs.size() ? drs.get(index) : null;
        }

        /** The data rate index of a spreading factor and bandwidth, or -1. */
        int drOf(int sf, int bw, boolean uplink) {
            for (int i = 0; i < drs.size(); i++) {
                DataRate dr = drs.get(i);
                if (dr.sf() == sf && dr.bw() == bw && (!uplink || !dr.downlinkOnly())) {
                    return i;
                }
            }
            return -1;
        }

        /** RX1: the downlink data rate and frequency answering an uplink. */
        int rx1Dr(int upDr, int offset) {
            int[] row = rx1Dr[Math.min(upDr, rx1Dr.length - 1)];
            return row[Math.min(offset, row.length - 1)];
        }

        long rx1Freq(long upFreq) {
            if (!fixed) {
                return upFreq;
            }
            long base = switch (name) {
                case "AU915" -> 915_200_000L;
                case "CN470" -> 470_300_000L;
                default -> 902_300_000L;
            };
            long wideBase = "AU915".equals(name) ? 915_900_000L : 903_000_000L;
            int channel;
            if ((upFreq - wideBase) % 1_600_000 == 0 && upFreq >= wideBase && (upFreq - base) % 200_000 != 0) {
                channel = 64 + (int) ((upFreq - wideBase) / 1_600_000);
            } else {
                channel = (int) ((upFreq - base) / 200_000);
            }
            if ("CN470".equals(name)) {
                return 500_300_000L + 200_000L * (channel % 48);
            }
            return rx1Freqs[channel % 8];
        }
    }

    private static final List<DataRate> US_DRS = List.of(new DataRate(10, 125, false), new DataRate(9, 125, false),
            new DataRate(8, 125, false), new DataRate(7, 125, false), new DataRate(8, 500, false),
            new DataRate(0, 0, true), new DataRate(0, 0, true), new DataRate(0, 0, true),
            new DataRate(12, 500, true), new DataRate(11, 500, true), new DataRate(10, 500, true),
            new DataRate(9, 500, true), new DataRate(8, 500, true), new DataRate(7, 500, true));
    private static final List<DataRate> EU_DRS = List.of(new DataRate(12, 125, false), new DataRate(11, 125, false),
            new DataRate(10, 125, false), new DataRate(9, 125, false), new DataRate(8, 125, false),
            new DataRate(7, 125, false), new DataRate(7, 250, false), new DataRate(0, 0, false));
    private static final int[][] US_RX1 = {{10, 9, 8, 8}, {11, 10, 9, 8}, {12, 11, 10, 9}, {13, 12, 11, 10},
        {13, 13, 12, 11}};
    private static final int[][] EU_RX1 = {{0, 0, 0, 0, 0, 0}, {1, 0, 0, 0, 0, 0}, {2, 1, 0, 0, 0, 0},
        {3, 2, 1, 0, 0, 0}, {4, 3, 2, 1, 0, 0}, {5, 4, 3, 2, 1, 0}, {6, 5, 4, 3, 2, 1}, {7, 6, 5, 4, 3, 2}};

    private static long[] subBandUplinks(long first) {
        long[] channels = new long[8];
        for (int i = 0; i < 8; i++) {
            channels[i] = first + i * 200_000L;
        }
        return channels;
    }

    private static List<long[]> fixedChannels(long first, long wide) {
        long[] narrow = subBandUplinks(first);
        ArrayList<long[]> channels = new ArrayList<>();
        for (long freq : narrow) {
            channels.add(new long[] {freq, 0, 3});
        }
        channels.add(new long[] {wide, 4, 4});
        return channels;
    }

    private static long[] downlinks(long first) {
        long[] channels = new long[8];
        for (int i = 0; i < 8; i++) {
            channels[i] = first + i * 600_000L;
        }
        return channels;
    }

    private static List<long[]> dynamicChannels(long... freqs) {
        ArrayList<long[]> channels = new ArrayList<>();
        for (long freq : freqs) {
            channels.add(new long[] {freq, 0, 5});
        }
        return channels;
    }

    /** Sub-band 2 (channels 8 to 15 and 65), which gateways on IoT Wireless use by default. */
    static final Map<String, Region> REGIONS = Map.ofEntries(
            Map.entry("US915", new Region("US915", "US902", true, 902_000_000L, 928_000_000L, US_DRS,
                    fixedChannels(903_900_000L, 904_600_000L), 923_300_000L, 8, US_RX1, downlinks(923_300_000L))),
            Map.entry("AU915", new Region("AU915", "AU915", true, 915_000_000L, 928_000_000L, US_DRS,
                    fixedChannels(916_800_000L, 917_500_000L), 923_300_000L, 8, US_RX1, downlinks(923_300_000L))),
            Map.entry("EU868", new Region("EU868", "EU863", false, 863_000_000L, 870_000_000L, EU_DRS,
                    dynamicChannels(868_100_000L, 868_300_000L, 868_500_000L), 869_525_000L, 0, EU_RX1, null)),
            Map.entry("EU433", new Region("EU433", "EU433", false, 433_175_000L, 434_665_000L, EU_DRS,
                    dynamicChannels(433_175_000L, 433_375_000L, 433_575_000L), 434_665_000L, 0, EU_RX1, null)),
            Map.entry("IN865", new Region("IN865", "IN865", false, 865_000_000L, 867_000_000L, EU_DRS,
                    dynamicChannels(865_062_500L, 865_402_500L, 865_985_000L), 866_550_000L, 2, EU_RX1, null)),
            Map.entry("RU864", new Region("RU864", "RU864", false, 864_000_000L, 870_000_000L, EU_DRS,
                    dynamicChannels(868_900_000L, 869_100_000L), 869_100_000L, 0, EU_RX1, null)),
            Map.entry("KR920", new Region("KR920", "KR920", false, 920_900_000L, 923_300_000L, EU_DRS,
                    dynamicChannels(922_100_000L, 922_300_000L, 922_500_000L), 921_900_000L, 0, EU_RX1, null)),
            Map.entry("AS923-1", new Region("AS923-1", "AS923-1", false, 915_000_000L, 928_000_000L, EU_DRS,
                    dynamicChannels(923_200_000L, 923_400_000L), 923_200_000L, 2, EU_RX1, null)),
            Map.entry("AS923-2", new Region("AS923-2", "AS923-2", false, 915_000_000L, 928_000_000L, EU_DRS,
                    dynamicChannels(921_400_000L, 921_600_000L), 921_400_000L, 2, EU_RX1, null)),
            Map.entry("AS923-3", new Region("AS923-3", "AS923-3", false, 915_000_000L, 928_000_000L, EU_DRS,
                    dynamicChannels(916_600_000L, 916_800_000L), 916_600_000L, 2, EU_RX1, null)),
            Map.entry("AS923-4", new Region("AS923-4", "AS923-4", false, 915_000_000L, 928_000_000L, EU_DRS,
                    dynamicChannels(917_300_000L, 917_500_000L), 917_300_000L, 2, EU_RX1, null)),
            Map.entry("CN470", new Region("CN470", "CN470", true, 470_000_000L, 510_000_000L,
                    EU_DRS.subList(0, 6), dynamicChannels(470_300_000L, 470_500_000L, 470_700_000L, 470_900_000L,
                    471_100_000L, 471_300_000L, 471_500_000L, 471_700_000L), 505_300_000L, 0, EU_RX1,
                    downlinks(500_300_000L))),
            Map.entry("CN779", new Region("CN779", "CN779", false, 779_500_000L, 787_500_000L, EU_DRS,
                    dynamicChannels(779_500_000L, 779_700_000L, 779_900_000L), 786_000_000L, 0, EU_RX1, null)));

    private LoRaRegions() {
    }

    static Region region(String name) {
        Region region = REGIONS.get(name == null ? "US915" : name);
        if (region == null) {
            throw WirelessStore.validation("The RF region " + name + " isn't one IoT Wireless supports.");
        }
        return region;
    }

    /** The region of a Basics Station region name ({@code US902}, {@code EU863}). */
    static Region byStation(String station) {
        for (Region region : REGIONS.values()) {
            if (region.station().equals(station)) {
                return region;
            }
        }
        return REGIONS.get("US915");
    }

    /** The {@code router_config} message a Basics Station gateway in this region gets. */
    static ObjectNode routerConfig(ObjectMapper mapper, Region region, ArrayNode joinEuiFilters,
                                   ArrayNode netIdFilters) {
        ObjectNode config = mapper.createObjectNode();
        config.put("msgtype", "router_config");
        config.set("NetID", netIdFilters == null || netIdFilters.isEmpty() ? null : netIdFilters);
        config.set("JoinEui", joinEuiFilters == null || joinEuiFilters.isEmpty() ? null : joinEuiFilters);
        config.put("region", region.station());
        config.put("hwspec", "sx1301/1");
        config.putArray("freq_range").add(region.minFreq()).add(region.maxFreq());
        ArrayNode drs = config.putArray("DRs");
        for (DataRate dr : region.drs()) {
            drs.addArray().add(dr.sf()).add(dr.bw()).add(dr.downlinkOnly() ? 1 : 0);
        }
        ArrayNode up = config.putArray("upchannels");
        for (long[] channel : region.upChannels()) {
            up.addArray().add(channel[0]).add(channel[1]).add(channel[2]);
        }
        config.set("sx1301_conf", mapper.createArrayNode().add(sx1301(mapper, region)));
        config.put("nocca", true);
        config.put("nodc", true);
        config.put("nodwell", true);
        config.put("MuxTime", System.currentTimeMillis() / 1000.0);
        return config;
    }

    /** The concentrator configuration: two radios centred on the uplink channels, each channel at its offset. */
    private static ObjectNode sx1301(ObjectMapper mapper, Region region) {
        ObjectNode conf = mapper.createObjectNode();
        List<long[]> narrow = region.upChannels().stream().filter(c -> region.dr((int) c[1]).bw() == 125).toList();
        long low = narrow.get(0)[0];
        long high = narrow.get(narrow.size() - 1)[0];
        long radio0 = narrow.size() > 4 ? low + 400_000L : (low + high) / 2;
        long radio1 = narrow.size() > 4 ? narrow.get(4)[0] + 300_000L : radio0 + 1_000_000L;
        conf.putObject("radio_0").put("enable", true).put("freq", radio0);
        conf.putObject("radio_1").put("enable", true).put("freq", radio1);
        for (int i = 0; i < narrow.size() && i < 8; i++) {
            long freq = narrow.get(i)[0];
            boolean first = i < 4 || narrow.size() <= 4;
            conf.putObject("chan_multiSF_" + i).put("enable", true).put("radio", first ? 0 : 1)
                    .put("if", freq - (first ? radio0 : radio1));
        }
        ObjectNode std = conf.putObject("chan_Lora_std");
        long[] wide = region.upChannels().stream().filter(c -> region.dr((int) c[1]).bw() > 125).findFirst()
                .orElse(null);
        if (wide != null) {
            DataRate dr = region.dr((int) wide[1]);
            std.put("enable", true).put("radio", 0).put("if", wide[0] - radio0).put("bandwidth", dr.bw() * 1000)
                    .put("spread_factor", dr.sf());
        } else {
            std.put("enable", false);
        }
        conf.putObject("chan_FSK").put("enable", false);
        return conf;
    }
}
