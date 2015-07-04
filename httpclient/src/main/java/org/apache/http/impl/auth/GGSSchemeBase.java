/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.annotation.NotThreadSafe;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthChallenge;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.ChallengeType;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.InvalidCredentialsException;
import org.apache.http.auth.KerberosCredentials;
import org.apache.http.auth.MalformedChallengeException;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.message.BufferedHeader;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;
import org.apache.http.util.CharArrayBuffer;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * @since 4.2
 */
@NotThreadSafe
public abstract class GGSSchemeBase extends NonStandardAuthScheme {

    enum State {
        UNINITIATED,
        CHALLENGE_RECEIVED,
        TOKEN_GENERATED,
        FAILED,
    }

    private final Log log = LogFactory.getLog(getClass());

    private final boolean stripPort;
    private final boolean useCanonicalHostname;

    /** Authentication process state */
    private State state;

    /** base64 decoded challenge **/
    private byte[] token;

    GGSSchemeBase(final boolean stripPort, final boolean useCanonicalHostname) {
        super();
        this.stripPort = stripPort;
        this.useCanonicalHostname = useCanonicalHostname;
        this.state = State.UNINITIATED;
    }

    GGSSchemeBase(final boolean stripPort) {
        this(stripPort, true);
    }

    GGSSchemeBase() {
        this(true,true);
    }

    public void processChallenge(
            final ChallengeType challengeType,
            final AuthChallenge authChallenge) throws MalformedChallengeException {
        update(challengeType, authChallenge);
        if (state == State.UNINITIATED) {
            final String challenge = getChallenge();
            token = Base64.decodeBase64(challenge.getBytes());
            if (log.isDebugEnabled()) {
                log.debug("Received token '" + token + "' from the auth server");
            }
            state = State.CHALLENGE_RECEIVED;
        } else {
            log.debug("Authentication already attempted");
            state = State.FAILED;
        }
    }

    protected GSSManager getManager() {
        return GSSManager.getInstance();
    }

    protected byte[] generateGSSToken(
            final byte[] input, final Oid oid, final String authServer) throws GSSException {
        return generateGSSToken(input, oid, authServer, null);
    }

    /**
     * @since 4.4
     */
    protected byte[] generateGSSToken(
            final byte[] input, final Oid oid, final String authServer,
            final Credentials credentials) throws GSSException {
        byte[] inputBuff = input;
        if (inputBuff == null) {
            inputBuff = new byte[0];
        }
        final GSSManager manager = getManager();
        final GSSName serverName = manager.createName("HTTP@" + authServer, GSSName.NT_HOSTBASED_SERVICE);

        final GSSCredential gssCredential;
        if (credentials instanceof KerberosCredentials) {
            gssCredential = ((KerberosCredentials) credentials).getGSSCredential();
        } else {
            gssCredential = null;
        }

        final GSSContext gssContext = manager.createContext(
                serverName.canonicalize(oid), oid, gssCredential, GSSContext.DEFAULT_LIFETIME);
        gssContext.requestMutualAuth(true);
        gssContext.requestCredDeleg(true);
        return gssContext.initSecContext(inputBuff, 0, inputBuff.length);
    }

    /**
     * @since 4.4
     */
    protected abstract byte[] generateToken(
            byte[] input, String authServer, Credentials credentials) throws GSSException;

    @Override
    public boolean isComplete() {
        return this.state == State.TOKEN_GENERATED || this.state == State.FAILED;
    }

    @Override
    public Header authenticate(
            final Credentials credentials,
            final HttpRequest request,
            final HttpContext context) throws AuthenticationException {
        Args.notNull(request, "HTTP request");
        switch (state) {
        case UNINITIATED:
            throw new AuthenticationException(getSchemeName() + " authentication has not been initiated");
        case FAILED:
            throw new AuthenticationException(getSchemeName() + " authentication has failed");
        case CHALLENGE_RECEIVED:
            try {
                final HttpRoute route = (HttpRoute) context.getAttribute(HttpClientContext.HTTP_ROUTE);
                if (route == null) {
                    throw new AuthenticationException("Connection route is not available");
                }
                HttpHost host;
                if (isProxy()) {
                    host = route.getProxyHost();
                    if (host == null) {
                        host = route.getTargetHost();
                    }
                } else {
                    host = route.getTargetHost();
                }
                final String authServer;
                String hostname = host.getHostName();

                if (this.useCanonicalHostname){
                    try {
                         //TODO: uncomment this statement and delete the resolveCanonicalHostname,
                         //TODO: as soon canonical hostname resolving is implemented in the SystemDefaultDnsResolver
                         //final DnsResolver dnsResolver = SystemDefaultDnsResolver.INSTANCE;
                         //hostname = dnsResolver.resolveCanonicalHostname(host.getHostName());
                         hostname = resolveCanonicalHostname(hostname);
                    } catch (UnknownHostException ignore){
                    }
                }
                if (this.stripPort) { // || host.getPort()==80 || host.getPort()==443) {
                    authServer = hostname;
                } else {
                    authServer = hostname + ":" + host.getPort();
                }

                if (log.isDebugEnabled()) {
                    log.debug("init " + authServer);
                }
                token = generateToken(token, authServer, credentials);
                state = State.TOKEN_GENERATED;
            } catch (final GSSException gsse) {
                state = State.FAILED;
                if (gsse.getMajor() == GSSException.DEFECTIVE_CREDENTIAL
                        || gsse.getMajor() == GSSException.CREDENTIALS_EXPIRED) {
                    throw new InvalidCredentialsException(gsse.getMessage(), gsse);
                }
                if (gsse.getMajor() == GSSException.NO_CRED ) {
                    throw new InvalidCredentialsException(gsse.getMessage(), gsse);
                }
                if (gsse.getMajor() == GSSException.DEFECTIVE_TOKEN
                        || gsse.getMajor() == GSSException.DUPLICATE_TOKEN
                        || gsse.getMajor() == GSSException.OLD_TOKEN) {
                    throw new AuthenticationException(gsse.getMessage(), gsse);
                }
                // other error
                throw new AuthenticationException(gsse.getMessage());
            }
        case TOKEN_GENERATED:
            final Base64 codec = new Base64(0);
            final String tokenstr = new String(codec.encode(token));
            if (log.isDebugEnabled()) {
                log.debug("Sending response '" + tokenstr + "' back to the auth server");
            }
            final CharArrayBuffer buffer = new CharArrayBuffer(32);
            if (isProxy()) {
                buffer.append(AUTH.PROXY_AUTH_RESP);
            } else {
                buffer.append(AUTH.WWW_AUTH_RESP);
            }
            buffer.append(": Negotiate ");
            buffer.append(tokenstr);
            return new BufferedHeader(buffer);
        default:
            throw new IllegalStateException("Illegal state: " + state);
        }
    }

    private String resolveCanonicalHostname(final String host) throws UnknownHostException {
        final InetAddress in = InetAddress.getByName(host);
        final String canonicalServer = in.getCanonicalHostName();
        if (in.getHostAddress().contentEquals(canonicalServer)) {
            return host;
        }
        return canonicalServer;
    }

}
