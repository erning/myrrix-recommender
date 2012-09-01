/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.myrrix.web;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.Realm;
import org.apache.catalina.authenticator.AuthenticatorBase;
import org.apache.catalina.authenticator.Constants;
import org.apache.catalina.connector.Request;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.util.MD5Encoder;

/**
 * Modification of Tomcat's DigestAuthenticator to resolve some thread contention hotspots.
 *
 * @see org.apache.catalina.authenticator.DigestAuthenticator
 */
public final class MyrrixDigestAuthenticator extends AuthenticatorBase {

  private static final MD5Encoder md5Encoder = new MD5Encoder();
  private static final String INFO = "org.apache.catalina.authenticator.DigestAuthenticator/1.0";
  private static final String QOP = "auth";
  private static final Pattern COMMA = Pattern.compile(",");

  private Map<String, NonceInfo> cnonces;
  private int cnonceCacheSize = 1000;
  private String key = null;
  private long nonceValidity = 5 * 60 * 1000;
  private String opaque;
  private boolean validateUri = true;

  @Override
  public String getInfo() {
    return INFO;
  }

  public int getCnonceCacheSize() {
    return cnonceCacheSize;
  }

  public void setCnonceCacheSize(int cnonceCacheSize) {
    this.cnonceCacheSize = cnonceCacheSize;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public long getNonceValidity() {
    return nonceValidity;
  }

  public void setNonceValidity(long nonceValidity) {
    this.nonceValidity = nonceValidity;
  }

  public String getOpaque() {
    return opaque;
  }

  public void setOpaque(String opaque) {
    this.opaque = opaque;
  }

  public boolean isValidateUri() {
    return validateUri;
  }

  public void setValidateUri(boolean validateUri) {
    this.validateUri = validateUri;
  }

  @Override
  public boolean authenticate(Request request,
                              HttpServletResponse response,
                              LoginConfig config) throws IOException {
    // Have we already authenticated someone?
    Principal principal = request.getUserPrincipal();
    if (principal != null) {
      // Associate the session with any existing SSO session in order
      // to get coordinated session invalidation at logout
      String ssoId = (String) request.getNote(Constants.REQ_SSOID_NOTE);
      if (ssoId != null) {
        associate(ssoId, request.getSessionInternal(true));
      }
      return true;
    }

    // Validate any credentials already included with this request
    String authorization = request.getHeader("authorization");
    DigestInfo digestInfo = new DigestInfo(getOpaque(), getNonceValidity(),
                                           getKey(), cnonces, isValidateUri());
    if (authorization != null) {
      if (digestInfo.validate(request, authorization, config)) {
        principal = digestInfo.authenticate(context.getRealm());
      }

      if (principal != null) {
        String username = parseUsername(authorization);
        register(request, response, principal,
                 HttpServletRequest.DIGEST_AUTH,
                 username, null);
        return true;
      }
    }

    // Send an "unauthorized" response and an appropriate challenge

    // Next, generate a nonce token (that is a token which is supposed
    // to be unique).
    String nonce = generateNonce(request);

    setAuthenticateHeader(response, config, nonce, digestInfo.isNonceStale());
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    return false;
  }

  @Override
  protected String getAuthMethod() {
    return HttpServletRequest.DIGEST_AUTH;
  }

  private static String parseUsername(String authorization) {

    // Validate the authorization credentials format
    if (authorization == null) {
      return null;
    }
    if (!authorization.startsWith("Digest ")) {
      return null;
    }
    authorization = authorization.substring(7).trim();

    for (String currentToken : COMMA.split(authorization)) {
      int equalSign = currentToken.indexOf('=');
      if (equalSign < 0) {
        return null;
      }
      String currentTokenName =
          currentToken.substring(0, equalSign).trim();
      String currentTokenValue =
          currentToken.substring(equalSign + 1).trim();
      if ("username".equals(currentTokenName)) {
        return removeQuotes(currentTokenValue);
      }
    }

    return null;

  }

  private static String removeQuotes(String quotedString, boolean quotesRequired) {
    //support both quoted and non-quoted
    if (!quotedString.isEmpty() && quotedString.charAt(0) != '"' && !quotesRequired) {
      return quotedString;
    }
    if (quotedString.length() > 2) {
      return quotedString.substring(1, quotedString.length() - 1);
    }
    return "";
  }

  private static String removeQuotes(String quotedString) {
    return removeQuotes(quotedString, false);
  }

  private String generateNonce(ServletRequest request) {
    long currentTime = System.currentTimeMillis();
    String ipTimeKey = request.getRemoteAddr() + ':' + currentTime + ':' + getKey();
    byte[] bytesToDigest = ipTimeKey.getBytes(Charset.defaultCharset());
    byte[] buffer = md5Digest(bytesToDigest);
    return currentTime + ":" + md5Encoder.encode(buffer);
  }

  private static byte[] md5Digest(byte[] in) {
    try {
      return MessageDigest.getInstance("MD5").digest(in);
    } catch (NoSuchAlgorithmException nsae) {
      throw new IllegalStateException(nsae);
    }
  }

  private void setAuthenticateHeader(HttpServletResponse response,
                                     LoginConfig config,
                                     String nonce,
                                     boolean isNonceStale) {

    // Get the realm name
    String realmName = config.getRealmName();
    if (realmName == null) {
      realmName = REALM_NAME;
    }

    String authenticateHeader;
    if (isNonceStale) {
      authenticateHeader = "Digest realm=\"" + realmName + "\", " +
          "qop=\"" + QOP + "\", nonce=\"" + nonce + "\", " + "opaque=\"" +
          getOpaque() + "\", stale=true";
    } else {
      authenticateHeader = "Digest realm=\"" + realmName + "\", " +
          "qop=\"" + QOP + "\", nonce=\"" + nonce + "\", " + "opaque=\"" +
          getOpaque() + '"';
    }

    response.setHeader(AUTH_HEADER_NAME, authenticateHeader);

  }

  @Override
  protected synchronized void startInternal() throws LifecycleException {
    super.startInternal();

    // Generate a random secret key
    if (getKey() == null) {
      setKey(sessionIdGenerator.generateSessionId());
    }

    // Generate the opaque string the same way
    if (getOpaque() == null) {
      setOpaque(sessionIdGenerator.generateSessionId());
    }

    cnonces = new LinkedHashMap<String, NonceInfo>() {
      private static final long serialVersionUID = 1L;
      //private static final long LOG_SUPPRESS_TIME = 5 * 60 * 1000;
      //private long lastLog = 0;
      @Override
      protected boolean removeEldestEntry(
          Map.Entry<String, NonceInfo> eldest) {
        // This is called from a sync so keep it simple
        //long currentTime = System.currentTimeMillis();
        if (size() > getCnonceCacheSize()) {
          /*
          if (lastLog < currentTime &&
              currentTime - eldest.getValue().getTimestamp() <
                  getNonceValidity()) {
            // Replay attack is possible
            log.warn(sm.getString("digestAuthenticator.cacheRemove"));
            lastLog = currentTime + LOG_SUPPRESS_TIME;
          }
           */
          return true;
        }
        return false;
      }
    };
  }

  private static final class DigestInfo {

    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(",(?=(?:[^\"]*\"[^\"]*\")+$)");

    private final String opaque;
    private final long nonceValidity;
    private final String key;
    private final Map<String, NonceInfo> cnonces;
    private boolean validateUri = true;

    private String userName;
    private String method;
    private String uri;
    private String response;
    private String nonce;
    private String nc;
    private String cnonce;
    private String realmName;
    private String qop;

    private boolean nonceStale;


    private DigestInfo(String opaque, long nonceValidity, String key,
                       Map<String, NonceInfo> cnonces, boolean validateUri) {
      this.opaque = opaque;
      this.nonceValidity = nonceValidity;
      this.key = key;
      this.cnonces = cnonces;
      this.validateUri = validateUri;
    }

    public boolean validate(HttpServletRequest request, String authorization, LoginConfig config) {
      // Validate the authorization credentials format
      if (authorization == null) {
        return false;
      }
      if (!authorization.startsWith("Digest ")) {
        return false;
      }
      authorization = authorization.substring(7).trim();

      // Bugzilla 37132: http://issues.apache.org/bugzilla/show_bug.cgi?id=37132
      String[] tokens = AUTHORIZATION_PATTERN.split(authorization);

      method = request.getMethod();
      String opaque = null;

      for (String currentToken : tokens) {
        if (currentToken.isEmpty()) {
          continue;
        }

        int equalSign = currentToken.indexOf('=');
        if (equalSign < 0) {
          return false;
        }
        String currentTokenName =
            currentToken.substring(0, equalSign).trim();
        String currentTokenValue =
            currentToken.substring(equalSign + 1).trim();
        if ("username".equals(currentTokenName)) {
          userName = removeQuotes(currentTokenValue);
        }
        if ("realm".equals(currentTokenName)) {
          realmName = removeQuotes(currentTokenValue, true);
        }
        if ("nonce".equals(currentTokenName)) {
          nonce = removeQuotes(currentTokenValue);
        }
        if ("nc".equals(currentTokenName)) {
          nc = removeQuotes(currentTokenValue);
        }
        if ("cnonce".equals(currentTokenName)) {
          cnonce = removeQuotes(currentTokenValue);
        }
        if ("qop".equals(currentTokenName)) {
          qop = removeQuotes(currentTokenValue);
        }
        if ("uri".equals(currentTokenName)) {
          uri = removeQuotes(currentTokenValue);
        }
        if ("response".equals(currentTokenName)) {
          response = removeQuotes(currentTokenValue);
        }
        if ("opaque".equals(currentTokenName)) {
          opaque = removeQuotes(currentTokenValue);
        }
      }

      if ((userName == null) || (realmName == null) || (nonce == null)
          || (uri == null) || (response == null)) {
        return false;
      }

      // Validate the URI - should match the request line sent by client
      if (validateUri) {
        String uriQuery;
        String query = request.getQueryString();
        if (query == null) {
          uriQuery = request.getRequestURI();
        } else {
          uriQuery = request.getRequestURI() + '?' + query;
        }
        if (!uri.equals(uriQuery)) {
          // Some clients (older Android) use an absolute URI for
          // DIGEST but a relative URI in the request line.
          // request. 2.3.5 < fixed Android version <= 4.0.3
          String host = request.getHeader("host");
          String scheme = request.getScheme();
          if (host != null && !uriQuery.startsWith(scheme)) {
            StringBuilder absolute = new StringBuilder();
            absolute.append(scheme);
            absolute.append("://");
            absolute.append(host);
            absolute.append(uriQuery);
            if (!uri.equals(absolute.toString())) {
              return false;
            }
          } else {
            return false;
          }
        }
      }

      // Validate the Realm name
      String lcRealm = config.getRealmName();
      if (lcRealm == null) {
        lcRealm = REALM_NAME;
      }
      if (!lcRealm.equals(realmName)) {
        return false;
      }

      // Validate the opaque string
      if (!this.opaque.equals(opaque)) {
        return false;
      }

      // Validate nonce
      int i = nonce.indexOf(':');
      if (i < 0 || (i + 1) == nonce.length()) {
        return false;
      }
      long nonceTime;
      try {
        nonceTime = Long.parseLong(nonce.substring(0, i));
      } catch (NumberFormatException nfe) {
        return false;
      }
      String md5clientIpTimeKey = nonce.substring(i + 1);
      long currentTime = System.currentTimeMillis();
      if ((currentTime - nonceTime) > nonceValidity) {
        nonceStale = true;
        return false;
      }
      String serverIpTimeKey = request.getRemoteAddr() + ':' + nonceTime + ':' + key;
      byte[] bytesToDigest = serverIpTimeKey.getBytes(Charset.defaultCharset());
      byte[] buffer = md5Digest(bytesToDigest);
      String md5ServerIpTimeKey = md5Encoder.encode(buffer);
      if (!md5ServerIpTimeKey.equals(md5clientIpTimeKey)) {
        return false;
      }

      // Validate qop
      if (qop != null && !QOP.equals(qop)) {
        return false;
      }

      // Validate cnonce and nc
      // Check if presence of nc and nonce is consistent with presence of qop
      if (qop == null) {
        if (cnonce != null || nc != null) {
          return false;
        }
      } else {
        if (cnonce == null || nc == null) {
          return false;
        }
        // RFC 2617 says nc must be 8 digits long. Older Android clients
        // use 6. 2.3.5 < fixed Android version <= 4.0.3
        if (nc.length() < 6 || nc.length() > 8) {
          return false;
        }
        long count;
        try {
          count = Long.parseLong(nc, 16);
        } catch (NumberFormatException nfe) {
          return false;
        }
        NonceInfo info;
        synchronized (cnonces) {
          info = cnonces.get(cnonce);
        }
        if (info == null) {
          info = new NonceInfo();
        } else {
          if (count <= info.getCount()) {
            return false;
          }
        }
        info.setCount(count);
        info.setTimestamp(currentTime);
        synchronized (cnonces) {
          cnonces.put(cnonce, info);
        }
      }
      return true;
    }

    public boolean isNonceStale() {
      return nonceStale;
    }

    public Principal authenticate(Realm realm) {
      // Second MD5 digest used to calculate the digest :
      // MD5(Method + ":" + uri)
      String a2 = method + ':' + uri;
      byte[] bytesToDigest = a2.getBytes(Charset.defaultCharset());
      byte[] buffer = md5Digest(bytesToDigest);
      String md5a2 = md5Encoder.encode(buffer);

      return realm.authenticate(userName, response, nonce, nc, cnonce,
                                qop, realmName, md5a2);
    }

  }

  private static final class NonceInfo {
    private volatile long count;
    private volatile long timestamp;

    public void setCount(long l) {
      count = l;
    }

    public long getCount() {
      return count;
    }

    public void setTimestamp(long l) {
      timestamp = l;
    }

    public long getTimestamp() {
      return timestamp;
    }
  }
}
