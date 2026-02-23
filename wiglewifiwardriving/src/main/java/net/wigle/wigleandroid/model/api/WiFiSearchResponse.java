package net.wigle.wigleandroid.model.api;

import org.maplibre.android.geometry.LatLng;

import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.model.NetworkType;

import java.util.List;
import java.util.Locale;

/**
 * WiGLE v2 API WiFi Search Response model object
 */
public class WiFiSearchResponse {
    private boolean success;
    private List<WiFiNetwork> results;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public List<WiFiNetwork> getResults() {
        return results;
    }

    public void setResults(List<WiFiNetwork> results) {
        this.results = results;
    }

    //ALIBI: while these have some things in common w/ Network, we don't need all of that.
    public class WiFiNetwork {
        private String netid;
        private Double trilat;
        private Double trilong;
        private String ssid;
        private String encryption;
        private String auth;
        private String flags;
        private final String type = "WiFi";
        private int frequency;
        private Integer channel;
        private Integer highSig;
        private Integer highRssi;
        private Integer highGpsSig;
        private Integer highGpsRssi;

        public String getNetid() {
            return netid;
        }

        public void setNetid(String netid) {
            this.netid = netid;
        }

        public Double getTrilong() {
            return trilong;
        }

        public void setTrilong(Double trilong) {
            this.trilong = trilong;
        }

        public String getSsid() {
            return ssid;
        }

        public void setSsid(String ssid) {
            this.ssid = ssid;
        }

        public String getEncryption() {
            return encryption;
        }

        public void setEncryption(String encryption) {
            this.encryption = encryption;
        }

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }

        public String getFlags() {
            return flags;
        }

        public void setFlags(String flags) {
            this.flags = flags;
        }

        public String getType() {
            return type;
        }

        public int getFrequency() {
            return frequency;
        }

        public void setFrequency(int frequency) {
            this.frequency = frequency;
        }

        public Integer getChannel() {
            return channel;
        }

        public void setChannel(Integer channel) {
            this.channel = channel;
        }

        public Integer getHighSig() { return highSig; }
        public void setHighSig(Integer highSig) { this.highSig = highSig; }

        public Integer getHighRssi() { return highRssi; }
        public void setHighRssi(Integer highRssi) { this.highRssi = highRssi; }

        public Integer getHighGpsSig() { return highGpsSig; }
        public void setHighGpsSig(Integer highGpsSig) { this.highGpsSig = highGpsSig; }

        public Integer getHighGpsRssi() { return highGpsRssi; }
        public void setHighGpsRssi(Integer highGpsRssi) { this.highGpsRssi = highGpsRssi; }

        public Double getTrilat() {
            return trilat;
        }

        public void setTrilat(Double trilat) {
            this.trilat = trilat;
        }
    }

    /**
     * Sometimes you need a {@link Network} that contains the information in a {@link WiFiNetwork}. ALIBI: avoids a rendering rewrite for network refactor.
     * @param wNet the WiFiNetwork instance
     * @return a Network instance with some assumptions. Capabilities will be [<encryption value> SEARCH]
     */
    public static Network asNetwork(WiFiNetwork wNet) {
        // Guard against missing coordinates (avoid autounboxing NPE)
        LatLng l = null;
        Double tlat = wNet.getTrilat();
        Double tlon = wNet.getTrilong();
        if (tlat != null && tlon != null) {
            l = new LatLng(tlat, tlon);
        }
        // Prefer full flags if provided
        String caps;
        if (wNet.getFlags() != null && !wNet.getFlags().isEmpty()) {
            caps = wNet.getFlags();
        } else {
            // Guard encryption null
            String enc = wNet.getEncryption();
            String auth = wNet.getAuth();
            if (auth != null && !auth.isEmpty()) {
                caps = "[" + auth.toUpperCase(Locale.ROOT) + (enc != null && !enc.isEmpty() ? ("-" + enc.toUpperCase(Locale.ROOT)) : "") + " SEARCH]";
            } else if (enc != null && !enc.isEmpty()) {
                caps = "[" + enc.toUpperCase(Locale.ROOT) + " SEARCH]";
            } else {
                caps = "[ESS]";
            }
        }
        int signal = 0;
        if (wNet.getHighGpsRssi() != null) {
            signal = wNet.getHighGpsRssi();
        } else if (wNet.getHighRssi() != null) {
            signal = wNet.getHighRssi();
        } else if (wNet.getHighSig() != null) {
            signal = wNet.getHighSig();
        }

        return new Network(wNet.getNetid(), wNet.getSsid(),
                wNet.getChannel(), caps,
                signal, NetworkType.WIFI, l);
    }

}
