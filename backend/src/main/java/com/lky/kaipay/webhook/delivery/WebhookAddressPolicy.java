package com.lky.kaipay.webhook.delivery;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/** No DNS calls: accepts canonical operator supplied public IPv4 bytes only. */
public class WebhookAddressPolicy {
    private static final String[] SPECIAL = {"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24",
            "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3"};

    public URI endpoint(String url, String hostname, String path) {
        URI uri = URI.create(url);
        if (!hostname.matches("(?=.{1,253}$)(?:[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
                || !path.matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]*")
                || !"https".equals(uri.getScheme()) || !hostname.equals(uri.getHost())
                || uri.getPort() != -1 && uri.getPort() != 443 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !path.equals(uri.getRawPath())
                || !("https://" + hostname + path).equals(url) && !("https://" + hostname + ":443" + path).equals(url)) {
            throw new IllegalArgumentException("Unsafe webhook destination");
        }
        return uri;
    }
    public InetAddress[] addresses(List<String> pins) {
        if (pins.isEmpty() || pins.size() > 4 || pins.stream().distinct().count() != pins.size())
            throw new IllegalArgumentException("Invalid pin set");
        return pins.stream().map(this::address).toArray(InetAddress[]::new);
    }
    protected InetAddress address(String pin) {
        byte[] bytes = ipv4(pin);
        long value = number(bytes);
        for (String cidr : SPECIAL) {
            String[] parts = cidr.split("/");
            long mask = (0xffffffffL << (32 - Integer.parseInt(parts[1]))) & 0xffffffffL;
            if ((value & mask) == (number(ipv4(parts[0])) & mask)) throw new IllegalArgumentException("Nonpublic pin");
        }
        try { return InetAddress.getByAddress(bytes); }
        catch (java.net.UnknownHostException e) { throw new IllegalArgumentException("Invalid pin"); }
    }
    private static byte[] ipv4(String text) {
        if (text == null || !text.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))
            throw new IllegalArgumentException("Invalid IPv4 pin");
        String[] values = text.split("\\."); byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) { int n = Integer.parseInt(values[i]); if (n > 255) throw new IllegalArgumentException("Invalid IPv4 pin"); bytes[i] = (byte)n; }
        return bytes;
    }
    private static long number(byte[] b) { long n = 0; for (byte value : b) n = (n << 8) | (value & 255); return n; }
}
