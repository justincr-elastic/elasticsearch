/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.authc.jwt;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.Strings;
import org.elasticsearch.xpack.core.security.user.User;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test class with settings for a JWT issuer to sign JWTs for users.
 * Based on these settings, a test JWT realm can be created.
 */
public class JwtIssuer implements Closeable {
    private static final Logger LOGGER = LogManager.getLogger(JwtIssuer.class);

    record AlgJwkPair(String alg, JWK jwk) {}

    // input parameters
    final String issuerClaimValue; // claim name is hard-coded to `iss` for OIDC ID Token compatibility
    final List<String> audiencesClaimValue; // claim name is hard-coded to `aud` for OIDC ID Token compatibility
    final String principalClaimName; // claim name is configurable, EX: Users (sub, oid, email, dn, uid), Clients (azp, appid, client_id)
    final Map<String, User> principals; // principals with roles, for sending encoded JWTs into JWT realms for authc/authz verification
    List<AlgJwkPair> algAndJwksPkc;
    List<AlgJwkPair> algAndJwksHmac;
    AlgJwkPair algAndJwkHmacOidc;

    // Computed values
    List<AlgJwkPair> algAndJwksAll;
    Set<String> algorithmsAll;
    String encodedJwkSetPkcPublicPrivate;
    String encodedJwkSetPkcPublicOnly;
    String encodedJwkSetHmac;
    String encodedKeyHmacOidc;
    final JwtIssuerHttpsServer httpsServer;

    JwtIssuer(
        final String issuerClaimValue,
        final List<String> audiencesClaimValue,
        final String principalClaimName,
        final Map<String, User> principals,
        final List<AlgJwkPair> algAndJwksPkc,
        final List<AlgJwkPair> algAndJwksHmac,
        final AlgJwkPair algAndJwkHmacOidc,
        final boolean createHttpsServer
    ) throws Exception {
        this.issuerClaimValue = issuerClaimValue;
        this.audiencesClaimValue = audiencesClaimValue;
        this.principalClaimName = principalClaimName;
        this.principals = principals;
        this.httpsServer = this.initializeJwksAlgs(algAndJwksPkc, algAndJwksHmac, algAndJwkHmacOidc, createHttpsServer);
    }

    private JwtIssuerHttpsServer initializeJwksAlgs(
        final List<AlgJwkPair> algAndJwksPkc,
        final List<AlgJwkPair> algAndJwksHmac,
        final AlgJwkPair algAndJwkHmacOidc,
        final boolean createHttpsServer
    ) throws Exception {
        this.setJwksAndAlgs(algAndJwksPkc, algAndJwksHmac, algAndJwkHmacOidc);
        if ((Strings.hasText(this.encodedJwkSetPkcPublicOnly) == false) || (createHttpsServer == false)) {
            return null; // no PKC JWKSet, or skip HTTPS server because caller will use local file instead
        }
        return new JwtIssuerHttpsServer(this.encodedJwkSetPkcPublicOnly.getBytes(StandardCharsets.UTF_8));
    }

    public void updateJwksAlgs(
        final List<AlgJwkPair> algAndJwksPkc,
        final List<AlgJwkPair> algAndJwksHmac,
        final AlgJwkPair algAndJwkHmacOidc
    ) {
        this.setJwksAndAlgs(algAndJwksPkc, algAndJwksHmac, algAndJwkHmacOidc);
        if (this.httpsServer != null) {
            this.httpsServer.updateJwkSetPkcContents(this.encodedJwkSetPkcPublicOnly.getBytes(StandardCharsets.UTF_8));
        }

    }

    private void setJwksAndAlgs(
        final List<AlgJwkPair> algAndJwksPkc,
        final List<AlgJwkPair> algAndJwksHmac,
        final AlgJwkPair algAndJwkHmacOidc
    ) {
        this.algAndJwksPkc = algAndJwksPkc;
        this.algAndJwksHmac = algAndJwksHmac;
        this.algAndJwkHmacOidc = algAndJwkHmacOidc;

        this.algAndJwksAll = new ArrayList<>(this.algAndJwksPkc.size() + this.algAndJwksHmac.size() + 1);
        this.algAndJwksAll.addAll(this.algAndJwksPkc);
        this.algAndJwksAll.addAll(this.algAndJwksHmac);
        if (this.algAndJwkHmacOidc != null) {
            this.algAndJwksAll.add(this.algAndJwkHmacOidc);
        }
        this.algorithmsAll = this.algAndJwksAll.stream().map(p -> p.alg).collect(Collectors.toSet());

        final JWKSet jwkSetPkc = new JWKSet(this.algAndJwksPkc.stream().map(p -> p.jwk).toList());
        final JWKSet jwkSetHmac = new JWKSet(this.algAndJwksHmac.stream().map(p -> p.jwk).toList());

        this.encodedJwkSetPkcPublicPrivate = jwkSetPkc.getKeys().isEmpty() ? null : JwtUtil.serializeJwkSet(jwkSetPkc, false);
        this.encodedJwkSetPkcPublicOnly = jwkSetPkc.getKeys().isEmpty() ? null : JwtUtil.serializeJwkSet(jwkSetPkc, true);
        this.encodedJwkSetHmac = jwkSetHmac.getKeys().isEmpty() ? null : JwtUtil.serializeJwkSet(jwkSetHmac, false);
        this.encodedKeyHmacOidc = (algAndJwkHmacOidc == null) ? null : JwtUtil.serializeJwkHmacOidc(this.algAndJwkHmacOidc.jwk);
    }

    @Override
    public void close() {
        if (this.httpsServer != null) {
            try {
                this.httpsServer.close();
            } catch (IOException e) {
                LOGGER.warn("Exception closing HTTPS server for issuer [" + issuerClaimValue + "]", e);
            }
        }
    }
}
