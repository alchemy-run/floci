package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.KeyAgreement;

final class EccNistKeyType implements KmsKeyType {

    @Override
    public void generateKeyMaterial(KmsKey key, String region) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(key.getKeySpec().curveName()));
        AsymmetricKeys.store(key, generator.generateKeyPair());
    }

    @Override
    public byte[] sign(KmsKey key, byte[] message, KmsKeySpec.Algorithm algorithm, KmsMessageType messageType)
            throws GeneralSecurityException {
        PrivateKey privateKey = AsymmetricKeys.privateKey(key, "EC");
        return AsymmetricKeys.sign(privateKey, jcaAlgorithm(algorithm, messageType), message);
    }

    @Override
    public boolean verify(KmsKey key, byte[] message, byte[] signature, KmsKeySpec.Algorithm algorithm,
                          KmsMessageType messageType) throws GeneralSecurityException {
        PublicKey publicKey = AsymmetricKeys.publicKey(key, "EC");
        return AsymmetricKeys.verify(publicKey, jcaAlgorithm(algorithm, messageType), message, signature);
    }

    @Override
    public byte[] deriveSharedSecret(KmsKey key, byte[] publicKeyDer) throws GeneralSecurityException {
        PrivateKey privateKey = AsymmetricKeys.privateKey(key, "EC");
        PublicKey peer = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKeyDer));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(peer, true);
        return agreement.generateSecret();
    }

    private static String jcaAlgorithm(KmsKeySpec.Algorithm algorithm, KmsMessageType messageType) {
        return switch (messageType) {
            case DIGEST -> "NONEwithECDSA";
            case RAW -> algorithm.getJavaName();
        };
    }
}
