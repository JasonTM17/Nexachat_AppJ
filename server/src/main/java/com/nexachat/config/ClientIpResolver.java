package com.nexachat.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the client IP used as the per-IP rate-limit bucket key.
 *
 * <p>The trust anchor is the socket peer captured by
 * {@link ClientIpCaptureThenForwardedHeaderFilter} before Spring's forwarded-header
 * rewriting. Forwarding headers are read only when that peer matches
 * {@code nexachat.security.trusted-proxy-cidrs}; a direct caller can therefore
 * never choose a bucket by sending a header.
 *
 * <p>With the default {@code X-Forwarded-For} header the chain is walked from
 * right to left and the first address outside the trusted ranges is used.
 * Proxies append the peer they saw, so the left end is caller-controlled and is
 * never selected. A deployment whose edge overwrites a single-value header can
 * set {@code nexachat.security.client-ip-header} to that header instead; when
 * it has more than one value the request falls back to the socket peer.
 */
@Component
public class ClientIpResolver {

    public static final String SOCKET_REMOTE_ADDR_ATTRIBUTE = "nexachat.socketRemoteAddr";

    static final String DEFAULT_CLIENT_IP_HEADER = "X-Forwarded-For";

    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
                    + "(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6_LITERAL_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    private final List<TrustedNetwork> trustedProxies;
    private final String clientIpHeader;

    public ClientIpResolver(SecurityProperties securityProperties) {
        List<TrustedNetwork> matchers = new ArrayList<>();
        for (String entry : securityProperties.trustedProxyCidrs()) {
            TrustedNetwork parsed = TrustedNetwork.parse(entry);
            if (parsed != null) {
                matchers.add(parsed);
            }
        }
        this.trustedProxies = List.copyOf(matchers);
        String configured = securityProperties.clientIpHeader();
        this.clientIpHeader = configured == null || configured.isBlank()
                ? DEFAULT_CLIENT_IP_HEADER
                : configured.strip();
    }

    /** IP string used as the per-IP rate-limit key for this request. */
    public String rateLimitClientIp(HttpServletRequest request) {
        String peer = socketPeer(request);
        InetAddress peerAddress = parseLiteral(peer);
        if (peerAddress == null || !isTrusted(peerAddress)) {
            return peer;
        }

        List<String> values = headerValues(unwrap(request), clientIpHeader);
        if (values.isEmpty()) {
            return peer;
        }
        if (DEFAULT_CLIENT_IP_HEADER.equalsIgnoreCase(clientIpHeader)) {
            return rightMostUntrusted(values, peer);
        }
        String address = singleAddress(values);
        return address != null ? address : peer;
    }

    private boolean isTrusted(InetAddress address) {
        for (TrustedNetwork network : trustedProxies) {
            if (network.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the forwarded chain right to left, skipping trusted proxies. A
     * malformed hop fails closed to the socket peer: accepting the entries to
     * its left could select a caller-supplied address.
     */
    private String rightMostUntrusted(List<String> values, String peer) {
        for (int valueIndex = values.size() - 1; valueIndex >= 0; valueIndex--) {
            String[] hops = values.get(valueIndex).split(",", -1);
            for (int hopIndex = hops.length - 1; hopIndex >= 0; hopIndex--) {
                String hop = hops[hopIndex].strip();
                if (hop.isEmpty()) {
                    return peer;
                }
                InetAddress address = parseLiteral(hop);
                if (address == null) {
                    return peer;
                }
                if (!isTrusted(address)) {
                    return address.getHostAddress();
                }
            }
        }
        return peer;
    }

    /** A single-value header (for example {@code CF-Connecting-IP}) must hold exactly one address. */
    private static String singleAddress(List<String> values) {
        if (values.size() != 1) {
            return null;
        }
        String text = values.get(0).strip();
        if (text.indexOf(',') >= 0) {
            return null;
        }
        InetAddress address = parseLiteral(text);
        return address == null ? null : address.getHostAddress();
    }

    private static List<String> headerValues(HttpServletRequest request, String name) {
        List<String> values = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders(name);
        while (headers != null && headers.hasMoreElements()) {
            String value = headers.nextElement();
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String socketPeer(HttpServletRequest request) {
        Object captured = request.getAttribute(SOCKET_REMOTE_ADDR_ATTRIBUTE);
        if (captured instanceof String peer && !peer.isBlank()) {
            return peer;
        }
        return unwrap(request).getRemoteAddr();
    }

    /** The innermost request keeps the socket peer after {@code ForwardedHeaderFilter} wraps it. */
    static HttpServletRequest unwrap(HttpServletRequest request) {
        HttpServletRequest current = request;
        while (current instanceof HttpServletRequestWrapper wrapper) {
            current = (HttpServletRequest) wrapper.getRequest();
        }
        return current;
    }

    /**
     * Parses an address literal without DNS resolution. Hostnames, ports, zone
     * ids and comma-separated lists are rejected, so a header value can never
     * be turned into a lookup or matched by a name that resolves into a trusted
     * range.
     */
    static InetAddress parseLiteral(String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > 1 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']') {
            text = text.substring(1, text.length() - 1);
        }
        if (text.indexOf('%') >= 0) {
            return null;
        }
        if (text.indexOf(':') >= 0) {
            if (!IPV6_LITERAL_CHARS.matcher(text).matches()) {
                return null;
            }
        } else if (!IPV4_LITERAL.matcher(text).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    static final class TrustedNetwork {

        private final InetAddress network;
        private final int prefixLength;
        private final InetAddress exact;

        private TrustedNetwork(InetAddress network, int prefixLength, InetAddress exact) {
            this.network = network;
            this.prefixLength = prefixLength;
            this.exact = exact;
        }

        static TrustedNetwork parse(String entry) {
            if (entry == null) {
                return null;
            }
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) {
                return null;
            }
            if (trimmed.contains("/")) {
                String[] parts = trimmed.split("/", 2);
                InetAddress base = parseLiteral(parts[0].strip());
                if (base == null) {
                    return null;
                }
                try {
                    int prefix = Integer.parseInt(parts[1].strip());
                    int maxBits = base.getAddress().length * 8;
                    if (prefix < 0 || prefix > maxBits) {
                        return null;
                    }
                    return new TrustedNetwork(base, prefix, null);
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            InetAddress single = parseLiteral(trimmed);
            return single == null ? null : new TrustedNetwork(null, 0, single);
        }

        boolean contains(InetAddress candidate) {
            if (exact != null) {
                return exact.equals(candidate);
            }
            byte[] networkBytes = network.getAddress();
            byte[] candidateBytes = candidate.getAddress();
            if (networkBytes.length != candidateBytes.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != candidateBytes[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (networkBytes[fullBytes] & mask) == (candidateBytes[fullBytes] & mask);
        }
    }
}
