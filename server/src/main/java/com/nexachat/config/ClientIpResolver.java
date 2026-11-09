package com.nexachat.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the client IP for rate-limit buckets. Uses the socket peer captured
 * before {@code ForwardedHeaderFilter} rewrites {@code getRemoteAddr()}, unless
 * the peer matches {@code nexachat.security.trusted-proxy-cidrs} (in which case
 * the framework-rewritten address from the trusted proxy is used).
 */
@Component
public class ClientIpResolver {

    private final List<TrustedNetwork> trustedProxies;

    public ClientIpResolver(SecurityProperties securityProperties) {
        List<TrustedNetwork> matchers = new ArrayList<>();
        for (String entry : securityProperties.trustedProxyCidrs()) {
            TrustedNetwork parsed = TrustedNetwork.parse(entry);
            if (parsed != null) {
                matchers.add(parsed);
            }
        }
        this.trustedProxies = List.copyOf(matchers);
    }

  /** IP string used as the per-IP rate-limit key for this request. */
    public String rateLimitClientIp(HttpServletRequest request) {
        String peer = peerAddress(request);
        if (trustedProxies.stream().anyMatch(network -> network.contains(peer))) {
            return request.getRemoteAddr();
        }
        return peer;
    }

    private static String peerAddress(HttpServletRequest request) {
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
            try {
                if (trimmed.contains("/")) {
                    String[] parts = trimmed.split("/", 2);
                    InetAddress base = InetAddress.getByName(parts[0].strip());
                    int prefix = Integer.parseInt(parts[1].strip());
                    int maxBits = base.getAddress().length * 8;
                    if (prefix < 0 || prefix > maxBits) {
                        return null;
                    }
                    return new TrustedNetwork(base, prefix, null);
                }
                return new TrustedNetwork(null, 0, InetAddress.getByName(trimmed));
            } catch (UnknownHostException | NumberFormatException ex) {
                return null;
            }
        }

        boolean contains(String address) {
            try {
                InetAddress candidate = InetAddress.getByName(address.strip());
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
            } catch (UnknownHostException ex) {
                return false;
            }
        }
    }
}
