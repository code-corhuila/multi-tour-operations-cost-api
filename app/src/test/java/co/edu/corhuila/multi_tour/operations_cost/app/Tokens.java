package co.edu.corhuila.multi_tour.operations_cost.app;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * The fixture of the tokens (spec section 6): the security microservice does not exist yet, so the tests
 * sign with a key pair made here and give the service only the public half.
 */
final class Tokens {

  static final KeyPair PAIR = generate();
  static final KeyPair OTHER = generate();

  private Tokens() {}

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The public key in the PEM form the service reads, on a single line with {@code \n} escapes. */
  static String publicKeyPem() {
    String body = Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded());
    return "-----BEGIN PUBLIC KEY-----\\n" + body + "\\n-----END PUBLIC KEY-----";
  }

  static JWTClaimsSet.Builder claims(String sub, long secondsFromNow, List<String> roles, String tenantId) {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .subject(sub)
            .expirationTime(new Date(System.currentTimeMillis() + secondsFromNow * 1000))
            .claim("roles", roles);
    return tenantId == null ? claims : claims.claim("tenantId", tenantId);
  }

  static JWTClaimsSet.Builder admin() {
    return claims("account-1", 600, List.of("PLATFORM_ADMINISTRATOR"), null);
  }

  static String rs256(java.security.PrivateKey key, JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  static String valid(JWTClaimsSet.Builder claims) {
    return rs256(PAIR.getPrivate(), claims.build());
  }

  /** The classic confusion: HS256 with the public key as the secret. */
  static String hs256WithThePublicKey(JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(PAIR.getPublic().getEncoded()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  static String unsigned(JWTClaimsSet claims) {
    return new PlainJWT(claims).serialize();
  }
}
