package com.piggymetrics.auth.token;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Provides the RSA signing key for JWT access tokens.
 *
 * MIGRATION NOTE (H6 - security): the legacy auth-service used an InMemoryTokenStore with
 * NoOpPasswordEncoder and plaintext client secrets from environment variables. JWT is now
 * stateless and signed with RSA. For production the key pair MUST be loaded from a managed
 * secret store / KMS or a Nacos-encrypted config (and rotated), NOT generated per-boot as
 * done here for the refactoring baseline. This class centralizes that single point of change.
 */
public final class JwtKeyProvider {

	private static final RSAKey RSA_KEY = generate();

	private JwtKeyProvider() {
	}

	private static RSAKey generate() {
		try {
			KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
			keyPairGenerator.initialize(2048);
			KeyPair keyPair = keyPairGenerator.generateKeyPair();
			RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
			RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
			return new RSAKey.Builder(publicKey)
					.privateKey(privateKey)
					.keyID(UUID.randomUUID().toString())
					.build();
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to initialize RSA signing key", ex);
		}
	}

	public static RSAKey rsaKey() {
		return RSA_KEY;
	}

	public static JwtDecoder jwtDecoder() {
		try {
			return NimbusJwtDecoder.withPublicKey(RSA_KEY.toRSAPublicKey()).build();
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to build JwtDecoder from RSA key", ex);
		}
	}

}
